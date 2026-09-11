package io.github.fastformer.fastplace.world;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fastformer.fastplace.task.TaskCancellationResult;
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
         assertEquals(true, WorldWriteCoordinator.tryAcquire(server, DIMENSION, owner));

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
   void queuedRecoveryRemainsPausedUntilExplicitResume() {
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
      assertTrue(WorldHistoryManager.cancel(owner));
      assertTrue(WorldHistoryManager.cancel(owner));
      readyForRecovery.complete(null);

      WorldHistoryManager.detachOwner(owner);
      WorldHistoryManager.tickWorld(null);

      assertEquals(1, WorldHistoryManager.recoveryCaptureCountForTest(owner));
      assertNull(WorldHistoryManager.activeRecoveryBatchForTest(owner));
      assertTrue(WorldHistoryManager.resumeRecovery(owner));
      WorldHistoryManager.tickWorld(null);
      assertNotNull(WorldHistoryManager.activeRecoveryBatchForTest(owner));
   }

   @Test
   void pausedActiveRecoverySurvivesOwnerDetach() {
      UUID owner = UUID.randomUUID();
      BlockPos pos = new BlockPos(13, 14, 15);
      ArrayDeque<ReversibleBlockSnapshot> before = new ArrayDeque<>(List.of(snapshot(pos, "before")));
      Map<BlockPos, ReversibleBlockSnapshot> after = Map.of(pos, snapshot(pos, "after"));

      assertTrue(WorldHistoryManager.startRollback(
         new WorldTaskContext(null, owner), DIMENSION, before, after, null
      ));
      assertTrue(WorldHistoryManager.cancel(owner));
      WorldHistoryManager.tickWorld(null);
      assertNull(WorldHistoryManager.activeRecoveryBatchForTest(owner));

      WorldHistoryManager.detachOwner(owner);
      WorldHistoryManager.tickWorld(null);

      assertNull(WorldHistoryManager.activeRecoveryBatchForTest(owner));
      assertTrue(WorldHistoryManager.busy(owner));
      assertTrue(WorldHistoryManager.resumeRecovery(owner));
      assertNotNull(WorldHistoryManager.activeRecoveryBatchForTest(owner));
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

   private static ReversibleBlockSnapshot snapshot(BlockPos pos, String marker) {
      CompoundTag tag = new CompoundTag();
      tag.putString("marker", marker);
      return new ReversibleBlockSnapshot(pos, null, null, new BlockEntitySnapshot(tag));
   }

}
