package io.github.fastformer.server.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fastformer.server.history.entry.WorldChangeBatch;
import io.github.fastformer.server.history.HistoryService;
import io.github.fastformer.server.history.HistoryTestAccess;
import io.github.fastformer.common.placement.PlacementUpdateMode;
import io.github.fastformer.server.recovery.WorldJournalPreparation;
import io.github.fastformer.server.recovery.WorldRecoverySnapshot;
import io.github.fastformer.common.selection.OperationMode;
import io.github.fastformer.common.selection.OperationSelectionMode;
import io.github.fastformer.common.selection.OperationSelectionVolume;
import io.github.fastformer.common.selection.OperationStackRegion;
import io.github.fastformer.server.task.block.SubmittedWorkspaceTask;
import io.github.fastformer.server.task.TaskStep;
import io.github.fastformer.server.task.block.PlacementTask;
import io.github.fastformer.server.task.block.PlacementTaskPlan;
import io.github.fastformer.server.task.block.SelectionOperationTask;
import io.github.fastformer.server.task.TaskCancellationResult;
import io.github.fastformer.server.task.WorldTask;
import io.github.fastformer.server.task.block.WorldTaskFixtures;
import io.github.fastformer.server.task.runtime.LeasedJournaledTask;
import io.github.fastformer.server.recovery.JournalPreparation;
import io.github.fastformer.server.recovery.RecoveryJournalTestAccess;
import io.github.fastformer.server.worldwrite.transaction.WorldChangeTransaction;
import io.github.fastformer.server.worldwrite.transaction.WorldOperationCommit;
import io.github.fastformer.server.task.WorldTaskContext;
import io.github.fastformer.server.worldwrite.snapshot.BlockEntitySnapshot;
import io.github.fastformer.server.worldwrite.snapshot.ReversibleBlockSnapshot;
import io.github.fastformer.common.workspace.model.OperationConflictMode;
import io.github.fastformer.common.workspace.submission.OperationWorkspacePlan;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TaskRecoveryHandoffTest {
   @TempDir Path directory;
   private final SessionSystem runtime = new SessionSystem();
   private final QuickShapeSystem quickShape = runtime.quickShape;
   private final OperationSystem operation = runtime.operation;
   private static final HistoryService history = new HistoryService(
      io.github.fastformer.core.gate.Gate.require("test", ignored -> true),
      io.github.fastformer.core.gate.Gate.require("test", ignored -> true), new io.github.fastformer.server.history.NoDiskHistoryRepository());
   @Test
   void cancelRemovesDeferredGenerationBeforeItCanStart() {
      UUID owner = UUID.randomUUID();
      AtomicInteger starts = new AtomicInteger();
      PlacementTask task = deferredGeneration(starts);
      runtime.placementTasks().admit(owner, task);

      assertEquals(TaskCancellationResult.CANCELLED_BEFORE_WRITE,
         runtime.placementTasks().cancel(new WorldTaskContext(null, owner), history));
      runtime.placementTasks().tickWorld(null, history);

      assertFalse(task.waitingForGenerationMemory());
      assertFalse(runtime.placementTasks().active(owner));
      assertFalse(history.busy(owner));
      assertEquals(0, starts.get());
   }

   @Test
   void serverStopDiscardsDeferredGenerationInputs() {
      UUID owner = UUID.randomUUID();
      AtomicInteger starts = new AtomicInteger();
      PlacementTask task = deferredGeneration(starts);
      runtime.placementTasks().admit(owner, task);

      runtime.close();
      runtime.placementTasks().tickWorld(null, history);

      assertFalse(task.waitingForGenerationMemory());
      assertFalse(runtime.placementTasks().active(owner));
      assertEquals(0, starts.get());
   }

   private static PlacementTask deferredGeneration(AtomicInteger starts) {
      return PlacementTask.waitingForGeneration(() -> {
         starts.incrementAndGet();
         return io.github.fastformer.common.geometry.generation.source.BlockGenerationResult.fromLegacy(Set.of(BlockPos.ZERO));
      }, null, new PlacementTaskPlan(null, null, OperationConflictMode.REPLACE,
         PlacementUpdateMode.CLIENT_ONLY, 10, Level.OVERWORLD), 1L, 0L);
   }

   @AfterEach
   void clearTasks() {
      runtime.close();
      history.clear();
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
      runtime.placementTasks().admit(owner, task);

      assertEquals(
         TaskCancellationResult.CANCELLED_BEFORE_WRITE,
         runtime.placementTasks().cancel(new WorldTaskContext(null, owner), history)
      );
      assertEquals(
         TaskCancellationResult.NOT_ACTIVE,
         runtime.placementTasks().cancel(new WorldTaskContext(null, owner), history)
      );
   }

   @Test
   void workspaceCancellationTransfersRecoveryOnce() {
      SubmittedWorkspaceTask task = new SubmittedWorkspaceTask(
         UUID.randomUUID(), new OperationWorkspacePlan(List.of()), PlacementUpdateMode.CLIENT_ONLY, 10, Level.OVERWORLD
      );

      assertOperationTaskTransfersRecoveryOnce(task);
   }

   @Test
   void allTaskTypesWaitForPendingAppendBeforeRecoveryReadsSnapshots() throws Exception {
      for (int kind = 0; kind < 3; kind++) {
         var io = new ArrayDeque<Runnable>();
         var preparation = new WorldJournalPreparation(io::addLast);
         var journal = RecoveryJournalTestAccess.open(Files.createDirectory(directory.resolve("journal-" + kind)));
         preparation.poll(() -> Optional.of(journal));
         io.removeFirst().run();
         assertEquals(JournalPreparation.READY, preparation.poll(Optional::empty));
         assertEquals(JournalPreparation.PENDING, preparation.pollAppend(() -> true));
         LeasedJournaledTask task = switch (kind) {
            case 0 -> WorldTaskFixtures.placement(Set.of(BlockPos.ZERO), new PlacementTaskPlan(
               null, null, OperationConflictMode.REPLACE, PlacementUpdateMode.CLIENT_ONLY, 10, Level.OVERWORLD), preparation);
            case 1 -> WorldTaskFixtures.workspace(UUID.randomUUID(), new OperationWorkspacePlan(List.of()),
               PlacementUpdateMode.CLIENT_ONLY, 10, Level.OVERWORLD, preparation);
            default -> WorldTaskFixtures.selection(
               OperationSelectionVolume.cuboid(BlockPos.ZERO, BlockPos.ZERO, BlockPos.ZERO, BlockPos.ZERO),
               OperationMode.MOVE, OperationConflictMode.REPLACE, true, BlockPos.ZERO.above(),
               OperationStackRegion.origin(), PlacementUpdateMode.CLIENT_ONLY, 10, Level.OVERWORLD, preparation);
         };
         WorldChangeTransaction transaction = task.transaction();
         transaction.recordBefore(snapshot(BlockPos.ZERO, "before"));
         transaction.recordAfter(BlockPos.ZERO, snapshot(BlockPos.ZERO, "after"));

         WorldRecoverySnapshot recovery = task.stopAndTransferRecovery();
         assertFalse(recovery.ready(), task.getClass().getSimpleName());
         assertTrue(recovery.hasWrites());
         assertFalse(transaction.hasWrites());
         io.removeFirst().run();
         assertTrue(recovery.ready());
         assertEquals("before", marker(recovery.before().getFirst()));
         assertEquals("after", marker(recovery.after().get(BlockPos.ZERO)));
      }
   }

   @Test
   void placementSnapshotFailureAfterAnEarlierBatchStartsRecovery() throws Exception {
      assertPlacementFailureStartsRecovery(false);
   }

   @Test
   void placementMemoryRejectionAfterAnEarlierBatchStartsRecovery() throws Exception {
      assertPlacementFailureStartsRecovery(true);
   }

   private void assertPlacementFailureStartsRecovery(boolean rejectMemory) throws Exception {
      UUID owner = UUID.randomUUID();
      BlockPos pos = new BlockPos(3, 4, 5);
      var reject = new java.util.concurrent.atomic.AtomicBoolean();
      PlacementTask task = WorldTaskFixtures.placement(
         Set.of(pos),
         new PlacementTaskPlan(
            null, null, OperationConflictMode.REPLACE, PlacementUpdateMode.CLIENT_ONLY, 10, Level.OVERWORLD
         ), new WorldJournalPreparation(), (level, position) -> Optional.empty(),
         estimate -> reject.get() ? new io.github.fastformer.common.world.memory.MemoryAdmission(
            io.github.fastformer.common.world.memory.MemoryAdmissionStatus.HARD_REJECTED,
            estimate.requestedBytes(), estimate.usableBytes()) : estimate
      );
      assertTrue(task.prepare());
      WorldChangeTransaction transaction = task.transaction();
      transaction.recordBefore(snapshot(pos, "before"));
      transaction.recordAfter(pos, snapshot(pos, "after"));
      reject.set(rejectMemory);
      assertTrue(task.validateSnapshots(null, io.github.fastformer.server.task.WorldTaskBudget.forServerTick()));
      if (rejectMemory) assertTrue(task.memoryUnsafe());
      else assertTrue(task.failed());
      runtime.placementTasks().admit(owner, task);

      runtime.placementTasks().tickWorld(null, history);

      assertTrue(task.recoveryTaskCreated());
      assertTrue(history.busy(owner));
      assertEquals(TaskCancellationResult.NOT_ACTIVE, runtime.placementTasks().cancel(new WorldTaskContext(null, owner), history));
      history.tickWorld(null);
      WorldChangeBatch recovery = HistoryTestAccess.activeRecoveryBatch(history, owner);
      assertNotNull(recovery);
      assertEquals("before", marker(HistoryTestAccess.targetSnapshots(recovery, true).getFirst()));
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
      runtime.operationTasks().admit(owner, task);

      assertTrue(compressionStarted.await(5, TimeUnit.SECONDS));
      try {
         assertEquals(
            TaskCancellationResult.ROLLBACK_STARTED,
            runtime.operationTasks().cancel(new WorldTaskContext(null, owner), history)
         );
         assertEquals(1, task.memoryReleaseCount.get());
         assertEquals(
            TaskCancellationResult.NOT_ACTIVE,
            runtime.operationTasks().cancel(new WorldTaskContext(null, owner), history)
         );
         assertEquals(1, task.memoryReleaseCount.get());
         history.tickWorld(null);
         assertEquals(1, HistoryTestAccess.recoveryCaptureCount(history, owner));
         assertNull(HistoryTestAccess.activeRecoveryBatch(history, owner));
      } finally {
         finishCompression.complete(null);
      }

      task.readyForRecovery.join();
      assertTrue(task.commit.batch().isEmpty());
      history.tickWorld(null);

      assertEquals(0, HistoryTestAccess.recoveryCaptureCount(history, owner));
      WorldChangeBatch recovery = HistoryTestAccess.activeRecoveryBatch(history, owner);
      assertNotNull(recovery);
      assertEquals(pos, recovery.position(0));
      assertEquals("after", marker(HistoryTestAccess.sourceSnapshots(recovery, true).getFirst()));
      assertEquals("before", marker(HistoryTestAccess.targetSnapshots(recovery, true).getFirst()));
   }

   private void assertOperationTaskTransfersRecoveryOnce(WorldTask task) {
      UUID owner = UUID.randomUUID();
      BlockPos pos = new BlockPos(3, 4, 5);
      task.transaction().recordBefore(snapshot(pos, "before"));
      task.transaction().recordAfter(pos, snapshot(pos, "after"));

      var reported = new java.util.ArrayList<io.github.fastformer.common.workspace.submission.WorkspaceCompletion>();
      io.github.fastformer.server.submission.WorkspaceResultSink results = (result, online) -> {
         assertNull(online);
         reported.add(result);
      };
      var execution = new io.github.fastformer.server.session.execution.OperationTasks(
         () -> false, ignored -> false, ignored -> results);
      execution.admit(owner, task);
      assertEquals(
         TaskCancellationResult.ROLLBACK_STARTED,
         execution.cancel(new WorldTaskContext(null, owner), history)
      );
      assertTrue(history.busy(owner));
      assertEquals(
         TaskCancellationResult.NOT_ACTIVE,
         execution.cancel(new WorldTaskContext(null, owner), history)
      );
      if (task instanceof SubmittedWorkspaceTask workspace) {
         assertEquals(1, reported.size());
         assertEquals(owner, reported.getFirst().owner());
         assertEquals(workspace.transferId(), reported.getFirst().transfer());
         assertTrue(reported.getFirst().recoveryCreated());
         assertFalse(reported.getFirst().applied());
         assertFalse(reported.getFirst().retryable());
      } else assertTrue(reported.isEmpty());
   }

   private static ReversibleBlockSnapshot snapshot(BlockPos pos, String marker) {
      CompoundTag tag = new CompoundTag();
      tag.putString("marker", marker);
      return new ReversibleBlockSnapshot(pos, null, null, new BlockEntitySnapshot(tag));
   }

   private static String marker(ReversibleBlockSnapshot snapshot) {
      return snapshot.blockEntity().data().getString("marker");
   }

   private static final class CancellableCompressionTask implements WorldTask {
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
         this.commit = WorldOperationCommit.begin(Level.OVERWORLD, before, after, null, task -> CompletableFuture.runAsync(task));
      }

      @Override
      public WorldRecoverySnapshot stopAndTransferRecovery() {
         this.readyForRecovery = this.commit.stopForRecovery();
         return new WorldRecoverySnapshot(this.before, this.after, this.readyForRecovery);
      }

      @Override
      public TaskStep tick(WorldTaskContext context) {
         return TaskStep.ACTIVE;
      }

      @Override
      public String phaseName() {
         return "COMMIT";
      }

      @Override
      public void cancel(io.github.fastformer.server.task.CancelReason reason) {
      }

      @Override
      public io.github.fastformer.server.recovery.PersistentRecoveryJournal journal() {
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
         return this.commit.batch();
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
