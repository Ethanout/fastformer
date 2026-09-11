package io.github.fastformer.fastplace.world;

import io.github.fastformer.FastFormer;
import io.github.fastformer.fastplace.history.HistoryBatchStore;
import io.github.fastformer.fastplace.history.WorldHistoryBatchCodec;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(FastFormer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class WorldHistoryPersistenceGameTests {
   private WorldHistoryPersistenceGameTests() {
   }

   @GameTest(template = "fastformergametests.empty", timeoutTicks = 200)
   public static void newOperationRemovesRetiredRedoFileAfterIndexCommit(GameTestHelper helper) {
      UUID owner = UUID.randomUUID();
      UUID retired = UUID.randomUUID();
      UUID current = UUID.randomUUID();
      BlockPos pos = new BlockPos(5, 20, 3);
      var before = new ReversibleBlockSnapshot(pos, Blocks.STONE.defaultBlockState(), Fluids.EMPTY.defaultFluidState(), null);
      var after = new ReversibleBlockSnapshot(pos, Blocks.GOLD_BLOCK.defaultBlockState(), Fluids.EMPTY.defaultFluidState(), null);
      var batch = WorldChangeBatch.capturePairsByPos(helper.getLevel().dimension(), List.of(before),
         new HashMap<>(Map.of(pos, after))).orElseThrow();
      var server = helper.getLevel().getServer();
      var published = WorldHistoryPersistence.publishNewBatch(server, owner, batch.withOperationId(retired),
         List.of(retired), List.of())
         .thenCompose(ignored -> WorldHistoryPersistence.publishIndex(server, owner, List.of(), List.of(retired)))
         .thenCompose(ignored -> WorldHistoryPersistence.publishNewBatch(server, owner, batch.withOperationId(current),
            List.of(current), List.of()));
      var ownerRoot = server.getWorldPath(LevelResource.ROOT).resolve("fastformer-history").resolve(owner.toString());
      helper.succeedWhen(() -> {
         helper.assertTrue(published.isDone(), "history publication is still pending");
         published.join();
         helper.assertTrue(!java.nio.file.Files.exists(ownerRoot.resolve("batches").resolve(retired + ".dat")),
            "retired redo batch still occupies disk");
         helper.assertTrue(java.nio.file.Files.isRegularFile(ownerRoot.resolve("batches").resolve(current + ".dat")),
            "cleanup removed current undo batch");
      });
   }

   @GameTest(template = "fastformergametests.empty", timeoutTicks = 200)
   public static void completedManagerBatchReachesDisk(GameTestHelper helper) {
      publishAndCheck(helper, false);
   }

   @GameTest(template = "fastformergametests.empty", timeoutTicks = 300)
   public static void failedIndexPublicationRetriesFromMemory(GameTestHelper helper) {
      publishAndCheck(helper, true);
   }

   private static void publishAndCheck(GameTestHelper helper, boolean failFirstWrite) {
      UUID owner = UUID.randomUUID();
      UUID operation = UUID.randomUUID();
      BlockPos pos = new BlockPos(5, 20, 3);
      ReversibleBlockSnapshot before = new ReversibleBlockSnapshot(pos,
         Blocks.STONE.defaultBlockState(), Fluids.EMPTY.defaultFluidState(), null);
      ReversibleBlockSnapshot after = new ReversibleBlockSnapshot(pos,
         Blocks.GOLD_BLOCK.defaultBlockState(), Fluids.EMPTY.defaultFluidState(), null);
      WorldChangeBatch batch = WorldChangeBatch.capturePairsByPos(helper.getLevel().dimension(),
         List.of(before), new HashMap<>(Map.of(pos, after))).orElseThrow().withOperationId(operation);
      var root = helper.getLevel().getServer().getWorldPath(LevelResource.ROOT).resolve("fastformer-history");
      var indexPath = root.resolve(owner.toString()).resolve("index.dat");
      if (failFirstWrite) {
         try {
            java.nio.file.Files.createDirectories(indexPath);
            java.nio.file.Files.writeString(indexPath.resolve("blocked"), "force index publication to fail");
         } catch (java.io.IOException failure) {
            throw new AssertionError("cannot prepare history write failure", failure);
         }
      }
      var releaseIo = new java.util.concurrent.CompletableFuture<Void>();
      java.util.concurrent.CompletableFuture.runAsync(releaseIo::join, WorldHistoryPersistence.executor());
      java.util.concurrent.CompletableFuture<Void> shutdownBarrier;
      try {
         helper.assertTrue(WorldHistoryManager.commitPreparedOperation(
            new WorldTaskContext(helper.getLevel().getServer(), owner), Optional.of(batch), null),
            "completed history was not accepted");
         shutdownBarrier = WorldHistoryPersistence.pendingWrites(helper.getLevel().getServer());
         helper.assertTrue(!shutdownBarrier.isDone(), "shutdown barrier ignored queued history writes");
      } finally {
         releaseIo.complete(null);
      }
      boolean[] failureObserved = {false};
      helper.succeedWhen(() -> {
         if (failFirstWrite && !failureObserved[0]) {
            helper.assertTrue(WorldHistoryManager.persistenceFailedForTest(owner), "waiting for index write failure");
            helper.assertTrue(WorldHistoryManager.undoSizeForTest(owner) == 1, "failed save lost in-memory history");
            try {
               java.nio.file.Files.delete(indexPath.resolve("blocked"));
               java.nio.file.Files.delete(indexPath);
            } catch (java.io.IOException failure) {
               throw new AssertionError("cannot release history write failure", failure);
            }
            failureObserved[0] = true;
         }
         HistoryBatchStore reopened = new HistoryBatchStore(root, Runnable::run, 1,
            256L * 1024 * 1024, 8L * 1024 * 1024 * 1024, 1024, 4, 1024);
         var order = reopened.loadIndex(owner).join();
         helper.assertTrue(order.isPresent(), "background history publication is still pending");
         helper.assertTrue(shutdownBarrier.isDone(), "shutdown barrier is still waiting for the publication chain");
         helper.assertTrue(order.orElseThrow().undo().equals(List.of(operation)), "saved undo order differs");
         helper.assertTrue(order.orElseThrow().redo().isEmpty(), "new operation retained redo history");
         try {
            var restored = WorldHistoryBatchCodec.decode(helper.getLevel().registryAccess(),
               reopened.loadBatch(owner, operation).join().orElseThrow(), 1024 * 1024);
            helper.assertTrue(restored.operationId().equals(operation), "saved operation ID differs");
            helper.assertTrue(restored.targetSnapshots(true).getFirst().sameContents(before), "saved before state differs");
            helper.assertTrue(restored.sourceSnapshots(true).getFirst().sameContents(after), "saved after state differs");
         } catch (java.io.IOException failure) {
            throw new AssertionError("saved history cannot be decoded", failure);
         }
      });
   }
}
