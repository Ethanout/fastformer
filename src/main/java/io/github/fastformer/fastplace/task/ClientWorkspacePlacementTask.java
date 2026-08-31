package io.github.fastformer.fastplace.task;

import io.github.fastformer.client.operation.model.ClientBlockSnapshot;
import io.github.fastformer.fastplace.world.BlockEntitySnapshot;
import io.github.fastformer.fastplace.world.JournalPreparation;
import io.github.fastformer.fastplace.OperationWorkspacePlan;
import io.github.fastformer.fastplace.OperationWorkspaceValidator;
import io.github.fastformer.fastplace.world.PersistentRecoveryJournal;
import io.github.fastformer.fastplace.PlacementUpdateMode;
import io.github.fastformer.fastplace.world.ReversibleBlockSnapshot;
import io.github.fastformer.fastplace.world.WorldChangeBatch;
import io.github.fastformer.fastplace.world.WorldOperationCommit;
import io.github.fastformer.fastplace.world.WorldOperationMemory;
import io.github.fastformer.fastplace.world.WorldTaskBudget;
import io.github.fastformer.fastplace.world.WorldTaskContext;
import io.github.fastformer.fastplace.world.WorldWriteCoordinator;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
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
   private final ArrayDeque<ReversibleBlockSnapshot> undo = new ArrayDeque<>();
   private final Map<BlockPos, ReversibleBlockSnapshot> expected = new HashMap<>();
   private final Map<BlockPos, ReversibleBlockSnapshot> after = new HashMap<>();
   private Map<BlockPos, ClientBlockSnapshot> desired = Map.of();
   private Iterator<Map.Entry<BlockPos, ClientBlockSnapshot>> writeIterator;
   private Iterator<Map.Entry<BlockPos, ReversibleBlockSnapshot>> finalizationIterator;
   private PersistentRecoveryJournal journal;
   private CompletableFuture<Optional<PersistentRecoveryJournal>> journalFuture;
   private WorldOperationCommit commitPreparation;
   private boolean cancelled;
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
   }

   public UUID transferId() {
      return transferId;
   }

   public List<Integer> failedPartIds() {
      return invalidPartIds;
   }

   @Override
   public OperationTaskResult tick(WorldTaskContext context, ServerLevel level, WorldTaskBudget budget) {
      while (budget.tryConsume()) {
         if (!PersistentRecoveryJournal.writesAllowed()) {
            return OperationTaskResult.FAILED;
         }
         switch (phase) {
            case VALIDATE -> {
               OperationTaskResult validation = validate(level);
               if (validation != OperationTaskResult.ACTIVE) {
                  return validation;
               }
               phase = Phase.JOURNAL;
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
               writeIterator = desired.entrySet().iterator();
            }
            case WRITE -> {
               if (!writeIterator.hasNext()) {
                  phase = Phase.FINALIZE;
                  continue;
               }
               if (!write(level, writeIterator.next())) {
                  return OperationTaskResult.FAILED;
               }
            }
            case FINALIZE -> {
               if (finalizationIterator == null) {
                  finalizationIterator = after.entrySet().iterator();
               }
               if (!finalizationIterator.hasNext()) {
                  phase = Phase.FINAL_JOURNAL;
                  continue;
               }
               Map.Entry<BlockPos, ReversibleBlockSnapshot> entry = finalizationIterator.next();
               Optional<ReversibleBlockSnapshot> actual = ReversibleBlockSnapshot.capture(level, entry.getKey());
               if (actual.isEmpty()) {
                  return OperationTaskResult.FAILED;
               }
               entry.setValue(actual.orElseThrow());
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

   private OperationTaskResult validate(ServerLevel level) {
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
         return OperationTaskResult.EMPTY;
      }
      if (composed.size() > maxPlacement) {
         return OperationTaskResult.EXCEEDED;
      }
      long blockEntityReserve = captureExpectedSnapshots(level, composed);
      if (blockEntityReserve < 0L) {
         return OperationTaskResult.FAILED;
      }
      if (!WorldOperationMemory.canPrepare(composed.size(), blockEntityReserve)) {
         return OperationTaskResult.MEMORY_UNSAFE;
      }
      desired = Map.copyOf(composed);
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

   /** Returns -1 when an external snapshot cannot be captured or validated. */
   private long captureExpectedSnapshots(
      ServerLevel level,
      Map<BlockPos, ClientBlockSnapshot> composed
   ) {
      long blockEntityReserve = 0L;
      for (Map.Entry<BlockPos, ClientBlockSnapshot> entry : composed.entrySet()) {
         if (!validSnapshot(level, entry.getKey(), entry.getValue())) {
            return -1L;
         }
         Optional<ReversibleBlockSnapshot> captured = ReversibleBlockSnapshot.capture(level, entry.getKey());
         if (captured.isEmpty()) {
            return -1L;
         }
         ReversibleBlockSnapshot snapshot = captured.orElseThrow();
         expected.put(entry.getKey().immutable(), snapshot);
         blockEntityReserve = WorldOperationMemory.saturatingAdd(
            blockEntityReserve, WorldOperationMemory.snapshotNbtReserve(snapshot)
         );
      }
      return blockEntityReserve;
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
      ReversibleBlockSnapshot before = expected.get(pos);
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
      undo.addFirst(before);
      if (!desiredSnapshot.placeAt(level, pos, updateMode.flags())) {
         ReversibleBlockSnapshot.capture(level, pos).ifPresent(actual -> after.put(pos.immutable(), actual));
         return false;
      }
      Optional<ReversibleBlockSnapshot> actual = ReversibleBlockSnapshot.capture(level, pos);
      if (actual.isEmpty()) {
         return false;
      }
      expected.put(pos.immutable(), actual.orElseThrow());
      after.put(pos.immutable(), actual.orElseThrow());
      return updateMode != PlacementUpdateMode.NORMAL || ReversibleBlockSnapshot.refreshTaskOwnedNeighbors(
         level, pos, expected, undo, after
      );
   }

   private JournalPreparation prepareJournal(WorldTaskContext context) {
      if (journal != null) {
         return JournalPreparation.READY;
      }
      if (journalFuture == null) {
         Optional<List<ReversibleBlockSnapshot>> predicted = predictedFinalSnapshots();
         if (predicted.isEmpty()) {
            return JournalPreparation.FAILED;
         }
         List<ReversibleBlockSnapshot> before = new ArrayList<>(expected.values());
         journalFuture = CompletableFuture.supplyAsync(
            () -> PersistentRecoveryJournal.begin(
               context.server(), context.owner(), dimension, before, predicted.orElseThrow()
            ),
            PersistentRecoveryJournal.executor()
         );
         return JournalPreparation.PENDING;
      }
      if (!journalFuture.isDone()) {
         return JournalPreparation.PENDING;
      }
      try {
         journal = journalFuture.join().orElse(null);
         return journal == null ? JournalPreparation.FAILED : JournalPreparation.READY;
      } catch (RuntimeException exception) {
         return JournalPreparation.FAILED;
      }
   }

   private Optional<List<ReversibleBlockSnapshot>> predictedFinalSnapshots() {
      List<ReversibleBlockSnapshot> before = new ArrayList<>(expected.values());
      List<ReversibleBlockSnapshot> predicted = new ArrayList<>(before.size());
      for (ReversibleBlockSnapshot original : before) {
         ClientBlockSnapshot target = desired.get(original.pos());
         if (target == null) {
            return Optional.empty();
         }
         predicted.add(new ReversibleBlockSnapshot(
            original.pos(),
            target.state(),
            target.state().getFluidState(),
            target.blockEntity() == null ? null : new BlockEntitySnapshot(target.blockEntity())
         ));
      }
      return Optional.of(predicted);
   }

   private JournalPreparation prepareCommit() {
      if (commitPreparation == null) {
         commitPreparation = WorldOperationCommit.begin(dimension, undo, after, journal);
      }
      return commitPreparation.poll();
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
      cancelled = true;
      if (commitPreparation != null) {
         commitPreparation.cancel();
      }
      if (journalFuture != null) {
         journalFuture.whenComplete((created, exception) -> {
            if (cancelled && journal == null && exception == null && created != null) {
               created.ifPresent(PersistentRecoveryJournal::discardUnused);
            }
         });
      }
   }

   @Override
   public PersistentRecoveryJournal journal() {
      return journal;
   }

   @Override
   public boolean acquireLease(WorldTaskContext context) {
      return WorldWriteCoordinator.tryAcquire(context.server(), dimension, context.owner());
   }

   @Override
   public void releaseLease(WorldTaskContext context) {
      WorldWriteCoordinator.release(context.server(), dimension, context.owner());
   }

   @Override
   public void releaseAfterCancelledJournal(WorldTaskContext context) {
      cancelJournalPreparation();
      WorldWriteCoordinator.releaseAfterUnusedJournal(
         context.server(), dimension, context.owner(), journal, journalFuture
      );
   }

   @Override
   public boolean hasWrites() {
      return !undo.isEmpty();
   }

   @Override
   public ArrayDeque<ReversibleBlockSnapshot> undoChanges() {
      return undo;
   }

   @Override
   public Map<BlockPos, ReversibleBlockSnapshot> afterChanges() {
      return after;
   }

   @Override
   public ResourceKey<Level> dimension() {
      return dimension;
   }

   @Override
   public Optional<WorldChangeBatch> preparedBatch() {
      return commitPreparation == null ? Optional.empty() : commitPreparation.batch();
   }

   private enum Phase {
      VALIDATE,
      JOURNAL,
      WRITE,
      FINALIZE,
      FINAL_JOURNAL
   }
}
