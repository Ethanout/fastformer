package io.github.fastformer.server.history;

import io.github.fastformer.server.history.entry.WorldChangeBatch;
import io.github.fastformer.server.history.entry.HistoryEntry;
import io.github.fastformer.server.history.memory.HistoryMemoryCache;
import io.github.fastformer.server.history.memory.HistoryOrderCatalog;
import io.github.fastformer.server.history.memory.HistoryPageLoadPlan;
import io.github.fastformer.server.history.memory.HistoryTaskScheduler;
import io.github.fastformer.server.history.replay.HistoryReplayTask;

import io.github.fastformer.server.history.rollback.RollbackRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fastformer.fastplace.placement.PlacementUpdateMode;
import io.github.fastformer.server.recovery.PersistentRecoveryJournal;
import io.github.fastformer.server.recovery.RecoveryJournalTestAccess;
import io.github.fastformer.server.recovery.WorldRecoverySnapshot;
import io.github.fastformer.fastplace.task.TaskCancellationResult;
import io.github.fastformer.fastplace.world.WorldTaskContext;
import io.github.fastformer.fastplace.world.WorldWriteCoordinator;
import io.github.fastformer.fastplace.world.snapshot.BlockEntitySnapshot;
import io.github.fastformer.fastplace.world.snapshot.ReversibleBlockSnapshot;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class WorldHistoryOwnerLifecycleTest {
   private static final HistoryService history = new HistoryService(
      io.github.fastformer.core.gate.Gate.require("test", ignored -> true),
      io.github.fastformer.core.gate.Gate.require("test", ignored -> true), new io.github.fastformer.server.history.NoDiskHistoryRepository());
   private static final ResourceKey<Level> DIMENSION = ResourceKey.create(
      Registries.DIMENSION,
      ResourceLocation.fromNamespaceAndPath("fastformer", "owner_lifecycle_test")
   );

   @AfterEach
   void clearHistory() {
      history.clear();
      RecoveryJournalTestAccess.resetWriteGate();
   }

   @Test
   void closedWriteGateDiscardsPendingAndCompletedPagesBeforeTheyCanResume() throws Exception {
      for (boolean activeTask : List.of(false, true)) {
         for (boolean completedPage : List.of(false, true)) {
            UUID ownerId = UUID.randomUUID();
            WorldChangeBatch older = batch(BlockPos.ZERO, UUID.randomUUID());
            OwnerHistory owner = history.ownerState(ownerId);
            HistoryMemoryCache<HistoryEntry> memory = new HistoryMemoryCache<HistoryEntry>();
            owner.history = memory;
            HistoryTaskScheduler<HistoryEntry> scheduler = owner.loading.pages();
            var plan = HistoryPageLoadPlan.create(true, 2, List.of(), 200,
               HistoryOrderCatalog.merge(List.of(), List.of(), List.of(older.operationId()), List.of())).orElseThrow();
            CompletableFuture<List<HistoryEntry>> load = new CompletableFuture<>();
            scheduler.startPageLoad(plan, load);
            if (activeTask) {
               owner.active = new HistoryReplayTask(new HistoryReplayTask.HistoryAccess() {
                  @Override public HistoryReplayTask.PageState requestPage(WorldTaskContext context, boolean undo, int remaining) {
                     throw new AssertionError("closed gate must not request another page");
                  }
                  @Override public void commit(WorldTaskContext context, HistoryMemoryCache<HistoryEntry> memory, boolean undo) {
                     throw new AssertionError("closed gate must not commit");
                  }
               }, memory, true, 2, PlacementUpdateMode.NORMAL,
                  io.github.fastformer.server.history.replay.EntryReplayRegistry.standard());
            }
            if (completedPage) {
               load.complete(List.of(older));
            }
            PersistentRecoveryJournal.blockNewWrites();

            history.tickWorld(null);

            assertFalse(history.busy(ownerId), "closed gate retained a page request");
            assertEquals(0, HistoryTestAccess.undoSize(history, ownerId), "cancelled page entered the cache");
            assertFalse(load.complete(List.of(older)), "late completion survived cancellation");
            RecoveryJournalTestAccess.resetWriteGate();
            history.tickWorld(null);
            assertFalse(history.busy(ownerId));
            assertTrue(scheduler.takeCompletedPageLoad().isEmpty());
         }
      }
   }

   @Test
   void cancelAcceptsAnInitialPageLoadWithoutAnActiveWorldTask() throws Exception {
      UUID ownerId = UUID.randomUUID();
      UUID operation = UUID.randomUUID();
      OwnerHistory owner = history.ownerState(ownerId);
      HistoryTaskScheduler<HistoryEntry> scheduler = owner.loading.pages();
      var plan = HistoryPageLoadPlan.create(true, 2, List.of(), 200,
         HistoryOrderCatalog.merge(List.of(), List.of(), List.of(operation), List.of())).orElseThrow();
      CompletableFuture<List<HistoryEntry>> load = new CompletableFuture<>();
      scheduler.startPageLoad(plan, load);
      assertTrue(history.busy(ownerId));
      assertTrue(history.cancel(ownerId));
      assertFalse(history.busy(ownerId));
      assertTrue(load.isCancelled());
      assertTrue(scheduler.takeCompletedPageLoad().isEmpty());
   }

   @Test
   void failedSnapshotWithAnAllocatedCacheStaysBlockedUntilSuccessfulRetry() throws Exception {
      UUID ownerId = UUID.randomUUID();
      OwnerHistory owner = history.ownerState(ownerId);
      owner.history = new HistoryMemoryCache<HistoryEntry>();
      owner.loading.startSnapshot(CompletableFuture.failedFuture(new IllegalStateException("snapshot unavailable")));
      history.tickWorld(null);
      assertTrue(history.busy(ownerId));

      owner.loading.startSnapshot(CompletableFuture.completedFuture(new HistoryRepository.Snapshot<HistoryEntry>(
         List.of(), List.of(), List.of(), List.of()
      )));
      assertTrue(history.busy(ownerId));
      history.tickWorld(null);
      assertFalse(history.busy(ownerId));
   }

   @Test
   void completedHistorySurvivesOwnerLogoutForTheCurrentServerProcess() {
      UUID owner = UUID.randomUUID();
      BlockPos pos = new BlockPos(4, 5, 6);
      ReversibleBlockSnapshot before = snapshot(pos, "before");
      ReversibleBlockSnapshot after = snapshot(pos, "after");
      WorldChangeBatch batch = WorldChangeBatch.capturePairsByPos(
         DIMENSION, List.of(before), Map.of(pos, after)
      ).orElseThrow();

      HistoryTestAccess.addBatch(history, owner, batch);
      history.detachOwner(owner);

      assertEquals(1, HistoryTestAccess.undoSize(history, owner));
   }

   @Test
   void loadedHistoryAppendsOlderUniqueBatchesWithoutReplacingNewMemoryHistory() {
      WorldChangeBatch newest = batch(new BlockPos(1, 2, 3), UUID.randomUUID());
      WorldChangeBatch duplicate = batch(new BlockPos(4, 5, 6), newest.operationId());
      WorldChangeBatch older = batch(new BlockPos(7, 8, 9), UUID.randomUUID());
      HistoryMemoryCache<HistoryEntry> target = new HistoryMemoryCache<HistoryEntry>();
      target.addNew(newest);
      target.merge(true, List.of(duplicate, older));

      assertEquals(List.of(newest.operationId(), older.operationId()),
         target.operationIds(true));
   }

   @Test
   void offlineCompletionUsesTheOwnersConfiguredHistoryLimit() {
      UUID owner = UUID.randomUUID();
      HistoryTestAccess.setOwnerLimit(history, owner, 800);
      for (int index = 0; index < 201; index++) {
         BlockPos pos = new BlockPos(index, 5, 6);
         WorldChangeBatch batch = WorldChangeBatch.capturePairsByPos(
            DIMENSION,
            List.of(snapshot(pos, "before")),
            Map.of(pos, snapshot(pos, "after"))
         ).orElseThrow();
         HistoryTestAccess.addBatch(history, owner, batch);
      }

      assertEquals(201, HistoryTestAccess.undoSize(history, owner));
   }

   @Test
   void alreadyRestoredRecoveryExplicitlyReleasesItsWorldLease() {
      Object server = new Object();
      UUID owner = UUID.randomUUID();
      try {
         assertNotNull(WorldWriteCoordinator.acquire(server, DIMENSION, owner));

         WorldWriteCoordinator.releaseCurrentLease(server, DIMENSION, owner);

         assertEquals(false, WorldWriteCoordinator.heldBy(server, DIMENSION, owner));
      } finally {
         WorldWriteCoordinator.clear(server);
      }
   }

   @Test
   void historyIsNotPublishedBeforeJournalIsSealed() throws Exception {
      UUID owner = UUID.randomUUID();
      BlockPos pos = new BlockPos(7, 8, 9);
      WorldChangeBatch batch = WorldChangeBatch.capturePairsByPos(
         DIMENSION,
         List.of(snapshot(pos, "before")),
         Map.of(pos, snapshot(pos, "after"))
      ).orElseThrow();
      Path prepared = Files.createTempFile("fastformer-test-", ".dat");
      try {
         PersistentRecoveryJournal journal = RecoveryJournalTestAccess.open(prepared);
         assertFalse(history.commitPreparedOperation(
            new WorldTaskContext(null, owner), java.util.Optional.of(batch), journal
         ));
         assertEquals(0, HistoryTestAccess.undoSize(history, owner));
      } finally {
         Files.deleteIfExists(prepared);
      }
   }

   @Test
   void queuedRecoveryDispatchesAutomaticallyAndCannotBePaused() {
      UUID owner = UUID.randomUUID();
      BlockPos pos = new BlockPos(10, 11, 12);
      CompletableFuture<Void> readyForRecovery = new CompletableFuture<>();
      WorldRecoverySnapshot recovery = new WorldRecoverySnapshot(
         new ArrayDeque<>(List.of(snapshot(pos, "before"))),
         Map.of(pos, snapshot(pos, "after")),
         readyForRecovery
      );

      assertEquals(
         TaskCancellationResult.ROLLBACK_STARTED,
         history.acceptTransferredRecovery(
            new WorldTaskContext(null, owner), DIMENSION, recovery, null, () -> {}, () -> {}
         )
      );
      assertFalse(history.cancel(owner));
      assertFalse(history.cancel(owner));
      readyForRecovery.complete(null);

      history.detachOwner(owner);
      history.tickWorld(null);

      assertEquals(0, HistoryTestAccess.recoveryCaptureCount(history, owner));
      assertNotNull(HistoryTestAccess.activeRecoveryBatch(history, owner));
   }

   @Test
   void activeRecoveryIgnoresCancelAndSurvivesOwnerDetach() {
      UUID owner = UUID.randomUUID();
      BlockPos pos = new BlockPos(13, 14, 15);
      ArrayDeque<ReversibleBlockSnapshot> before = new ArrayDeque<>(List.of(snapshot(pos, "before")));
      Map<BlockPos, ReversibleBlockSnapshot> after = Map.of(pos, snapshot(pos, "after"));

      assertTrue(history.rollback(RollbackRequest.ready(
         new WorldTaskContext(null, owner), DIMENSION, before, after, null
      )));
      assertFalse(history.cancel(owner));
      assertFalse(history.cancel(owner));
      history.tickWorld(null);
      assertNotNull(HistoryTestAccess.activeRecoveryBatch(history, owner));

      history.detachOwner(owner);
      history.tickWorld(null);

      assertNotNull(HistoryTestAccess.activeRecoveryBatch(history, owner));
      assertTrue(history.busy(owner));
   }

   @Test
   void deferredUndoDoesNotBlockItsOwnDispatch() {
      UUID owner = UUID.randomUUID();
      history.deferUndoAfterRecovery(owner, 2);

      assertTrue(history.busy(owner));
      assertEquals(2, history.takeDeferredUndo(owner));
      assertFalse(history.busy(owner));
   }

   @Test
   void deferredUndoSurvivesOwnerDetachUntilRecoveryCanDispatchIt() {
      UUID owner = UUID.randomUUID();
      history.deferUndoAfterRecovery(owner, 2);

      history.detachOwner(owner);

      assertEquals(2, history.takeDeferredUndo(owner));
   }

   @Test
   void ownerPressureCannotDiscardTheOnlyCopyOfCompletedHistory() {
      UUID first = null;
      BlockPos pos = BlockPos.ZERO;
      WorldChangeBatch batch = WorldChangeBatch.capturePairsByPos(
         DIMENSION, List.of(snapshot(pos, "before")), Map.of(pos, snapshot(pos, "after"))
      ).orElseThrow();
      for (int i = 0; i < HistoryService.MAX_IDLE_OWNERS + 2; i++) {
         UUID owner = UUID.randomUUID();
         if (first == null) first = owner;
         HistoryTestAccess.addBatch(history, owner, batch);
         history.detachOwner(owner);
      }
      assertEquals(1, HistoryTestAccess.undoSize(history, first));
   }

   @Test
   void detachedIdleOwnersAreBoundedWithoutDroppingBusyRecovery() {
      UUID busyOwner = UUID.randomUUID();
      BlockPos pos = new BlockPos(20, 21, 22);
      assertTrue(history.rollback(RollbackRequest.ready(
         new WorldTaskContext(null, busyOwner), DIMENSION,
         new ArrayDeque<>(List.of(snapshot(pos, "before"))),
         Map.of(pos, snapshot(pos, "after")), null
      )));
      history.detachOwner(busyOwner);

      for (int i = 0; i < HistoryService.MAX_IDLE_OWNERS + 20; i++) {
         UUID owner = UUID.randomUUID();
         history.deferUndoAfterRecovery(owner, 1);
         history.takeDeferredUndo(owner);
         history.detachOwner(owner);
      }

      assertTrue(history.ownerCount() <= HistoryService.MAX_IDLE_OWNERS + 1);
      assertTrue(history.busy(busyOwner));
   }

   @Test
   void ownerPressureKeepsAllPendingRecoveryAndPersistenceState() {
      UUID pendingCapture = UUID.randomUUID();
      UUID pendingWrite = UUID.randomUUID();
      UUID failedWrite = UUID.randomUUID();
      UUID durableCommit = UUID.randomUUID();
      BlockPos pos = new BlockPos(23, 24, 25);
      CompletableFuture<Void> recoveryReady = new CompletableFuture<>();

      history.acceptTransferredRecovery(
         new WorldTaskContext(null, pendingCapture), DIMENSION,
         new WorldRecoverySnapshot(
            new ArrayDeque<>(List.of(snapshot(pos, "before"))),
            Map.of(pos, snapshot(pos, "after")),
            recoveryReady
         ),
         null,
         () -> {},
         () -> {}
      );
      HistoryTestAccess.setPersistenceRetention(history, pendingWrite, 1, false, false);
      HistoryTestAccess.setPersistenceRetention(history, failedWrite, 0, true, false);
      HistoryTestAccess.setPersistenceRetention(history, durableCommit, 0, false, true);

      List<UUID> protectedOwners = List.of(
         pendingCapture, pendingWrite, failedWrite, durableCommit
      );
      protectedOwners.forEach(history::detachOwner);
      createDetachedIdleOwnerPressure();

      protectedOwners.forEach(owner -> assertTrue(
         HistoryTestAccess.ownerPresent(history, owner),
         () -> "owner pressure removed pending state for " + owner
      ));
      assertEquals(1, HistoryTestAccess.recoveryCaptureCount(history, pendingCapture));
   }

   private static void createDetachedIdleOwnerPressure() {
      for (int i = 0; i < HistoryService.MAX_IDLE_OWNERS + 20; i++) {
         UUID owner = UUID.randomUUID();
         history.deferUndoAfterRecovery(owner, 1);
         history.takeDeferredUndo(owner);
         history.detachOwner(owner);
      }
   }

   private static ReversibleBlockSnapshot snapshot(BlockPos pos, String marker) {
      CompoundTag tag = new CompoundTag();
      tag.putString("marker", marker);
      return new ReversibleBlockSnapshot(pos, null, null, new BlockEntitySnapshot(tag));
   }

   private static WorldChangeBatch batch(BlockPos pos, UUID operationId) {
      return WorldChangeBatch.capturePairsByPos(
         DIMENSION,
         List.of(snapshot(pos, "before")),
         Map.of(pos, snapshot(pos, "after"))
      ).orElseThrow().withOperationId(operationId);
   }

}
