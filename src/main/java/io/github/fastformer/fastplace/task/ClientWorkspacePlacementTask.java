package io.github.fastformer.fastplace.task;

import io.github.fastformer.client.operation.model.ClientBlockSnapshot;
import io.github.fastformer.fastplace.world.BlockEntitySnapshot;
import io.github.fastformer.fastplace.world.JournalPreparation;
import io.github.fastformer.fastplace.OperationWorkspacePlan;
import io.github.fastformer.fastplace.OperationWorkspaceValidator;
import io.github.fastformer.fastplace.world.PersistentRecoveryJournal;
import io.github.fastformer.fastplace.PlacementUpdateMode;
import io.github.fastformer.fastplace.world.ReversibleBlockSnapshot;
import io.github.fastformer.fastplace.world.WorldChangeTransaction;
import io.github.fastformer.fastplace.world.WorldOperationMemory;
import io.github.fastformer.fastplace.world.WorldOperationCommit;
import io.github.fastformer.fastplace.world.WorldOperationMetrics;
import io.github.fastformer.fastplace.world.WorldJournalPreparation;
import io.github.fastformer.fastplace.world.WorldBatchFeedback;
import io.github.fastformer.fastplace.world.WorldOperationPhase;
import io.github.fastformer.fastplace.world.MemoryReservation;
import io.github.fastformer.fastplace.world.WorldTaskBudget;
import io.github.fastformer.fastplace.world.WorldTaskContext;
import io.github.fastformer.fastplace.world.WorldWriteCoordinator;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Validates and atomically applies a fully client-resolved workspace package. */
public final class ClientWorkspacePlacementTask implements WorldOperationTask {
   private final UUID transferId;
   private final OperationWorkspacePlan plan;
   private final PlacementUpdateMode updateMode;
   private final int maxPlacement;
   private final ResourceKey<Level> dimension;
   private final WorldOperationMetrics metrics = new WorldOperationMetrics();
   private final WorldBatchFeedback batchFeedback = new WorldBatchFeedback(this.metrics);
   private MemoryReservation memoryReservation;
   private long blockEntityReserve;
   private boolean memoryThrottled;
   private final WorldChangeTransaction transaction = new WorldChangeTransaction();
   private WorldOperationCommit operationCommit;
   private Map<BlockPos, ClientBlockSnapshot> desired = Map.of();
   private Iterator<Map.Entry<BlockPos, ClientBlockSnapshot>> captureIterator;
   private boolean captureComplete;
   private int journaledCount;
   private List<ReversibleBlockSnapshot> pendingJournalSlice = List.of();
   private final ArrayDeque<Map.Entry<BlockPos, ClientBlockSnapshot>> writable = new ArrayDeque<>();
   private Iterator<BlockPos> finalizationIterator;
   private final WorldJournalPreparation journalPreparation = new WorldJournalPreparation();
   private List<Integer> invalidPartIds = List.of();
   private Phase phase = Phase.VALIDATE;

   public ClientWorkspacePlacementTask(
      UUID transferId,
      OperationWorkspacePlan plan,
      PlacementUpdateMode updateMode,
      int maxPlacement,
      ResourceKey<Level> dimension
   ) {
      this.transferId = transferId;
      this.plan = plan;
      this.updateMode = updateMode;
      this.maxPlacement = maxPlacement;
      this.dimension = dimension;
      this.metrics.queued();
   }

   public UUID transferId() {
      return transferId;
   }

   public List<Integer> failedPartIds() {
      return invalidPartIds;
   }

   @Override
   public OperationTaskResult tick(WorldTaskContext context, ServerLevel level, WorldTaskBudget budget) {
      this.metrics.phase(phaseMetric());
      while (budget.tryConsume()) {
         if (!PersistentRecoveryJournal.writesAllowed()) {
            return OperationTaskResult.FAILED;
         }
         switch (phase) {
            case VALIDATE -> {
               OperationTaskResult validation = captureNext(level);
               if (validation != OperationTaskResult.ACTIVE) {
                  return validation;
               }
               int unjournaled = transaction.expectedCount() - journaledCount - pendingJournalSlice.size();
               if (unjournaled > 0 && (captureComplete
                  || unjournaled >= PersistentRecoveryJournal.segmentBatchSize(journaledCount, unjournaled))) {
                  phase = Phase.JOURNAL;
               } else if (captureComplete && writable.isEmpty() && pendingJournalSlice.isEmpty()) {
                  phase = Phase.FINALIZE;
               }
            }
            case JOURNAL -> {
               JournalPreparation preparation = prepareJournal(context);
               if (preparation == JournalPreparation.PENDING) {
                  return OperationTaskResult.ACTIVE;
               }
               if (preparation == JournalPreparation.FAILED) {
                  return OperationTaskResult.JOURNAL_FAILED;
               }
               phase = Phase.WRITE;
            }
            case WRITE -> {
               if (writable.isEmpty()) {
                  if (!captureComplete) {
                     phase = Phase.VALIDATE;
                     continue;
                  }
                  if (transaction.expectedCount() > journaledCount) {
                     phase = Phase.JOURNAL;
                     continue;
                  }
                  phase = Phase.FINALIZE;
                  continue;
               }
               if (!write(level, writable.removeFirst())) {
                  return OperationTaskResult.FAILED;
               }
            }
            case FINALIZE -> {
               if (finalizationIterator == null) {
                  finalizationIterator = transaction.afterPositions();
               }
               if (!finalizationIterator.hasNext()) {
                  releaseWriteStagingForCommit();
                  phase = Phase.FINAL_JOURNAL;
                  continue;
               }
               BlockPos position = finalizationIterator.next();
               Optional<ReversibleBlockSnapshot> actual = ReversibleBlockSnapshot.capture(level, position);
               if (actual.isEmpty()) {
                  return OperationTaskResult.FAILED;
               }
               transaction.recordAfter(position, actual.orElseThrow());
            }
            case FINAL_JOURNAL -> {
               JournalPreparation preparation = prepareCommit();
               if (preparation == JournalPreparation.PENDING) {
                  return OperationTaskResult.ACTIVE;
               }
               return preparation == JournalPreparation.READY
                  ? OperationTaskResult.COMPLETE
                  : OperationTaskResult.FAILED;
            }
         }
      }
      return OperationTaskResult.ACTIVE;
   }

   private OperationTaskResult captureNext(ServerLevel level) {
      if (desired.isEmpty() && !captureComplete) {
         OperationTaskResult composed = composeDesired(level);
         if (composed != OperationTaskResult.ACTIVE) {
            return composed;
         }
      }
      if (captureIterator != null && captureIterator.hasNext()) {
         Map.Entry<BlockPos, ClientBlockSnapshot> entry = captureIterator.next();
         if (!captureExpected(level, entry)) {
            return OperationTaskResult.FAILED;
         }
         var admission = WorldOperationMemory.snapshotAdmission(desired.size(), blockEntityReserve);
         if (!admission.allowed()) {
            return OperationTaskResult.MEMORY_UNSAFE;
         }
         memoryThrottled = admission.throttled();
         if (memoryReservation == null) {
            memoryReservation = WorldOperationMemory.reserve(admission).orElse(null);
         } else if (!memoryReservation.resize(admission.requestedBytes(), admission.usableBytes())) {
            return OperationTaskResult.MEMORY_UNSAFE;
         }
         if (memoryReservation == null) {
            return OperationTaskResult.MEMORY_UNSAFE;
         }
      }
      if (captureIterator == null || !captureIterator.hasNext()) {
         captureComplete = true;
         captureIterator = null;
         var journalAdmission = WorldOperationMemory.journalAdmission(desired.size(), blockEntityReserve);
         memoryThrottled = journalAdmission.throttled();
         if (!journalAdmission.allowed()
            || memoryReservation == null
            || !memoryReservation.resize(journalAdmission.requestedBytes(), journalAdmission.usableBytes())) {
            return OperationTaskResult.MEMORY_UNSAFE;
         }
      }
      return OperationTaskResult.ACTIVE;
   }

   private OperationTaskResult composeDesired(ServerLevel level) {
      OperationWorkspaceValidator.Result validated = OperationWorkspaceValidator.validate(
         plan,
         pos -> ReversibleBlockSnapshot.capture(level, pos).map(ClientWorkspacePlacementTask::clientSnapshot),
         maxPlacement
      );
      if (!validated.success()) {
         invalidPartIds = validated.invalidPartIds();
         return OperationTaskResult.FAILED;
      }
      LinkedHashMap<BlockPos, ClientBlockSnapshot> composed = composeDesiredSnapshots(validated);
      if (composed.isEmpty()) {
         captureComplete = true;
         return OperationTaskResult.EMPTY;
      }
      if (composed.size() > maxPlacement) {
         return OperationTaskResult.EXCEEDED;
      }
      metrics.targetCount(composed.size());
      desired = Map.copyOf(composed);
      captureIterator = desired.entrySet().iterator();
      return OperationTaskResult.ACTIVE;
   }

   private static LinkedHashMap<BlockPos, ClientBlockSnapshot> composeDesiredSnapshots(
      OperationWorkspaceValidator.Result validated
   ) {
      LinkedHashMap<BlockPos, ClientBlockSnapshot> composed = new LinkedHashMap<>();
      ClientBlockSnapshot air = new ClientBlockSnapshot(Blocks.AIR.defaultBlockState(), null);
      validated.clears().forEach(pos -> composed.put(pos.immutable(), air));
      composed.putAll(validated.writes());
      return composed;
   }

   private boolean captureExpected(ServerLevel level, Map.Entry<BlockPos, ClientBlockSnapshot> entry) {
      if (!validSnapshot(level, entry.getKey(), entry.getValue())) {
         return false;
      }
      Optional<ReversibleBlockSnapshot> captured = ReversibleBlockSnapshot.capture(level, entry.getKey());
      if (captured.isEmpty()) {
         return false;
      }
      ReversibleBlockSnapshot snapshot = captured.orElseThrow();
      metrics.snapshotCaptured();
      transaction.recordExpected(entry.getKey(), snapshot);
      blockEntityReserve = WorldOperationMemory.saturatingAdd(
         blockEntityReserve, WorldOperationMemory.snapshotNbtReserve(snapshot)
      );
      return true;
   }

   private static boolean validSnapshot(ServerLevel level, BlockPos pos, ClientBlockSnapshot snapshot) {
      if (snapshot.blockEntity() == null) {
         return true;
      }
      if (!(snapshot.state().getBlock() instanceof EntityBlock entityBlock)) {
         return false;
      }
      try {
         BlockEntity entity = entityBlock.newBlockEntity(pos, snapshot.state());
         if (entity == null) {
            return false;
         }
         loadAt(entity, snapshot.blockEntity(), level, pos);
         return true;
      } catch (RuntimeException exception) {
         return false;
      }
   }

   private boolean write(ServerLevel level, Map.Entry<BlockPos, ClientBlockSnapshot> entry) {
      BlockPos pos = entry.getKey();
      ReversibleBlockSnapshot before = transaction.expectedAt(pos);
      if (before == null || !before.matches(level, pos)) {
         return false;
      }
      ClientBlockSnapshot target = entry.getValue();
      ReversibleBlockSnapshot desiredSnapshot = new ReversibleBlockSnapshot(
         pos,
         target.state(),
         target.state().getFluidState(),
         target.blockEntity() == null ? null : new BlockEntitySnapshot(target.blockEntity())
      );
      transaction.recordBefore(before);
      if (!desiredSnapshot.placeAt(level, pos, updateMode.flags())) {
         metrics.writeAttempt(false);
         transaction.recordAfter(
            pos,
            ReversibleBlockSnapshot.capture(level, pos).orElseGet(() -> currentSnapshot(level, pos))
         );
         return false;
      }
      metrics.writeAttempt(true);
      Optional<ReversibleBlockSnapshot> actual = ReversibleBlockSnapshot.capture(level, pos);
      if (actual.isEmpty()) {
         return false;
      }
      transaction.recordAfter(pos, actual.orElseThrow());
      // The primary block write is already durable. A neighbour refresh is a
      // best-effort side effect and must not replace the expected snapshot or
      // turn a successful write into an immediate rollback.
      if (updateMode == PlacementUpdateMode.NORMAL) {
         ReversibleBlockSnapshot.refreshTaskOwnedNeighbors(level, pos, transaction);
      }
      return true;
   }

   private static ReversibleBlockSnapshot currentSnapshot(ServerLevel level, BlockPos pos) {
      return new ReversibleBlockSnapshot(pos, level.getBlockState(pos), level.getFluidState(pos), null);
   }

   private JournalPreparation prepareJournal(WorldTaskContext context) {
      if (pendingJournalSlice.isEmpty()) {
         int from = journaledCount;
         int remaining = transaction.expectedCount() - from;
         if (remaining <= 0) {
            return JournalPreparation.READY;
         }
         int size = PersistentRecoveryJournal.segmentBatchSize(from, remaining);
         pendingJournalSlice = transaction.expectedRange(from, from + size);
      }
      List<ReversibleBlockSnapshot> slice = pendingJournalSlice;
      JournalPreparation preparation = journalPreparation.journal() == null
         ? journalPreparation.poll(() -> PersistentRecoveryJournal.begin(
            context.server(), context.owner(), dimension, slice, predictedFinalSnapshots(slice), operationId()
         ))
         : journalPreparation.pollAppend(() ->
            journalPreparation.journal().appendSegment(slice, predictedFinalSnapshots(slice))
         );
      if (preparation == JournalPreparation.READY && !slice.isEmpty()) {
         for (ReversibleBlockSnapshot snapshot : slice) {
            ClientBlockSnapshot target = desired.get(snapshot.pos());
            if (target == null) {
               return JournalPreparation.FAILED;
            }
            writable.addLast(Map.entry(snapshot.pos(), target));
         }
         journaledCount += slice.size();
         pendingJournalSlice = List.of();
      }
      return preparation;
   }

   private Collection<ReversibleBlockSnapshot> predictedFinalSnapshots(
      Collection<ReversibleBlockSnapshot> before
   ) {
      return PlacementTask.lazyMappedCollection(before, original -> {
         ClientBlockSnapshot target = desired.get(original.pos());
         if (target == null) {
            throw new IllegalStateException("Workspace journal target is missing");
         }
         return new ReversibleBlockSnapshot(
            original.pos(),
            target.state(),
            target.state().getFluidState(),
            target.blockEntity() == null ? null : new BlockEntitySnapshot(target.blockEntity())
         );
      });
   }

   private JournalPreparation prepareCommit() {
      if (operationCommit == null) {
         if (!reserveCommitMemory()) {
            return JournalPreparation.PENDING;
         }
         operationCommit = WorldOperationCommit.begin(dimension, transaction, journalPreparation.journal());
      }
      return operationCommit.poll();
   }

   private void releaseWriteStagingForCommit() {
      transaction.clearExpected();
      desired = Map.of();
      writable.clear();
      pendingJournalSlice = List.of();
      captureIterator = null;
   }

   private boolean reserveCommitMemory() {
      var admission = WorldOperationMemory.commitAdmission(
         transaction.snapshotCount(), transaction.largestSideCount(), transaction.commitBlockEntityReserve()
      );
      memoryThrottled = admission.throttled();
      if (!admission.allowed()) {
         return false;
      }
      if (memoryReservation == null) {
         memoryReservation = WorldOperationMemory.reserve(admission).orElse(null);
         return memoryReservation != null;
      }
      return memoryReservation.resize(admission.requestedBytes(), admission.usableBytes());
   }

   private static ClientBlockSnapshot clientSnapshot(ReversibleBlockSnapshot snapshot) {
      return new ClientBlockSnapshot(
         snapshot.state(), snapshot.blockEntity() == null ? null : snapshot.blockEntity().data()
      );
   }

   private static void loadAt(
      BlockEntity entity,
      CompoundTag tag,
      ServerLevel level,
      BlockPos pos
   ) {
      CompoundTag positioned = tag.copy();
      positioned.putInt("x", pos.getX());
      positioned.putInt("y", pos.getY());
      positioned.putInt("z", pos.getZ());
      entity.loadWithComponents(positioned, level.registryAccess());
   }

   @Override
   public String phaseName() {
      return switch (phase) {
         case VALIDATE -> "验证工作区";
         case JOURNAL -> "写入安全日志";
         case WRITE -> "写入";
         case FINALIZE -> "确认最终状态";
         case FINAL_JOURNAL -> "压缩最终恢复状态";
      };
   }

   @Override
   public void cancelJournalPreparation() {
      journalPreparation.cancel();
   }

   @Override
   public PersistentRecoveryJournal journal() {
      return journalPreparation.journal();
   }

   @Override
   public boolean acquireLease(WorldTaskContext context) {
      boolean acquired = WorldWriteCoordinator.tryAcquire(context.server(), dimension, context.owner());
      if (acquired) {
         this.metrics.leaseAcquired();
      }
      return acquired;
   }

   @Override
   public void releaseLease(WorldTaskContext context) {
      WorldWriteCoordinator.release(context.server(), dimension, context.owner());
   }

   @Override
   public void releaseAfterCancelledJournal(WorldTaskContext context) {
      if (operationCommit != null) {
         operationCommit.cancel();
      }
      journalPreparation.releaseAfterCancellation(context, dimension);
   }

   @Override
   public WorldChangeTransaction transaction() {
      return transaction;
   }

   @Override
   public WorldOperationCommit operationCommit() {
      return operationCommit;
   }

   @Override
   public ResourceKey<Level> dimension() {
      return dimension;
   }

   @Override
   public UUID operationId() {
      return metrics.operationId();
   }

   @Override
   public String metricsSummary() {
      return metrics.summary();
   }

   @Override
   public void markWorldUnloaded() {
      metrics.phase(WorldOperationPhase.WORLD_UNLOADED);
   }

   @Override
   public void markComplete() {
      metrics.complete();
   }

   @Override
   public boolean memoryThrottled() {
      return memoryThrottled;
   }

   @Override
   public boolean ensureMemoryReservation() {
      if (memoryReservation != null) {
         return true;
      }
      if (phase != Phase.FINAL_JOURNAL && desired.isEmpty()) {
         return true;
      }
      var admission = phase == Phase.FINAL_JOURNAL
         ? WorldOperationMemory.commitAdmission(
            transaction.snapshotCount(), transaction.largestSideCount(), transaction.commitBlockEntityReserve()
         )
         : phase == Phase.JOURNAL
            ? WorldOperationMemory.journalAdmission(desired.size(), blockEntityReserve)
            : WorldOperationMemory.snapshotAdmission(desired.size(), blockEntityReserve);
      if (!admission.allowed()) {
         memoryThrottled = admission.throttled();
         return false;
      }
      memoryThrottled = admission.throttled();
      memoryReservation = WorldOperationMemory.reserve(admission).orElse(null);
      return memoryReservation != null;
   }

   @Override
   public int previousBatchCells() {
      return batchFeedback.cells();
   }

   @Override
   public long previousBatchNanos() {
      return batchFeedback.elapsedNanos();
   }

   @Override
   public void recordBatch(int cells, long elapsedNanos) {
      batchFeedback.record(cells, elapsedNanos);
   }

   @Override
   public void releaseMemoryReservation() {
      if (memoryReservation != null) {
         MemoryReservation reservation = memoryReservation;
         memoryReservation = null;
         CompletableFuture<?> publicationCompletion = operationCommit == null
            ? CompletableFuture.completedFuture(null)
            : operationCommit.completion();
         journalPreparation.releaseWhenIdle(reservation, publicationCompletion);
      }
   }

   @Override
   public void releaseCommittedTransactionState() {
      transaction.releaseWriteState();
      operationCommit = null;
      desired = Map.of();
      writable.clear();
      captureIterator = null;
      finalizationIterator = null;
   }

   private WorldOperationPhase phaseMetric() {
      return switch (phase) {
         case VALIDATE -> WorldOperationPhase.SNAPSHOT;
         case JOURNAL -> WorldOperationPhase.JOURNAL;
         case WRITE -> WorldOperationPhase.WRITE;
         case FINALIZE -> WorldOperationPhase.FINALIZE;
         case FINAL_JOURNAL -> WorldOperationPhase.COMMIT;
      };
   }

   private enum Phase {
      VALIDATE,
      JOURNAL,
      WRITE,
      FINALIZE,
      FINAL_JOURNAL
   }
}
