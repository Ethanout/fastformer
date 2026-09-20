package io.github.fastformer.fastplace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fastformer.fastplace.task.OperationTaskResult;
import io.github.fastformer.fastplace.task.PlacementTask;
import io.github.fastformer.fastplace.task.PlacementTaskPlan;
import io.github.fastformer.fastplace.task.WorldOperationTask;
import io.github.fastformer.fastplace.world.BlockEntitySnapshot;
import io.github.fastformer.fastplace.world.PersistentRecoveryJournal;
import io.github.fastformer.fastplace.world.ReversibleBlockSnapshot;
import io.github.fastformer.fastplace.world.WorldChangeBatch;
import io.github.fastformer.fastplace.world.WorldChangeTransaction;
import io.github.fastformer.fastplace.world.WorldHistoryManager;
import io.github.fastformer.fastplace.world.WorldHistoryManagerTestAccess;
import io.github.fastformer.fastplace.world.WorldOperationCommit;
import io.github.fastformer.fastplace.world.WorldRecoverySnapshot;
import io.github.fastformer.fastplace.world.WorldTaskBudget;
import io.github.fastformer.fastplace.world.WorldTaskContext;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The handover the scheduler performs while the global write gate is closed.
 *
 * <p>A queued task must leave its queue only after the recovery path accepted the
 * snapshot. A refused or failed transfer must keep the queue slot, and one failing owner
 * must not stop the handover of another owner.
 */
class TaskHandoverUnderClosedGateTest {
   @AfterEach
   void clearTasks() {
      OperationManager.clearServer();
      FastPlaceManager.clearServer();
      WorldHistoryManager.clearServer();
   }

   @Test
   void aTaskWithoutWritesLeavesTheQueueWithoutRunning() {
      UUID owner = UUID.randomUUID();
      CountingOperationTask task = new CountingOperationTask(false);
      OperationManager.addTaskForTest(owner, task);

      OperationManager.handOverBlockedTasks(null);

      assertEquals(1, task.transfers.get(), "the recovery path did not receive the task");
      assertEquals(0, task.ticks.get(), "the handover ran the task instead of transferring it");
      assertFalse(OperationManager.taskActive(owner), "the handed-over task stayed in the queue");
      assertFalse(WorldHistoryManager.busy(owner), "a task without writes started recovery work");
   }

   @Test
   void aWrittenTaskStartsRecoveryBeforeItLeavesTheQueue() {
      UUID owner = UUID.randomUUID();
      BlockPos pos = new BlockPos(1, 2, 3);
      CountingOperationTask task = new CountingOperationTask(false);
      task.transaction().recordBefore(snapshot(pos, "before"));
      task.transaction().recordAfter(pos, snapshot(pos, "after"));
      OperationManager.addTaskForTest(owner, task);

      OperationManager.handOverBlockedTasks(null);

      assertEquals(1, task.transfers.get(), "the recovery path did not receive the task");
      assertFalse(OperationManager.taskActive(owner), "the written task stayed in the queue");
      assertTrue(WorldHistoryManager.busy(owner), "the written task did not start recovery");
   }

   @Test
   void repeatedHandoverTransfersOneTaskOnce() {
      UUID owner = UUID.randomUUID();
      CountingOperationTask task = new CountingOperationTask(false);
      OperationManager.addTaskForTest(owner, task);

      OperationManager.handOverBlockedTasks(null);
      OperationManager.handOverBlockedTasks(null);

      assertEquals(1, task.transfers.get(), "the second tick transferred the task again");
      assertFalse(OperationManager.taskActive(owner));
   }

   @Test
   void aFailedHandoverKeepsTheQueueSlot() {
      UUID owner = UUID.randomUUID();
      CountingOperationTask task = new CountingOperationTask(true);
      OperationManager.addTaskForTest(owner, task);

      OperationManager.handOverBlockedTasks(null);
      OperationManager.handOverBlockedTasks(null);

      assertEquals(2, task.transfers.get(), "the handover was not retried");
      assertTrue(OperationManager.taskActive(owner), "a failed handover lost the task");
   }

   @Test
   void oneFailingOwnerDoesNotStopAnotherOwner() {
      UUID failing = UUID.randomUUID();
      UUID healthy = UUID.randomUUID();
      CountingOperationTask broken = new CountingOperationTask(true);
      CountingOperationTask handedOver = new CountingOperationTask(false);
      OperationManager.addTaskForTest(failing, broken);
      OperationManager.addTaskForTest(healthy, handedOver);

      OperationManager.handOverBlockedTasks(null);

      assertTrue(OperationManager.taskActive(failing), "the failing owner lost its task");
      assertFalse(OperationManager.taskActive(healthy), "the healthy owner kept its task");
      assertEquals(1, handedOver.transfers.get());
   }

   @Test
   void anAcceptanceFailureKeepsTheExtractedSnapshotForTheRetry() {
      UUID owner = UUID.randomUUID();
      BlockPos pos = new BlockPos(4, 5, 6);
      CountingOperationTask task = new CountingOperationTask(false, true);
      task.transaction().recordBefore(snapshot(pos, "before"));
      task.transaction().recordAfter(pos, snapshot(pos, "after"));
      OperationManager.addTaskForTest(owner, task);

      OperationManager.handOverBlockedTasks(null);

      assertEquals(1, task.transfers.get(), "the snapshot was not extracted");
      assertTrue(OperationManager.taskActive(owner), "a failed acceptance dropped the task");
      assertFalse(WorldHistoryManager.busy(owner), "a failed acceptance started recovery work");

      OperationManager.handOverBlockedTasks(null);

      assertEquals(1, task.transfers.get(), "the retry extracted a second, empty snapshot");
      assertFalse(OperationManager.taskActive(owner), "the retry left the task in the queue");
      assertTrue(WorldHistoryManager.busy(owner), "the retry did not start recovery");
      WorldChangeBatch recovery = WorldHistoryManagerTestAccess.activeRecoveryBatch(owner);
      assertNotNull(recovery, "the retry created no recovery batch");
      assertEquals(pos, recovery.position(0));
      assertEquals("after", marker(WorldHistoryManagerTestAccess.sourceSnapshots(recovery, true).getFirst()),
         "the retry lost the extracted after state");
      assertEquals("before", marker(WorldHistoryManagerTestAccess.targetSnapshots(recovery, true).getFirst()),
         "the retry lost the extracted before state");
   }

   @Test
   void unrelatedHistoryWorkDoesNotProveThatTheSnapshotWasAccepted() {
      UUID owner = UUID.randomUUID();
      WorldTaskContext context = new WorldTaskContext(null, owner);
      BlockPos unrelated = new BlockPos(9, 9, 9);
      ArrayDeque<ReversibleBlockSnapshot> unrelatedChanges = new ArrayDeque<>();
      unrelatedChanges.add(snapshot(unrelated, "unrelated-before"));
      assertTrue(WorldHistoryManager.startRollback(
         context, Level.OVERWORLD, unrelatedChanges,
         Map.of(unrelated, snapshot(unrelated, "unrelated-after")), null
      ), "the unrelated history work did not start");
      assertTrue(WorldHistoryManager.busy(owner), "the unrelated history work is not busy");

      BlockPos pos = new BlockPos(4, 5, 6);
      CountingOperationTask task = new CountingOperationTask(false, true);
      task.transaction().recordBefore(snapshot(pos, "before"));
      task.transaction().recordAfter(pos, snapshot(pos, "after"));
      OperationManager.addTaskForTest(owner, task);

      OperationManager.handOverBlockedTasks(null);

      assertEquals(1, task.transfers.get(), "the snapshot was not extracted");
      assertTrue(OperationManager.taskActive(owner),
         "unrelated history work was mistaken for an acceptance of this snapshot");

      OperationManager.handOverBlockedTasks(null);

      assertEquals(1, task.transfers.get(), "the retry extracted a second, empty snapshot");
      assertFalse(OperationManager.taskActive(owner), "the retry left the task in the queue");
   }

   @Test
   void aPlacementTaskLeavesTheQueue() {
      UUID owner = UUID.randomUUID();
      PlacementTask task = PlacementTask.ready(Set.of(BlockPos.ZERO), new PlacementTaskPlan(
         null, null, OperationConflictMode.REPLACE, PlacementUpdateMode.CLIENT_ONLY, 10, Level.OVERWORLD
      ));
      FastPlaceManager.addTaskForTest(owner, task);

      FastPlaceManager.handOverBlockedTasks(null);

      assertFalse(FastPlaceManager.taskActive(owner), "the handed-over placement task stayed in the queue");
   }

   private static ReversibleBlockSnapshot snapshot(BlockPos pos, String marker) {
      CompoundTag tag = new CompoundTag();
      tag.putString("marker", marker);
      return new ReversibleBlockSnapshot(pos, null, null, new BlockEntitySnapshot(tag));
   }

   private static String marker(ReversibleBlockSnapshot snapshot) {
      return snapshot.blockEntity().data().getString("marker");
   }

   /** A task that counts handovers and runs no world work. */
   private static final class CountingOperationTask implements WorldOperationTask {
      private final WorldChangeTransaction transaction = new WorldChangeTransaction();
      private final AtomicInteger transfers = new AtomicInteger();
      private final AtomicInteger ticks = new AtomicInteger();
      private final UUID operationId = UUID.randomUUID();
      private final boolean throwing;
      private final boolean journalFailsOnce;
      private final AtomicInteger journalReads = new AtomicInteger();

      private CountingOperationTask(boolean throwing) {
         this(throwing, false);
      }

      private CountingOperationTask(boolean throwing, boolean journalFailsOnce) {
         this.throwing = throwing;
         this.journalFailsOnce = journalFailsOnce;
      }

      @Override
      public WorldRecoverySnapshot stopAndTransferRecovery() {
         this.transfers.incrementAndGet();
         if (this.throwing) {
            throw new IllegalStateException("expected handover failure");
         }
         return this.transaction.transferRecoverySnapshot(CompletableFuture.completedFuture(null));
      }

      @Override
      public OperationTaskResult tick(WorldTaskContext context, ServerLevel level, WorldTaskBudget budget) {
         this.ticks.incrementAndGet();
         return OperationTaskResult.ACTIVE;
      }

      @Override
      public String phaseName() {
         return "TEST";
      }

      @Override
      public void cancelJournalPreparation() {
      }

      @Override
      public PersistentRecoveryJournal journal() {
         if (this.journalFailsOnce && this.journalReads.incrementAndGet() == 1) {
            // The snapshot is already extracted at this point, because the caller reads the
            // journal while it builds the acceptance call.
            throw new IllegalStateException("expected acceptance failure");
         }
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
         return null;
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
   }
}
