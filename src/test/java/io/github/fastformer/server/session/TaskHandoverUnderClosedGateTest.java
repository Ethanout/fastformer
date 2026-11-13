package io.github.fastformer.server.session;

import io.github.fastformer.server.history.rollback.RollbackRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fastformer.server.history.entry.WorldChangeBatch;
import io.github.fastformer.server.history.HistoryService;
import io.github.fastformer.server.history.HistoryTestAccess;
import io.github.fastformer.fastplace.placement.PlacementUpdateMode;
import io.github.fastformer.server.recovery.PersistentRecoveryJournal;
import io.github.fastformer.server.recovery.WorldRecoverySnapshot;
import io.github.fastformer.server.task.TaskStep;
import io.github.fastformer.server.task.block.PlacementTask;
import io.github.fastformer.server.task.block.PlacementTaskPlan;
import io.github.fastformer.server.task.WorldTask;
import io.github.fastformer.fastplace.world.WorldChangeTransaction;
import io.github.fastformer.server.task.WorldTaskContext;
import io.github.fastformer.fastplace.world.snapshot.BlockEntitySnapshot;
import io.github.fastformer.fastplace.world.snapshot.ReversibleBlockSnapshot;
import io.github.fastformer.workspace.submission.OperationConflictMode;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The handover the scheduler performs while its server's write gate is closed.
 *
 * <p>A queued task must leave its queue only after the recovery path accepted the
 * snapshot. A refused or failed transfer must keep the queue slot, and one failing owner
 * must not stop the handover of another owner.
 */
class TaskHandoverUnderClosedGateTest {
   private final SessionSystem runtime = new SessionSystem();
   private final QuickShapeSystem quickShape = runtime.quickShape;
   private final OperationSystem operation = runtime.operation;
   private static final HistoryService history = new HistoryService(
      io.github.fastformer.core.gate.Gate.require("test", ignored -> true),
      io.github.fastformer.core.gate.Gate.require("test", ignored -> true), new io.github.fastformer.server.history.NoDiskHistoryRepository());
   @AfterEach
   void clearTasks() {
      runtime.close();
      history.clear();
   }

   @Test
   void aTaskWithoutWritesLeavesTheQueueWithoutRunning() {
      UUID owner = UUID.randomUUID();
      CountingOperationTask task = new CountingOperationTask(false);
      operation.tasks.put(owner, task);

      operation.handOverBlockedTasks(null, history);

      assertEquals(1, task.transfers.get(), "the recovery path did not receive the task");
      assertEquals(0, task.ticks.get(), "the handover ran the task instead of transferring it");
      assertFalse(operation.taskActive(owner), "the handed-over task stayed in the queue");
      assertFalse(history.busy(owner), "a task without writes started recovery work");
   }

   @Test
   void aWrittenTaskStartsRecoveryBeforeItLeavesTheQueue() {
      UUID owner = UUID.randomUUID();
      BlockPos pos = new BlockPos(1, 2, 3);
      CountingOperationTask task = new CountingOperationTask(false);
      task.transaction().recordBefore(snapshot(pos, "before"));
      task.transaction().recordAfter(pos, snapshot(pos, "after"));
      operation.tasks.put(owner, task);

      operation.handOverBlockedTasks(null, history);

      assertEquals(1, task.transfers.get(), "the recovery path did not receive the task");
      assertFalse(operation.taskActive(owner), "the written task stayed in the queue");
      assertTrue(history.busy(owner), "the written task did not start recovery");
   }

   @Test
   void repeatedHandoverTransfersOneTaskOnce() {
      UUID owner = UUID.randomUUID();
      CountingOperationTask task = new CountingOperationTask(false);
      operation.tasks.put(owner, task);

      operation.handOverBlockedTasks(null, history);
      operation.handOverBlockedTasks(null, history);

      assertEquals(1, task.transfers.get(), "the second tick transferred the task again");
      assertFalse(operation.taskActive(owner));
   }

   @Test
   void aFailedHandoverKeepsTheQueueSlot() {
      UUID owner = UUID.randomUUID();
      CountingOperationTask task = new CountingOperationTask(true);
      operation.tasks.put(owner, task);

      operation.handOverBlockedTasks(null, history);
      operation.tickWorld(null, history);

      assertEquals(2, task.transfers.get(), "the open-gate tick did not retry the failed extraction");
      assertEquals(0, task.ticks.get(), "the task resumed after cancellation failed");
      assertTrue(operation.taskActive(owner), "a failed handover lost the task");
   }

   @Test
   void oneFailingOwnerDoesNotStopAnotherOwner() {
      UUID failing = UUID.randomUUID();
      UUID healthy = UUID.randomUUID();
      CountingOperationTask broken = new CountingOperationTask(true);
      CountingOperationTask handedOver = new CountingOperationTask(false);
      operation.tasks.put(failing, broken);
      operation.tasks.put(healthy, handedOver);

      operation.handOverBlockedTasks(null, history);

      assertTrue(operation.taskActive(failing), "the failing owner lost its task");
      assertFalse(operation.taskActive(healthy), "the healthy owner kept its task");
      assertEquals(1, handedOver.transfers.get());
   }

   @Test
   void anAcceptanceFailureKeepsTheExtractedSnapshotForTheRetry() {
      UUID owner = UUID.randomUUID();
      BlockPos pos = new BlockPos(4, 5, 6);
      CountingOperationTask task = new CountingOperationTask(false, true);
      task.transaction().recordBefore(snapshot(pos, "before"));
      task.transaction().recordAfter(pos, snapshot(pos, "after"));
      operation.tasks.put(owner, task);

      operation.handOverBlockedTasks(null, history);

      assertEquals(1, task.transfers.get(), "the snapshot was not extracted");
      assertTrue(operation.taskActive(owner), "a failed acceptance dropped the task");
      assertFalse(history.busy(owner), "a failed acceptance started recovery work");

      operation.tickWorld(null, history);

      assertEquals(1, task.transfers.get(), "the open-gate retry extracted a second, empty snapshot");
      assertFalse(operation.taskActive(owner), "the retry left the task in the queue");
      assertTrue(history.busy(owner), "the retry did not start recovery");
      WorldChangeBatch recovery = HistoryTestAccess.activeRecoveryBatch(history, owner);
      assertNotNull(recovery, "the retry created no recovery batch");
      assertEquals(pos, recovery.position(0));
      assertEquals("after", marker(HistoryTestAccess.sourceSnapshots(recovery, true).getFirst()),
         "the retry lost the extracted after state");
      assertEquals("before", marker(HistoryTestAccess.targetSnapshots(recovery, true).getFirst()),
         "the retry lost the extracted before state");
   }

   @Test
   void unrelatedHistoryWorkDoesNotProveThatTheSnapshotWasAccepted() {
      UUID owner = UUID.randomUUID();
      WorldTaskContext context = new WorldTaskContext(null, owner);
      BlockPos unrelated = new BlockPos(9, 9, 9);
      ArrayDeque<ReversibleBlockSnapshot> unrelatedChanges = new ArrayDeque<>();
      unrelatedChanges.add(snapshot(unrelated, "unrelated-before"));
      assertTrue(history.rollback(RollbackRequest.ready(
         context, Level.OVERWORLD, unrelatedChanges,
         Map.of(unrelated, snapshot(unrelated, "unrelated-after")), null
      )), "the unrelated history work did not start");
      assertTrue(history.busy(owner), "the unrelated history work is not busy");

      BlockPos pos = new BlockPos(4, 5, 6);
      CountingOperationTask task = new CountingOperationTask(false, true);
      task.transaction().recordBefore(snapshot(pos, "before"));
      task.transaction().recordAfter(pos, snapshot(pos, "after"));
      operation.tasks.put(owner, task);

      operation.handOverBlockedTasks(null, history);

      assertEquals(1, task.transfers.get(), "the snapshot was not extracted");
      assertTrue(operation.taskActive(owner),
         "unrelated history work was mistaken for an acceptance of this snapshot");

      operation.handOverBlockedTasks(null, history);

      assertEquals(1, task.transfers.get(), "the retry extracted a second, empty snapshot");
      assertFalse(operation.taskActive(owner), "the retry left the task in the queue");
   }

   @Test
   void aPlacementTaskLeavesTheQueue() {
      UUID owner = UUID.randomUUID();
      PlacementTask task = PlacementTask.ready(Set.of(BlockPos.ZERO), new PlacementTaskPlan(
         null, null, OperationConflictMode.REPLACE, PlacementUpdateMode.CLIENT_ONLY, 10, Level.OVERWORLD
      ));
      runtime.placementTasks().admit(owner, task);

      runtime.placementTasks().handOverBlockedTasks(null, history);

      assertFalse(runtime.placementTasks().active(owner), "the handed-over placement task stayed in the queue");
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
   private static final class CountingOperationTask implements WorldTask {
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
      public TaskStep tick(WorldTaskContext context) {
         this.ticks.incrementAndGet();
         return TaskStep.ACTIVE;
      }

      @Override
      public String phaseName() {
         return "TEST";
      }

      @Override
      public void cancel(io.github.fastformer.server.task.CancelReason reason) {
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
      public java.util.Optional<WorldChangeBatch> preparedBatch() {
         return java.util.Optional.empty();
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
      public void releaseCommittedTransactionState() {
      }

      @Override
      public void markComplete() {
      }

      @Override public void releaseMemoryReservation() {}
   }
}
