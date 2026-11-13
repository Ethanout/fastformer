package io.github.fastformer.server.task.block;

import io.github.fastformer.server.task.MemoryReservationAttempt;

import io.github.fastformer.fastplace.placement.PlacementUpdateMode;
import io.github.fastformer.server.recovery.JournalPreparation;
import io.github.fastformer.server.recovery.WorldJournalPreparation;
import io.github.fastformer.fastplace.world.WorldOperationCommit;
import io.github.fastformer.fastplace.world.WorldOperationPhase;
import io.github.fastformer.server.task.WorldTaskBudget;
import io.github.fastformer.server.task.WorldTaskContext;
import io.github.fastformer.server.task.runtime.LeasedJournaledTask;
import io.github.fastformer.server.task.TaskStep;
import io.github.fastformer.fastplace.world.memory.MemoryAdmission;
import io.github.fastformer.fastplace.world.memory.WorldOperationMemory;
import io.github.fastformer.fastplace.world.snapshot.BlockEntitySnapshot;
import io.github.fastformer.fastplace.world.snapshot.ReversibleBlockSnapshot;
import io.github.fastformer.workspace.model.ClientBlockSnapshot;
import io.github.fastformer.workspace.submission.OperationWorkspacePlan;
import io.github.fastformer.workspace.submission.OperationWorkspaceValidator;
import io.github.fastformer.workspace.submission.WorkspaceFailure;
import io.github.fastformer.workspace.submission.WorkspaceCompletion;
import io.github.fastformer.workspace.preview.WorkspaceScene;
import io.github.fastformer.fastplace.world.WorkspaceWriteScope;
import io.github.fastformer.fastplace.world.WorkspaceTickBarrier;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Validates and atomically applies a submitted, fully resolved workspace package. */
public final class SubmittedWorkspaceTask extends LeasedJournaledTask {
   private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
   private WorkspaceFailure failureReason = WorkspaceFailure.UNKNOWN;
   public WorkspaceFailure failureReason() { return failureReason; }
   private final UUID transferId;
   private final OperationWorkspacePlan plan;
   private final PlacementUpdateMode updateMode;
   private final int maxPlacement;
   private final ResourceKey<Level> dimension;
   private WorkspaceTickBarrier tickBarrier;
   private long blockEntityReserve;
   private Map<BlockPos, ClientBlockSnapshot> desired = Map.of();
   private Iterator<Map.Entry<BlockPos, ClientBlockSnapshot>> captureIterator;
   private boolean captureComplete;
   private final ArrayDeque<Map.Entry<BlockPos, ClientBlockSnapshot>> writable = new ArrayDeque<>();
   private Iterator<BlockPos> finalizationIterator;
   private List<Integer> invalidPartIds = List.of();
   private final java.util.LinkedHashSet<BlockPos> failedTargetPositions = new java.util.LinkedHashSet<>();
   private Phase phase = Phase.VALIDATE;

   public SubmittedWorkspaceTask(
      UUID transferId,
      OperationWorkspacePlan plan,
      PlacementUpdateMode updateMode,
      int maxPlacement,
      ResourceKey<Level> dimension
   ) {
      this(transferId, plan, updateMode, maxPlacement, dimension, new WorldJournalPreparation());
   }

   SubmittedWorkspaceTask(
      UUID transferId, OperationWorkspacePlan plan, PlacementUpdateMode updateMode, int maxPlacement,
      ResourceKey<Level> dimension, WorldJournalPreparation journalPreparation
   ) {
      super(journalPreparation);
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

   public WorkspaceCompletion completion(UUID owner, boolean applied, boolean retryable, boolean recoveryCreated) {
      return new WorkspaceCompletion(owner, dimension.location(), transferId, applied, retryable, recoveryCreated,
         failedPartIds(), failedTargetPositions(), failureReason());
   }

   public List<Integer> failedPartIds() {
      return invalidPartIds;
   }

   public List<BlockPos> failedTargetPositions() {
      return List.copyOf(failedTargetPositions);
   }

   @Override
   protected TaskStep tickBody(WorldTaskContext context, ServerLevel level, WorldTaskBudget budget) {
      if (tickBarrier == null && !desired.isEmpty()) tickBarrier = new WorkspaceTickBarrier(level, desired.keySet());
      this.metrics.phase(phaseMetric());
      while (budget.tryConsume()
         || (phase == Phase.WRITE && !writable.isEmpty() && !hasWrites() && budget.tryConsumeFirstWrite())) {
         if (!context.writesAllowed()) {
            return TaskStep.FAILED;
         }
         switch (phase) {
            case VALIDATE -> {
               CaptureStep capture = captureNext(level);
               if (capture == CaptureStep.RETRY) {
                  return TaskStep.ACTIVE;
               }
               if (capture != CaptureStep.ADVANCED) {
                  return switch (capture) {
                     case EMPTY -> TaskStep.EMPTY;
                     case EXCEEDED -> TaskStep.EXCEEDED;
                     case REJECTED -> TaskStep.MEMORY_UNSAFE;
                     case FAILED -> TaskStep.FAILED;
                     default -> throw new IllegalStateException("Unexpected workspace capture state " + capture);
                  };
               }
               int unjournaled = transaction.expectedCount() - journalSegments.count() - journalSegments.pending().size();
               if (unjournaled > 0 && captureComplete) {
                  phase = Phase.JOURNAL;
               } else if (captureComplete && writable.isEmpty() && journalSegments.pending().isEmpty()) {
                  phase = Phase.FINALIZE;
               }
            }
            case JOURNAL -> {
               JournalPreparation preparation = prepareJournal(context);
               if (preparation == JournalPreparation.PENDING) {
                  return TaskStep.ACTIVE;
               }
               if (preparation == JournalPreparation.FAILED) {
                  return TaskStep.JOURNAL_FAILED;
               }
               if (journalSegments.count() == transaction.expectedCount()) phase = Phase.WRITE;
            }
            case WRITE -> {
               if (writable.isEmpty()) {
                  if (!captureComplete) {
                     phase = Phase.VALIDATE;
                     continue;
                  }
                  if (transaction.expectedCount() > journalSegments.count()) {
                     phase = Phase.JOURNAL;
                     continue;
                  }
                  phase = Phase.FINALIZE;
                  continue;
               }
               if (!write(level, writable.removeFirst())) {
                  return TaskStep.FAILED;
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
                  failedTargetPositions.add(position.immutable());
                  return TaskStep.FAILED;
               }
               // The write path validates the target synchronously. Capture
               // the final world state so later world ticks do not turn a
               // completed placement into a false transaction failure.
               transaction.recordAfter(position, actual.orElseThrow());
            }
            case FINAL_JOURNAL -> {
               return prepareCommit();
            }
         }
      }
      return TaskStep.ACTIVE;
   }

   private CaptureStep captureNext(ServerLevel level) {
      if (desired.isEmpty() && !captureComplete) {
         TaskStep composed = composeDesired(level);
         if (composed != TaskStep.ACTIVE) {
            return switch (composed) {
               case EMPTY -> CaptureStep.EMPTY;
               case EXCEEDED -> CaptureStep.EXCEEDED;
               default -> CaptureStep.FAILED;
            };
         }
      }
      MemoryReservationAttempt currentReservation = reserveSnapshotMemory();
      if (currentReservation == MemoryReservationAttempt.REJECTED) {
         return CaptureStep.REJECTED;
      }
      if (currentReservation == MemoryReservationAttempt.RETRY) {
         return CaptureStep.RETRY;
      }
      if (captureIterator != null && captureIterator.hasNext()) {
         Map.Entry<BlockPos, ClientBlockSnapshot> entry = captureIterator.next();
         if (!captureExpected(level, entry)) {
            return CaptureStep.FAILED;
         }
         MemoryReservationAttempt capturedReservation = reserveSnapshotMemory();
         if (capturedReservation == MemoryReservationAttempt.REJECTED) {
            return CaptureStep.REJECTED;
         }
         if (capturedReservation == MemoryReservationAttempt.RETRY) {
            return CaptureStep.RETRY;
         }
      }
      if (captureIterator == null || !captureIterator.hasNext()) {
         captureComplete = true;
         captureIterator = null;
         var journalAdmission = WorldOperationMemory.journalAdmission(desired.size(), blockEntityReserve);
         MemoryReservationAttempt journalReservation = reserveMemory(journalAdmission);
         if (journalReservation == MemoryReservationAttempt.REJECTED) {
            return CaptureStep.REJECTED;
         }
         if (journalReservation == MemoryReservationAttempt.RETRY) {
            return CaptureStep.RETRY;
         }
      }
      return CaptureStep.ADVANCED;
   }

   private TaskStep composeDesired(ServerLevel level) {
      OperationWorkspaceValidator.Result validated = OperationWorkspaceValidator.validate(
         plan,
         maxPlacement
      );
      if (!validated.success()) {
         invalidPartIds = validated.invalidPartIds();
         reportFailure(WorkspaceFailure.INVALID_PLAN, null);
         return TaskStep.FAILED;
      }
      LinkedHashMap<BlockPos, ClientBlockSnapshot> composed = composeDesiredSnapshots(validated);
      if (composed.isEmpty()) {
         captureComplete = true;
         return TaskStep.EMPTY;
      }
      if (composed.size() > maxPlacement) {
         return TaskStep.EXCEEDED;
      }
      metrics.targetCount(composed.size());
      desired = Collections.unmodifiableMap(composed);
      tickBarrier = new WorkspaceTickBarrier(level, desired.keySet());
      captureIterator = desired.entrySet().iterator();
      return TaskStep.ACTIVE;
   }

   static LinkedHashMap<BlockPos, ClientBlockSnapshot> composeDesiredSnapshots(
      OperationWorkspaceValidator.Result validated
   ) {
      ClientBlockSnapshot air = new ClientBlockSnapshot(Blocks.AIR.defaultBlockState(), null);
      var scene = new WorkspaceScene<ClientBlockSnapshot>(snapshot -> !snapshot.state().isAir());
      scene.clearSources(validated.clears());
      scene.overlay(1, validated.writes());
      var composed = scene.desired(air);
      LinkedHashMap<BlockPos, ClientBlockSnapshot> supportFirst = new LinkedHashMap<>();
      orderedPositions(composed.keySet()).forEach(pos -> supportFirst.put(pos, composed.get(pos)));
      return supportFirst;
   }

   static List<BlockPos> orderedPositions(Collection<BlockPos> positions) {
      List<BlockPos> ordered = new java.util.ArrayList<>(positions);
      ordered.sort(Comparator.<BlockPos>comparingInt(BlockPos::getY)
         .thenComparingInt(BlockPos::getX)
         .thenComparingInt(BlockPos::getZ));
      return ordered;
   }

   private boolean captureExpected(ServerLevel level, Map.Entry<BlockPos, ClientBlockSnapshot> entry) {
      if (!validSnapshot(level, entry.getKey(), entry.getValue())) {
         reportFailure(WorkspaceFailure.INVALID_BLOCK_ENTITY, entry.getKey());
         failedTargetPositions.add(entry.getKey().immutable());
         return false;
      }
      Optional<ReversibleBlockSnapshot> captured = ReversibleBlockSnapshot.capture(level, entry.getKey());
      if (captured.isEmpty()) {
         reportFailure(WorkspaceFailure.SNAPSHOT_UNAVAILABLE, entry.getKey());
         failedTargetPositions.add(entry.getKey().immutable());
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
         reportFailure(WorkspaceFailure.WORLD_CHANGED, pos);
         failedTargetPositions.add(pos.immutable());
         return false;
      }
      ClientBlockSnapshot target = entry.getValue();
      ReversibleBlockSnapshot desiredSnapshot = new ReversibleBlockSnapshot(
         pos,
         target.state(),
         target.state().getFluidState(),
         target.blockEntity() == null ? null : new BlockEntitySnapshot(target.blockEntity())
      );
      transaction.recordBeforeOnce(before);
      boolean placed;
      try (var scope = new WorkspaceWriteScope(level, transaction)) {
         placed = desiredSnapshot.placeAt(level, pos, updateMode.flags());
         if (scope.conflict() != null) {
            reportFailure(WorkspaceFailure.CALLBACK_CONFLICT, scope.conflict());
            failedTargetPositions.add(scope.conflict());
            placed = false;
         }
      }
      if (!placed) {
         if (failureReason == WorkspaceFailure.UNKNOWN) reportFailure(WorkspaceFailure.WRITE_DID_NOT_MATCH_TARGET, pos);
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
         transaction.recordAfter(pos, currentSnapshot(level, pos));
         return false;
      }
      transaction.recordAfter(pos, actual.orElseThrow());
      transaction.recordExpected(pos, actual.orElseThrow());
      return true;
   }

   private static ReversibleBlockSnapshot currentSnapshot(ServerLevel level, BlockPos pos) {
      return new ReversibleBlockSnapshot(pos, level.getBlockState(pos), level.getFluidState(pos), null);
   }

   private JournalPreparation prepareJournal(WorldTaskContext context) {
      List<ReversibleBlockSnapshot> slice = journalSegments.prepare(transaction);
      if (slice.isEmpty()) return JournalPreparation.READY;
      JournalPreparation preparation = journalPreparation.journal() == null
         ? journalPreparation.poll(() -> context.recovery().begin(
            context.owner(), dimension, slice, predictedFinalSnapshots(slice), operationId()
         ), context)
         : journalPreparation.pollAppend(() ->
            journalPreparation.journal().appendSegment(slice, predictedFinalSnapshots(slice))
         );
      if (preparation == JournalPreparation.READY && !slice.isEmpty()) {
         metrics.journalReady();
         for (ReversibleBlockSnapshot snapshot : slice) {
            ClientBlockSnapshot target = desired.get(snapshot.pos());
            if (target == null) {
               return JournalPreparation.FAILED;
            }
            writable.addLast(Map.entry(snapshot.pos(), target));
         }
         journalSegments.acknowledge(slice);
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

   private TaskStep prepareCommit() {
      if (operationCommit == null) {
         MemoryReservationAttempt commitReservation = reserveCommitMemory();
         if (commitReservation == MemoryReservationAttempt.RETRY) {
            return TaskStep.ACTIVE;
         }
         if (commitReservation == MemoryReservationAttempt.REJECTED) {
            return TaskStep.MEMORY_UNSAFE;
         }
         operationCommit = WorldOperationCommit.begin(dimension, transaction, journalPreparation.journal(), journalPreparation.executor());
      }
      JournalPreparation preparation = operationCommit.poll();
      if (preparation == JournalPreparation.PENDING) {
         return TaskStep.ACTIVE;
      }
      return preparation == JournalPreparation.READY
         ? TaskStep.COMPLETE
         : TaskStep.FAILED;
   }

   private void releaseWriteStagingForCommit() {
      transaction.clearExpected();
      desired = Map.of();
      writable.clear();
      journalSegments.clearPending();
      captureIterator = null;
   }

   private MemoryReservationAttempt reserveCommitMemory() {
      var admission = WorldOperationMemory.commitAdmission(
         transaction.snapshotCount(), transaction.largestSideCount(), transaction.commitBlockEntityReserve()
      );
      return reserveMemory(admission);
   }

   private MemoryReservationAttempt reserveSnapshotMemory() {
      return reserveMemory(WorldOperationMemory.snapshotAdmission(desired.size(), blockEntityReserve));
   }

   private MemoryReservationAttempt reserveMemory(MemoryAdmission admission) {
      memoryThrottled = admission.throttled();
      if (!admission.allowed()) {
         return MemoryReservationAttempt.REJECTED;
      }
      if (memoryReservation == null) {
         memoryReservation = WorldOperationMemory.reserve(admission).orElse(null);
         return memoryReservation == null ? MemoryReservationAttempt.RETRY : MemoryReservationAttempt.ACQUIRED;
      }
      return memoryReservation.resize(admission.requestedBytes(), admission.usableBytes())
         ? MemoryReservationAttempt.ACQUIRED
         : MemoryReservationAttempt.RETRY;
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
   public ResourceKey<Level> dimension() {
      return dimension;
   }

   @Override
   public String metricsSummary() {
      return metrics.summary() + ", workspaceFailure=" + failureReason;
   }

   private void reportFailure(WorkspaceFailure reason, BlockPos pos) {
      failureReason = reason;
      LOGGER.warn("Workspace {} failed: reason={}, position={}, phase={}", transferId, reason, pos, phase);
   }

   @Override
   public MemoryReservationAttempt reserveWorkingSet() {
      if (memoryReservation != null) {
         return MemoryReservationAttempt.ACQUIRED;
      }
      if (phase != Phase.FINAL_JOURNAL && desired.isEmpty()) {
         return MemoryReservationAttempt.ACQUIRED;
      }
      var admission = phase == Phase.FINAL_JOURNAL
         ? WorldOperationMemory.commitAdmission(
            transaction.snapshotCount(), transaction.largestSideCount(), transaction.commitBlockEntityReserve()
         )
         : phase == Phase.JOURNAL
            ? WorldOperationMemory.journalAdmission(desired.size(), blockEntityReserve)
            : WorldOperationMemory.snapshotAdmission(desired.size(), blockEntityReserve);
      return reserveMemory(admission);
   }

   @Override
   protected void clearCommittedStaging() {
      desired = Map.of();
      writable.clear();
      captureIterator = null;
      finalizationIterator = null;
   }

   @Override
   protected void releaseAttachedResources() {
      WorkspaceTickBarrier released = tickBarrier;
      tickBarrier = null;
      if (released != null) released.close();
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

   private enum CaptureStep {
      ADVANCED,
      RETRY,
      EMPTY,
      EXCEEDED,
      REJECTED,
      FAILED
   }
}
