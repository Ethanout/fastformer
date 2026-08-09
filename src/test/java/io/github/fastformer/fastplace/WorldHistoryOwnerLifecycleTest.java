package io.github.fastformer.fastplace;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import java.util.UUID;
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

   private static ReversibleBlockSnapshot snapshot(BlockPos pos, String marker) {
      CompoundTag tag = new CompoundTag();
      tag.putString("marker", marker);
      return new ReversibleBlockSnapshot(pos, null, null, new BlockEntitySnapshot(tag));
   }
}
