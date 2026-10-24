package io.github.fastformer.fastplace.history;

import io.github.fastformer.FastFormer;
import io.github.fastformer.fastplace.interaction.BlockTinker;
import io.github.fastformer.fastplace.placement.PlacementUpdateMode;
import io.github.fastformer.fastplace.world.BlockEditTransaction;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(FastFormer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class TinkerHistoryCompactionGameTests {
   private TinkerHistoryCompactionGameTests() { }

   @GameTest(template = "fastformergametests.empty", timeoutTicks = 100000, batch = "tinker_recompaction")
   public static void olderGroupsRecompactPersistAndUndoAsOneGroup(GameTestHelper helper) {
      var player = helper.makeMockServerPlayerInLevel();
      WorldHistoryManager.awaitInitialHistoryLoadForTest(player, 10_000L);
      player.setGameMode(GameType.CREATIVE);
      var level = helper.getLevel();
      var pos = helper.absolutePos(new BlockPos(2, 2, 2));
      var recent = pos.east();
      int flags = PlacementUpdateMode.CLIENT_ONLY.flags();
      level.setBlock(pos, Blocks.COMPOSTER.defaultBlockState(), flags);
      level.setBlock(recent, Blocks.AIR.defaultBlockState(), flags);
      for (int index = 1; index <= 4; index++) {
         var before = io.github.fastformer.fastplace.world.snapshot.ReversibleBlockSnapshot.capture(level, pos).orElseThrow();
         level.setBlock(pos, Blocks.COMPOSTER.defaultBlockState().setValue(BlockStateProperties.LEVEL_COMPOSTER, index), flags);
         var after = io.github.fastformer.fastplace.world.snapshot.ReversibleBlockSnapshot.capture(level, pos).orElseThrow();
         helper.assertTrue(WorldHistoryManager.recordTinker(player, level, new java.util.ArrayDeque<>(java.util.List.of(before)),
            Map.of(pos, after), new BlockEditHistoryMetadata("same", 8, 1)), "old group record failed");
      }
      for (int index = 0; index < 34; index++) {
         var state = index % 2 == 0 ? Blocks.GOLD_BLOCK.defaultBlockState() : Blocks.STONE.defaultBlockState();
         helper.assertTrue(BlockEditTransaction.apply(player, level, Map.of(recent, state), flags, flags)
            == BlockEditTransaction.Outcome.APPLIED, "newer ordinary write failed");
      }
      var originalIds = WorldHistoryManager.historyOrderForTest(player.getUUID(), true);
      helper.assertTrue(originalIds.size() == 38, "expected four old groups and 34 newer actions");
      int[] phase = {0};
      var load = new AtomicReference<CompletableFuture<WorldHistoryPersistence.LoadedHistory>>();
      helper.succeedWhen(() -> {
         java.util.concurrent.locks.LockSupport.parkNanos(2_000_000L);
         WorldHistoryManager.tickWorld(level.getServer());
         if (phase[0] == 0) {
            var ids = WorldHistoryManager.historyOrderForTest(player.getUUID(), true);
            helper.assertTrue(ids.size() == 35, "waiting for old groups to merge again");
            helper.assertTrue(ids.subList(0, 34).equals(originalIds.subList(0, 34)), "newer history order changed");
            load.set(WorldHistoryPersistence.loadSnapshot(level.getServer(), player.getUUID(), 200, 64L * 1024 * 1024));
            phase[0] = 1;
         }
         if (phase[0] == 1) {
            helper.assertTrue(load.get().isDone(), "waiting for durable recompacted history");
            var loaded = load.get().join();
            helper.assertTrue(loaded.undo().size() == 35, "recompaction did not update durable order");
            helper.assertTrue(loaded.undo().getLast().tinker().actions() == 32, "merged count was not preserved");
            helper.assertTrue(loaded.undo().getLast().tinker().compressionStage() == 2, "compression stage was not persisted");
            helper.assertTrue(WorldHistoryManager.requestUndo(player, 35), "group undo refused");
            phase[0] = 2;
         }
         if (phase[0] == 2) {
            helper.assertTrue(!WorldHistoryManager.busy(player), "waiting for undo");
            helper.assertTrue(level.getBlockState(pos).getValue(BlockStateProperties.LEVEL_COMPOSTER) == 0, "undo did not restore all four groups");
            helper.assertTrue(level.getBlockState(recent).isAir(), "newer undo was reordered");
            helper.assertTrue(WorldHistoryManager.requestRedo(player, 35), "group redo refused");
            phase[0] = 3;
         }
         helper.assertTrue(!WorldHistoryManager.busy(player), "waiting for redo");
         helper.assertTrue(level.getBlockState(pos).getValue(BlockStateProperties.LEVEL_COMPOSTER) == 4, "redo did not replay the whole old group");
         helper.assertTrue(level.getBlockState(recent).is(Blocks.STONE), "redo lost newer writes");
      });
   }

   @GameTest(template = "fastformergametests.empty", timeoutTicks = 100000, batch = "tinker_compaction")
   public static void backgroundMergePersistsAndReplaysAsOneRecord(GameTestHelper helper) {
      var player = helper.makeMockServerPlayerInLevel();
      WorldHistoryManager.awaitInitialHistoryLoadForTest(player, 10_000L);
      player.setGameMode(GameType.CREATIVE);
      var settings = io.github.fastformer.fastplace.settings.FastPlaceSettings.load(player);
      settings.setTinkerHistoryMergeLimit(player, TinkerHistoryMergeLimits.Setting.RECENT_GROUP, 2);
      settings.setTinkerHistoryMergeLimit(player, TinkerHistoryMergeLimits.Setting.RECENT_AFTER, 4);
      var level = helper.getLevel();
      var server = level.getServer();
      BlockPos position = helper.absolutePos(new BlockPos(2, 2, 2));
      player.setPos(position.getCenter().add(0, 0, -2));
      int flags = PlacementUpdateMode.CLIENT_ONLY.flags();
      level.setBlock(position, Blocks.COMPOSTER.defaultBlockState(), flags);
      var hit = new BlockHitResult(position.getCenter().add(0, 0.5, 0), Direction.UP, position, false);
      helper.assertTrue(BlockTinker.use(player, hit), "first wrench edit was not consumed");
      helper.assertTrue(BlockTinker.use(player, hit), "second wrench edit was not consumed");
      helper.assertTrue(level.getBlockState(position).getValue(BlockStateProperties.LEVEL_COMPOSTER) == 2,
         "wrench did not apply both edits");
      var protectedPositions = new ArrayList<BlockPos>();
      for (int index = 0; index < 5; index++) {
         var recent = helper.absolutePos(new BlockPos(index, 1, 1));
         level.setBlock(recent, Blocks.AIR.defaultBlockState(), flags);
         protectedPositions.add(recent);
         helper.assertTrue(BlockEditTransaction.apply(player, level,
            Map.of(recent, Blocks.STONE.defaultBlockState()), flags, flags) == BlockEditTransaction.Outcome.APPLIED,
            "protected ordinary edit failed");
      }
      var originalIds = WorldHistoryManager.historyOrderForTest(player.getUUID(), true);
      helper.assertTrue(originalIds.size() == 7, "expected seven uncompressed operations");
      int[] phase = {0};
      var load = new AtomicReference<CompletableFuture<WorldHistoryPersistence.LoadedHistory>>();
      helper.succeedWhen(() -> {
         WorldHistoryManager.tickWorld(server);
         if (phase[0] == 0) {
            var ids = WorldHistoryManager.historyOrderForTest(player.getUUID(), true);
            helper.assertTrue(ids.size() == 6, "background compaction is pending");
            helper.assertTrue(ids.subList(0, 5).equals(originalIds.subList(0, 5)), "recent ordinary history changed");
            load.set(WorldHistoryPersistence.loadSnapshot(server, player.getUUID(), 200, 64L * 1024 * 1024));
            phase[0] = 1;
         }
         if (phase[0] == 1) {
            helper.assertTrue(load.get().isDone(), "compacted history reload is pending");
            var loaded = load.get().join();
            helper.assertTrue(loaded.undo().size() == 6, "durable index retained the original records");
            var merged = loaded.undo().getLast();
            helper.assertTrue(merged.tinker() != null && merged.tinker().actions() == 2, "compaction metadata was lost");
            helper.assertTrue(merged.targetSnapshots(true).getFirst().state().getValue(BlockStateProperties.LEVEL_COMPOSTER) == 0,
               "merged history lost the original state");
            helper.assertTrue(merged.sourceSnapshots(true).getFirst().state().getValue(BlockStateProperties.LEVEL_COMPOSTER) == 2,
               "merged history lost the final state");
            helper.assertTrue(WorldHistoryManager.requestUndo(player, 6), "undo refused compacted history");
            phase[0] = 2;
         }
         if (phase[0] == 2) {
            helper.assertTrue(!WorldHistoryManager.busy(player), "undo is pending");
            helper.assertTrue(level.getBlockState(position).getValue(BlockStateProperties.LEVEL_COMPOSTER) == 0,
               "undo did not restore the original composter level");
            for (BlockPos recent : protectedPositions) helper.assertTrue(level.getBlockState(recent).isAir(), "ordinary undo failed");
            helper.assertTrue(WorldHistoryManager.requestRedo(player, 6), "redo refused compacted history");
            phase[0] = 3;
         }
         helper.assertTrue(!WorldHistoryManager.busy(player), "redo is pending");
         helper.assertTrue(level.getBlockState(position).getValue(BlockStateProperties.LEVEL_COMPOSTER) == 2,
            "redo did not restore the final composter level");
         for (BlockPos recent : protectedPositions) helper.assertTrue(level.getBlockState(recent).is(Blocks.STONE), "ordinary redo failed");
      });
   }
}
