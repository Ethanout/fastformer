package io.github.fastformer.fastplace.world;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fastformer.fastplace.task.TaskCancellationResult;
import io.github.fastformer.fastplace.PlacementUpdateMode;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class WorldHistoryOwnerLifecycleTest {
   private static final ResourceKey<Level> DIMENSION = ResourceKey.create(
      Registries.DIMENSION,
      ResourceLocation.fromNamespaceAndPath("fastformer", "owner_lifecycle_test")
   );

   @AfterEach
   void clearHistory() {
      WorldHistoryManager.clearServer();
      PersistentRecoveryJournal.resetWriteGateForTest();
   }

   @Test
   void closedWriteGateDiscardsPendingAndCompletedPagesBeforeTheyCanResume() throws Exception {
      for (boolean activeTask : List.of(false, true)) {
         for (boolean completedPage : List.of(false, true)) {
            UUID ownerId = UUID.randomUUID();
            WorldChangeBatch older = batch(BlockPos.ZERO, UUID.randomUUID());
            var ownerMethod = WorldHistoryManager.class.getDeclaredMethod("ownerState", UUID.class);
            ownerMethod.setAccessible(true);
            Object owner = ownerMethod.invoke(null, ownerId);
            HistoryMemoryCache history = new HistoryMemoryCache();
            var historyField = owner.getClass().getDeclaredField("history");
            historyField.setAccessible(true);
            historyField.set(owner, history);
            HistoryTaskScheduler scheduler = new HistoryTaskScheduler();
            var plan = HistoryPageLoadPlan.create(true, 2, List.of(), 200,
               HistoryOrderCatalog.merge(List.of(), List.of(), List.of(older.operationId()), List.of())).orElseThrow();
            CompletableFuture<List<WorldChangeBatch>> load = new CompletableFuture<>();
            scheduler.startPageLoad(plan, load);
            var schedulerField = owner.getClass().getDeclaredField("scheduler");
            schedulerField.setAccessible(true);
            schedulerField.set(owner, scheduler);
            if (activeTask) {
               var activeField = owner.getClass().getDeclaredField("active");
               activeField.setAccessible(true);
               var constructor = activeField.getType().getDeclaredConstructor(
                  HistoryMemoryCache.class, boolean.class, int.class, PlacementUpdateMode.class
               );
               constructor.setAccessible(true);
               activeField.set(owner, constructor.newInstance(history, true, 2, PlacementUpdateMode.NORMAL));
            }
            if (completedPage) {
               load.complete(List.of(older));
            }
            PersistentRecoveryJournal.blockNewWrites();

            WorldHistoryManager.tickWorld(null);

            assertFalse(WorldHistoryManager.busy(ownerId), "closed gate retained a page request");
            assertEquals(0, WorldHistoryManager.undoSizeForTest(ownerId), "cancelled page entered the cache");
            assertFalse(load.complete(List.of(older)), "late completion survived cancellation");
            PersistentRecoveryJournal.resetWriteGateForTest();
            WorldHistoryManager.tickWorld(null);
            assertFalse(WorldHistoryManager.busy(ownerId));
            assertTrue(scheduler.takeCompletedPageLoad().isEmpty());
         }
      }
   }

   @Test
   void cancelAcceptsAnInitialPageLoadWithoutAnActiveWorldTask() throws Exception {
      UUID ownerId = UUID.randomUUID();
      UUID operation = UUID.randomUUID();
      var ownerMethod = WorldHistoryManager.class.getDeclaredMethod("ownerState", UUID.class);
      ownerMethod.setAccessible(true);
      Object owner = ownerMethod.invoke(null, ownerId);
      HistoryTaskScheduler scheduler = new HistoryTaskScheduler();
      var plan = HistoryPageLoadPlan.create(true, 2, List.of(), 200,
         HistoryOrderCatalog.merge(List.of(), List.of(), List.of(operation), List.of())).orElseThrow();
      CompletableFuture<List<WorldChangeBatch>> load = new CompletableFuture<>();
      scheduler.startPageLoad(plan, load);
      var schedulerField = owner.getClass().getDeclaredField("scheduler");
      schedulerField.setAccessible(true);
      schedulerField.set(owner, scheduler);
      assertTrue(WorldHistoryManager.busy(ownerId));
      assertTrue(WorldHistoryManager.cancel(ownerId));
      assertFalse(WorldHistoryManager.busy(ownerId));
      assertTrue(load.isCancelled());
      assertTrue(scheduler.takeCompletedPageLoad().isEmpty());
   }

   @Test
   void failedSnapshotWithAnAllocatedCacheStaysBlockedUntilSuccessfulRetry() throws Exception {
      UUID ownerId = UUID.randomUUID();
      var ownerMethod = WorldHistoryManager.class.getDeclaredMethod("ownerState", UUID.class);
      ownerMethod.setAccessible(true);
      Object owner = ownerMethod.invoke(null, ownerId);
      var historyField = owner.getClass().getDeclaredField("history");
      historyField.setAccessible(true);
      historyField.set(owner, new HistoryMemoryCache());
      var loadField = owner.getClass().getDeclaredField("historyLoad");
      loadField.setAccessible(true);
      loadField.set(owner, CompletableFuture.failedFuture(new IllegalStateException("snapshot unavailable")));
      var attach = WorldHistoryManager.class.getDeclaredMethod("attachLoadedHistory", WorldTaskContext.class);
      attach.setAccessible(true);
      attach.invoke(null, new WorldTaskContext(null, ownerId));
      assertTrue(WorldHistoryManager.busy(ownerId));

      loadField.set(owner, CompletableFuture.completedFuture(new WorldHistoryPersistence.LoadedHistory(
         List.of(), List.of(), List.of(), List.of()
      )));
      assertTrue(WorldHistoryManager.busy(ownerId));
      attach.invoke(null, new WorldTaskContext(null, ownerId));
      assertFalse(WorldHistoryManager.busy(ownerId));
   }

   @Test
   void completedHistorySurvivesOwnerLogoutForTheCurrentServerProcess() {
      UUID owner = UUID.randomUUID();
      BlockPos pos = new BlockPos(4, 5, 6);
      ReversibleBlockSnapshot before = snapshot(pos, "before");
      ReversibleBlockSnapshot after = snapshot(pos, "after");
      WorldChangeBatch batch = WorldChangeBatch.fromPairsForTest(
         DIMENSION, List.of(before), Map.of(pos, after)
      ).orElseThrow();

      WorldHistoryManager.addBatchForTest(owner, batch);
      WorldHistoryManager.detachOwner(owner);

      assertEquals(1, WorldHistoryManager.undoSizeForTest(owner));
   }

   @Test
   void loadedHistoryAppendsOlderUniqueBatchesWithoutReplacingNewMemoryHistory() {
      WorldChangeBatch newest = batch(new BlockPos(1, 2, 3), UUID.randomUUID());
      WorldChangeBatch duplicate = batch(new BlockPos(4, 5, 6), newest.operationId());
      WorldChangeBatch older = batch(new BlockPos(7, 8, 9), UUID.randomUUID());
      ArrayDeque<WorldChangeBatch> target = new ArrayDeque<>(List.of(newest));

      WorldHistoryManager.mergeLoaded(target, List.of(duplicate, older));

      assertEquals(List.of(newest.operationId(), older.operationId()),
         target.stream().map(WorldChangeBatch::operationId).toList());
   }

   @Test
   void offlineCompletionUsesTheOwnersConfiguredHistoryLimit() {
      UUID owner = UUID.randomUUID();
      WorldHistoryManager.setOwnerLimitForTest(owner, 800);
      for (int index = 0; index < 201; index++) {
         BlockPos pos = new BlockPos(index, 5, 6);
         WorldChangeBatch batch = WorldChangeBatch.fromPairsForTest(
            DIMENSION,
            List.of(snapshot(pos, "before")),
            Map.of(pos, snapshot(pos, "after"))
         ).orElseThrow();
         WorldHistoryManager.addBatchForTest(owner, batch);
      }

      assertEquals(201, WorldHistoryManager.undoSizeForTest(owner));
   }

   @Test
   void alreadyRestoredRecoveryExplicitlyReleasesItsWorldLease() {
      Object server = new Object();
      UUID owner = UUID.randomUUID();
      try {
         assertNotNull(WorldWriteCoordinator.acquire(server, DIMENSION, owner));

         WorldHistoryManager.releaseResolvedLease(server, DIMENSION, owner);

         assertEquals(false, WorldWriteCoordinator.heldBy(server, DIMENSION, owner));
      } finally {
         WorldWriteCoordinator.clear(server);
      }
   }

   @Test
   void historyIsNotPublishedBeforeJournalIsSealed() throws Exception {
      UUID owner = UUID.randomUUID();
      BlockPos pos = new BlockPos(7, 8, 9);
      WorldChangeBatch batch = WorldChangeBatch.fromPairsForTest(
         DIMENSION,
         List.of(snapshot(pos, "before")),
         Map.of(pos, snapshot(pos, "after"))
      ).orElseThrow();
      Path prepared = Files.createTempFile("fastformer-test-", ".dat");
      try {
         PersistentRecoveryJournal journal = new PersistentRecoveryJournal(prepared);
         assertFalse(WorldHistoryManager.commitPreparedOperation(
            new WorldTaskContext(null, owner), java.util.Optional.of(batch), journal
         ));
         assertEquals(0, WorldHistoryManager.undoSizeForTest(owner));
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
         WorldHistoryManager.acceptTransferredRecovery(
            new WorldTaskContext(null, owner), DIMENSION, recovery, null, () -> {}, () -> {}
         )
      );
      assertFalse(WorldHistoryManager.cancel(owner));
      assertFalse(WorldHistoryManager.cancel(owner));
      readyForRecovery.complete(null);

      WorldHistoryManager.detachOwner(owner);
      WorldHistoryManager.tickWorld(null);

      assertEquals(0, WorldHistoryManager.recoveryCaptureCountForTest(owner));
      assertNotNull(WorldHistoryManager.activeRecoveryBatchForTest(owner));
   }

   @Test
   void activeRecoveryIgnoresCancelAndSurvivesOwnerDetach() {
      UUID owner = UUID.randomUUID();
      BlockPos pos = new BlockPos(13, 14, 15);
      ArrayDeque<ReversibleBlockSnapshot> before = new ArrayDeque<>(List.of(snapshot(pos, "before")));
      Map<BlockPos, ReversibleBlockSnapshot> after = Map.of(pos, snapshot(pos, "after"));

      assertTrue(WorldHistoryManager.startRollback(
         new WorldTaskContext(null, owner), DIMENSION, before, after, null
      ));
      assertFalse(WorldHistoryManager.cancel(owner));
      assertFalse(WorldHistoryManager.cancel(owner));
      WorldHistoryManager.tickWorld(null);
      assertNotNull(WorldHistoryManager.activeRecoveryBatchForTest(owner));

      WorldHistoryManager.detachOwner(owner);
      WorldHistoryManager.tickWorld(null);

      assertNotNull(WorldHistoryManager.activeRecoveryBatchForTest(owner));
      assertTrue(WorldHistoryManager.busy(owner));
   }

   @Test
   void deferredUndoDoesNotBlockItsOwnDispatch() {
      UUID owner = UUID.randomUUID();
      WorldHistoryManager.deferUndoAfterRecovery(owner, 2);

      assertTrue(WorldHistoryManager.busy(owner));
      assertEquals(2, WorldHistoryManager.takeDeferredUndo(owner));
      assertFalse(WorldHistoryManager.busy(owner));
   }

   @Test
   void deferredUndoSurvivesOwnerDetachUntilRecoveryCanDispatchIt() {
      UUID owner = UUID.randomUUID();
      WorldHistoryManager.deferUndoAfterRecovery(owner, 2);

      WorldHistoryManager.detachOwner(owner);

      assertEquals(2, WorldHistoryManager.takeDeferredUndo(owner));
   }

   @Test
   void ownerPressureCannotDiscardTheOnlyCopyOfCompletedHistory() {
      UUID first = null;
      BlockPos pos = BlockPos.ZERO;
      WorldChangeBatch batch = WorldChangeBatch.fromPairsForTest(
         DIMENSION, List.of(snapshot(pos, "before")), Map.of(pos, snapshot(pos, "after"))
      ).orElseThrow();
      for (int i = 0; i < WorldHistoryManager.MAX_IDLE_OWNERS + 2; i++) {
         UUID owner = UUID.randomUUID();
         if (first == null) first = owner;
         WorldHistoryManager.addBatchForTest(owner, batch);
         WorldHistoryManager.detachOwner(owner);
      }
      assertEquals(1, WorldHistoryManager.undoSizeForTest(first));
   }

   @Test
   void detachedIdleOwnersAreBoundedWithoutDroppingBusyRecovery() {
      UUID busyOwner = UUID.randomUUID();
      BlockPos pos = new BlockPos(20, 21, 22);
      assertTrue(WorldHistoryManager.startRollback(
         new WorldTaskContext(null, busyOwner), DIMENSION,
         new ArrayDeque<>(List.of(snapshot(pos, "before"))),
         Map.of(pos, snapshot(pos, "after")), null
      ));
      WorldHistoryManager.detachOwner(busyOwner);

      for (int i = 0; i < WorldHistoryManager.MAX_IDLE_OWNERS + 20; i++) {
         UUID owner = UUID.randomUUID();
         WorldHistoryManager.deferUndoAfterRecovery(owner, 1);
         WorldHistoryManager.takeDeferredUndo(owner);
         WorldHistoryManager.detachOwner(owner);
      }

      assertTrue(WorldHistoryManager.ownerCountForTest() <= WorldHistoryManager.MAX_IDLE_OWNERS + 1);
      assertTrue(WorldHistoryManager.busy(busyOwner));
   }

   @Test
   void ownerPressureKeepsAllPendingRecoveryAndPersistenceState() {
      UUID pendingCapture = UUID.randomUUID();
      UUID pendingRecord = UUID.randomUUID();
      UUID pendingWrite = UUID.randomUUID();
      UUID failedWrite = UUID.randomUUID();
      UUID durableCommit = UUID.randomUUID();
      BlockPos pos = new BlockPos(23, 24, 25);
      CompletableFuture<Void> recoveryReady = new CompletableFuture<>();

      WorldHistoryManager.acceptTransferredRecovery(
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
      WorldHistoryManager.enqueuePendingRecordForTest(
         pendingRecord,
         DIMENSION,
         new ArrayDeque<>(List.of(snapshot(pos, "before"))),
         Map.of(pos, snapshot(pos, "after"))
      );
      WorldHistoryManager.setPersistenceRetentionForTest(pendingWrite, 1, false, false);
      WorldHistoryManager.setPersistenceRetentionForTest(failedWrite, 0, true, false);
      WorldHistoryManager.setPersistenceRetentionForTest(durableCommit, 0, false, true);

      List<UUID> protectedOwners = List.of(
         pendingCapture, pendingRecord, pendingWrite, failedWrite, durableCommit
      );
      protectedOwners.forEach(WorldHistoryManager::detachOwner);
      createDetachedIdleOwnerPressure();

      protectedOwners.forEach(owner -> assertTrue(
         WorldHistoryManager.ownerPresentForTest(owner),
         () -> "owner pressure removed pending state for " + owner
      ));
      assertEquals(1, WorldHistoryManager.recoveryCaptureCountForTest(pendingCapture));
   }

   private static void createDetachedIdleOwnerPressure() {
      for (int i = 0; i < WorldHistoryManager.MAX_IDLE_OWNERS + 20; i++) {
         UUID owner = UUID.randomUUID();
         WorldHistoryManager.deferUndoAfterRecovery(owner, 1);
         WorldHistoryManager.takeDeferredUndo(owner);
         WorldHistoryManager.detachOwner(owner);
      }
   }

   private static ReversibleBlockSnapshot snapshot(BlockPos pos, String marker) {
      CompoundTag tag = new CompoundTag();
      tag.putString("marker", marker);
      return new ReversibleBlockSnapshot(pos, null, null, new BlockEntitySnapshot(tag));
   }

   private static WorldChangeBatch batch(BlockPos pos, UUID operationId) {
      return WorldChangeBatch.fromPairsForTest(
         DIMENSION,
         List.of(snapshot(pos, "before")),
         Map.of(pos, snapshot(pos, "after"))
      ).orElseThrow().withOperationId(operationId);
   }

}
