package io.github.fastformer.fastplace;

import io.github.fastformer.fastplace.selection.OperationStackRegion;

import io.github.fastformer.fastplace.selection.OperationMode;

import io.github.fastformer.fastplace.selection.OperationSelectionMode;
import io.github.fastformer.fastplace.selection.OperationSelectionVolume;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fastformer.fastplace.task.ClientWorkspacePlacementTask;
import io.github.fastformer.fastplace.task.OperationTaskResult;
import io.github.fastformer.fastplace.task.PlacementTask;
import io.github.fastformer.fastplace.task.PlacementTaskPlan;
import io.github.fastformer.fastplace.task.SelectionOperationTask;
import io.github.fastformer.fastplace.task.TaskCancellationResult;
import io.github.fastformer.fastplace.task.WorldOperationTask;
import io.github.fastformer.fastplace.world.BlockEntitySnapshot;
import io.github.fastformer.fastplace.world.ReversibleBlockSnapshot;
import io.github.fastformer.fastplace.world.WorldChangeBatch;
import io.github.fastformer.fastplace.world.WorldChangeTransaction;
import io.github.fastformer.fastplace.world.WorldHistoryManager;
import io.github.fastformer.fastplace.world.WorldHistoryManagerTestAccess;
import io.github.fastformer.fastplace.world.WorldOperationCommit;
import io.github.fastformer.fastplace.world.WorldRecoverySnapshot;
import io.github.fastformer.fastplace.world.WorldTaskBudget;
import io.github.fastformer.fastplace.world.WorldTaskContext;
import io.github.fastformer.fastplace.world.WorldJournalPreparation;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TaskRecoveryHandoffTest {
   @Test
   void cancelRemovesDeferredGenerationBeforeItCanStart() {
      UUID owner = UUID.randomUUID();
      AtomicInteger starts = new AtomicInteger();
      PlacementTask task = deferredGeneration(starts);
      FastPlaceManager.addTaskForTest(owner, task);

      assertEquals(TaskCancellationResult.CANCELLED_BEFORE_WRITE,
         FastPlaceManager.cancelTask(new WorldTaskContext(null, owner)));
      FastPlaceManager.tickWorld(null);

      assertFalse(task.waitingForGenerationMemory());
      assertFalse(FastPlaceManager.taskActive(owner));
      assertFalse(WorldHistoryManager.busy(owner));
      assertEquals(0, starts.get());
   }

   @Test
   void serverStopDiscardsDeferredGenerationInputs() {
      UUID owner = UUID.randomUUID();
      AtomicInteger starts = new AtomicInteger();
      PlacementTask task = deferredGeneration(starts);
      FastPlaceManager.addTaskForTest(owner, task);

      FastPlaceManager.clearServer();
      FastPlaceManager.tickWorld(null);

      assertFalse(task.waitingForGenerationMemory());
      assertFalse(FastPlaceManager.taskActive(owner));
      assertEquals(0, starts.get());
   }

   private static PlacementTask deferredGeneration(AtomicInteger starts) {
      return PlacementTask.waitingForGeneration(() -> {
         starts.incrementAndGet();
         return io.github.fastformer.fastplace.geometry.generation.BlockGenerationResult.fromLegacy(Set.of(BlockPos.ZERO));
      }, null, new PlacementTaskPlan(null, null, OperationConflictMode.REPLACE,
         PlacementUpdateMode.CLIENT_ONLY, 10, Level.OVERWORLD), 1L, 0L);
   }

   @AfterEach
   void clearTasks() {
      FastPlaceManager.clearServer();
      WorldHistoryManager.clearServer();
   }

   @Test
   void ordinaryPlacementCancellationUsesOneShotHandoff() {
      UUID owner = UUID.randomUUID();
      PlacementTask task = PlacementTask.ready(
         Set.of(BlockPos.ZERO),
         new PlacementTaskPlan(
            null, null, OperationConflictMode.REPLACE, PlacementUpdateMode.CLIENT_ONLY, 10, Level.OVERWORLD
         )
      );
      FastPlaceManager.addTaskForTest(owner, task);

      assertEquals(
         TaskCancellationResult.CANCELLED_BEFORE_WRITE,
         FastPlaceManager.cancelTask(new WorldTaskContext(null, owner))
      );
      assertEquals(
         TaskCancellationResult.NOT_ACTIVE,
         FastPlaceManager.cancelTask(new WorldTaskContext(null, owner))
      );
   }

   @Test
   void workspaceCancellationTransfersRecoveryOnce() {
      ClientWorkspacePlacementTask task = new ClientWorkspacePlacementTask(
         UUID.randomUUID(), new OperationWorkspacePlan(List.of()), PlacementUpdateMode.CLIENT_ONLY, 10, Level.OVERWORLD
      );

      assertOperationTaskTransfersRecoveryOnce(task);
   }

   @Test
   void allTaskTypesWaitForPendingAppendBeforeRecoveryReadsSnapshots() throws Exception {
      PlacementTask placement = PlacementTask.ready(Set.of(BlockPos.ZERO), new PlacementTaskPlan(
         null, null, OperationConflictMode.REPLACE, PlacementUpdateMode.CLIENT_ONLY, 10, Level.OVERWORLD
      ));
      ClientWorkspacePlacementTask workspace = new ClientWorkspacePlacementTask(
         UUID.randomUUID(), new OperationWorkspacePlan(List.of()), PlacementUpdateMode.CLIENT_ONLY, 10, Level.OVERWORLD
      );
      SelectionOperationTask selection = new SelectionOperationTask(
         OperationSelectionVolume.cuboid(BlockPos.ZERO, BlockPos.ZERO, BlockPos.ZERO, BlockPos.ZERO),
         OperationMode.MOVE, OperationConflictMode.REPLACE, true, BlockPos.ZERO.above(),
         OperationStackRegion.origin(), PlacementUpdateMode.CLIENT_ONLY, 10, Level.OVERWORLD
      );
      for (Object task : List.of(placement, workspace, selection)) {
         var preparationField = task.getClass().getDeclaredField("journalPreparation");
         preparationField.setAccessible(true);
         WorldJournalPreparation preparation = (WorldJournalPreparation)preparationField.get(task);
         CompletableFuture<Boolean> append = new CompletableFuture<>();
         var appendField = WorldJournalPreparation.class.getDeclaredField("appendFuture");
         appendField.setAccessible(true);
         appendField.set(preparation, append);
         var transactionField = task.getClass().getDeclaredField("transaction");
         transactionField.setAccessible(true);
         WorldChangeTransaction transaction = (WorldChangeTransaction)transactionField.get(task);
         transaction.recordBefore(snapshot(BlockPos.ZERO, "before"));
         transaction.recordAfter(BlockPos.ZERO, snapshot(BlockPos.ZERO, "after"));

         WorldRecoverySnapshot recovery = task instanceof PlacementTask ordinary
            ? ordinary.stopAndTransferRecovery() : ((WorldOperationTask)task).stopAndTransferRecovery();
         assertFalse(recovery.ready(), task.getClass().getSimpleName());
         assertTrue(recovery.hasWrites());
         assertFalse(transaction.hasWrites());
         append.complete(false);
         assertTrue(recovery.ready());
         assertEquals("before", marker(recovery.before().getFirst()));
         assertEquals("after", marker(recovery.after().get(BlockPos.ZERO)));
      }
   }

   @Test
   void placementSnapshotFailureAfterAnEarlierBatchStartsRecovery() throws Exception {
      assertPlacementFailureStartsRecovery("failed");
   }

   @Test
   void placementMemoryRejectionAfterAnEarlierBatchStartsRecovery() throws Exception {
      assertPlacementFailureStartsRecovery("memoryUnsafe");
   }

   private static void assertPlacementFailureStartsRecovery(String failureField) throws Exception {
      UUID owner = UUID.randomUUID();
      BlockPos pos = new BlockPos(3, 4, 5);
      PlacementTask task = PlacementTask.ready(
         Set.of(pos),
         new PlacementTaskPlan(
            null, null, OperationConflictMode.REPLACE, PlacementUpdateMode.CLIENT_ONLY, 10, Level.OVERWORLD
         )
      );
      assertTrue(task.prepare());
      var transactionField = PlacementTask.class.getDeclaredField("transaction");
      transactionField.setAccessible(true);
      WorldChangeTransaction transaction = (WorldChangeTransaction)transactionField.get(task);
      transaction.recordBefore(snapshot(pos, "before"));
      transaction.recordAfter(pos, snapshot(pos, "after"));
      var terminalField = PlacementTask.class.getDeclaredField(failureField);
      terminalField.setAccessible(true);
      terminalField.setBoolean(task, true);
      FastPlaceManager.addTaskForTest(owner, task);

      FastPlaceManager.tickWorld(null);

      assertTrue(task.recoveryTaskCreated());
      assertTrue(WorldHistoryManager.busy(owner));
      assertEquals(TaskCancellationResult.NOT_ACTIVE, FastPlaceManager.cancelTask(new WorldTaskContext(null, owner)));
      WorldHistoryManager.tickWorld(null);
      WorldChangeBatch recovery = WorldHistoryManagerTestAccess.activeRecoveryBatch(owner);
      assertNotNull(recovery);
      assertEquals("before", marker(WorldHistoryManagerTestAccess.targetSnapshots(recovery, true).getFirst()));
   }

   @Test
   void selectionCancellationTransfersRecoveryOnce() {
      OperationSelectionVolume selection = new OperationSelectionVolume(
         OperationSelectionMode.CUBOID,
         new AABB(0, 0, 0, 1, 1, 1),
         null,
         List.of(),
         0,
         BlockPos.ZERO,
         BlockPos.ZERO
      );
      SelectionOperationTask task = new SelectionOperationTask(
         selection,
         OperationMode.MOVE,
         OperationConflictMode.REPLACE,
         false,
         BlockPos.ZERO,
         OperationStackRegion.origin(),
         PlacementUpdateMode.CLIENT_ONLY,
         10,
         Level.OVERWORLD
      );

      assertOperationTaskTransfersRecoveryOnce(task);
   }

   @Test
   void managerCancellationWaitsForCompressionThenStartsRecovery() throws Exception {
      UUID owner = UUID.randomUUID();
      BlockPos pos = new BlockPos(6, 7, 8);
      CountDownLatch compressionStarted = new CountDownLatch(1);
      CompletableFuture<Void> finishCompression = new CompletableFuture<>();
      ArrayDeque<ReversibleBlockSnapshot> before = new BlockingCompressionDeque<>(
         List.of(snapshot(pos, "before")), compressionStarted, finishCompression
      );
      Map<BlockPos, ReversibleBlockSnapshot> after = Map.of(pos, snapshot(pos, "after"));
      CancellableCompressionTask task = new CancellableCompressionTask(before, after);
      OperationManager.addTaskForTest(owner, task);

      assertTrue(compressionStarted.await(5, TimeUnit.SECONDS));
      try {
         assertEquals(
            TaskCancellationResult.ROLLBACK_STARTED,
            OperationManager.cancelTask(new WorldTaskContext(null, owner))
         );
         assertEquals(1, task.memoryReleaseCount.get());
         assertEquals(
            TaskCancellationResult.NOT_ACTIVE,
            OperationManager.cancelTask(new WorldTaskContext(null, owner))
         );
         assertEquals(1, task.memoryReleaseCount.get());
         WorldHistoryManager.tickWorld(null);
         assertEquals(1, WorldHistoryManagerTestAccess.recoveryCaptureCount(owner));
         assertNull(WorldHistoryManagerTestAccess.activeRecoveryBatch(owner));
      } finally {
         finishCompression.complete(null);
      }

      task.readyForRecovery.join();
      assertTrue(task.commit.batch().isEmpty());
      WorldHistoryManager.tickWorld(null);

      assertEquals(0, WorldHistoryManagerTestAccess.recoveryCaptureCount(owner));
      WorldChangeBatch recovery = WorldHistoryManagerTestAccess.activeRecoveryBatch(owner);
      assertNotNull(recovery);
      assertEquals(pos, recovery.position(0));
      assertEquals("after", marker(WorldHistoryManagerTestAccess.sourceSnapshots(recovery, true).getFirst()));
      assertEquals("before", marker(WorldHistoryManagerTestAccess.targetSnapshots(recovery, true).getFirst()));
   }

   private static void assertOperationTaskTransfersRecoveryOnce(WorldOperationTask task) {
      UUID owner = UUID.randomUUID();
      BlockPos pos = new BlockPos(3, 4, 5);
      task.transaction().recordBefore(snapshot(pos, "before"));
      task.transaction().recordAfter(pos, snapshot(pos, "after"));
      OperationManager.addTaskForTest(owner, task);

      assertEquals(
         TaskCancellationResult.ROLLBACK_STARTED,
         OperationManager.cancelTask(new WorldTaskContext(null, owner))
      );
      assertTrue(WorldHistoryManager.busy(owner));
      assertEquals(
         TaskCancellationResult.NOT_ACTIVE,
         OperationManager.cancelTask(new WorldTaskContext(null, owner))
      );
   }

   private static ReversibleBlockSnapshot snapshot(BlockPos pos, String marker) {
      CompoundTag tag = new CompoundTag();
      tag.putString("marker", marker);
      return new ReversibleBlockSnapshot(pos, null, null, new BlockEntitySnapshot(tag));
   }

   private static String marker(ReversibleBlockSnapshot snapshot) {
      return snapshot.blockEntity().data().getString("marker");
   }

   private static final class CancellableCompressionTask implements WorldOperationTask {
      private final ArrayDeque<ReversibleBlockSnapshot> before;
      private final Map<BlockPos, ReversibleBlockSnapshot> after;
      private final WorldChangeTransaction transaction = new WorldChangeTransaction();
      private final WorldOperationCommit commit;
      private final AtomicInteger memoryReleaseCount = new AtomicInteger();
      private final UUID operationId = UUID.randomUUID();
      private CompletableFuture<Void> readyForRecovery;

      private CancellableCompressionTask(
         ArrayDeque<ReversibleBlockSnapshot> before,
         Map<BlockPos, ReversibleBlockSnapshot> after
      ) {
         this.before = before;
         this.after = after;
         this.commit = WorldOperationCommit.begin(Level.OVERWORLD, before, after, null);
      }

      @Override
      public WorldRecoverySnapshot stopAndTransferRecovery() {
         this.readyForRecovery = this.commit.stopForRecovery();
         return new WorldRecoverySnapshot(this.before, this.after, this.readyForRecovery);
      }

      @Override
      public OperationTaskResult tick(WorldTaskContext context, ServerLevel level, WorldTaskBudget budget) {
         return OperationTaskResult.ACTIVE;
      }

      @Override
      public String phaseName() {
         return "COMMIT";
      }

      @Override
      public void cancelJournalPreparation() {
      }

      @Override
      public io.github.fastformer.fastplace.world.PersistentRecoveryJournal journal() {
         return null;
      }

      @Override
      public boolean acquireLease(WorldTaskContext context) {
         return true;
      }

      @Override
      public void releaseLease(WorldTaskContext context) {
      }

      @Override
      public void releaseAfterCancelledJournal(WorldTaskContext context) {
      }

      @Override
      public WorldChangeTransaction transaction() {
         return this.transaction;
      }

      @Override
      public WorldOperationCommit operationCommit() {
         return this.commit;
      }

      @Override
      public ResourceKey<Level> dimension() {
         return Level.OVERWORLD;
      }

      @Override
      public UUID operationId() {
         return this.operationId;
      }

      @Override
      public String metricsSummary() {
         return "test";
      }

      @Override
      public void markWorldUnloaded() {
      }

      @Override
      public void markComplete() {
      }

      @Override
      public void releaseMemoryReservation() {
         this.memoryReleaseCount.incrementAndGet();
      }
   }

   private static final class BlockingCompressionDeque<E> extends ArrayDeque<E> {
      private final CountDownLatch compressionStarted;
      private final CompletableFuture<Void> finishCompression;

      private BlockingCompressionDeque(
         Collection<? extends E> values,
         CountDownLatch compressionStarted,
         CompletableFuture<Void> finishCompression
      ) {
         super(values);
         this.compressionStarted = compressionStarted;
         this.finishCompression = finishCompression;
      }

      @Override
      public Iterator<E> descendingIterator() {
         this.compressionStarted.countDown();
         this.finishCompression.join();
         return super.descendingIterator();
      }
   }
}
