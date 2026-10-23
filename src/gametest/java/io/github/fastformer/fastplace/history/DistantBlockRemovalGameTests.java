package io.github.fastformer.fastplace.history;

import io.github.fastformer.FastFormer;
import io.github.fastformer.fastplace.geometry.raycast.LongRangeBlockRaycast;
import io.github.fastformer.fastplace.history.WorldHistoryManager;
import io.github.fastformer.fastplace.history.WorldHistoryPersistence;
import io.github.fastformer.fastplace.placement.PlacementUpdateMode;
import io.github.fastformer.fastplace.world.BlockEditTransaction;
import io.github.fastformer.network.payload.placement.DistantBlockRemovePayload;
import io.github.fastformer.server.input.ServerInputDispatcher;
import io.github.fastformer.server.input.IdleInteractionSnapshots;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(FastFormer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DistantBlockRemovalGameTests {
   private DistantBlockRemovalGameTests() { }

   @GameTest(template = "fastformergametests.empty", timeoutTicks = 100000, batch = "distant_remove")
   public static void distantRemovalsUseSharedCompactionAndUndoRestoresBothStates(GameTestHelper helper) {
      var player = player(helper);
      var level = helper.getLevel();
      var position = helper.absolutePos(new BlockPos(2, 2, 2));
      var first = Blocks.OAK_LOG.defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.X);
      var second = Blocks.OAK_LOG.defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.Z);
      int flags = PlacementUpdateMode.CLIENT_ONLY.flags();
      level.setBlock(position, first, flags);
      level.setBlock(position.east(), second, flags);
      lookDown(player, position, 40);
      helper.assertTrue(ServerInputDispatcher.removeDistantBlock(player, request(player)), "first distant removal failed");
      lookDown(player, position.east(), 40);
      helper.assertTrue(ServerInputDispatcher.removeDistantBlock(player, request(player)), "second distant removal failed");
      helper.assertTrue(level.getBlockState(position).isAir() && level.getBlockState(position.east()).isAir(), "removal did not clear both blocks");
      helper.assertTrue(IdleInteractionSnapshots.idle(player), "removal started a selection session");
      for (int index = 0; index < 5; index++) {
         var recent = helper.absolutePos(new BlockPos(index, 1, 1));
         level.setBlock(recent, Blocks.AIR.defaultBlockState(), flags);
         helper.assertTrue(BlockEditTransaction.apply(player, level, Map.of(recent, Blocks.STONE.defaultBlockState()),
            flags, flags) == BlockEditTransaction.Outcome.APPLIED, "protected history edit failed");
      }
      int[] phase = {0};
      var load = new AtomicReference<CompletableFuture<WorldHistoryPersistence.LoadedHistory>>();
      helper.succeedWhen(() -> {
         WorldHistoryManager.tickWorld(level.getServer());
         if (phase[0] == 0) {
            helper.assertTrue(WorldHistoryManager.historyOrderForTest(player.getUUID(), true).size() == 6, "shared history compaction is pending");
            load.set(WorldHistoryPersistence.loadSnapshot(level.getServer(), player.getUUID(), 200, 64L * 1024 * 1024));
            phase[0] = 1;
         }
         if (phase[0] == 1) {
            helper.assertTrue(load.get().isDone(), "history load is pending");
            var merged = load.get().join().undo().getLast();
            helper.assertTrue(merged.tinker() != null && merged.tinker().actions() == 2 && merged.size() == 2,
               "removals did not reuse wrench history metadata and compaction");
            helper.assertTrue(WorldHistoryManager.requestUndo(player, 6), "group undo was refused");
            phase[0] = 2;
         }
         helper.assertFalse(WorldHistoryManager.busy(player), "undo is pending");
         helper.assertTrue(level.getBlockState(position).equals(first), "group undo lost the first block state");
         helper.assertTrue(level.getBlockState(position.east()).equals(second), "group undo lost the second block state");
      });
   }

   @GameTest(template = "fastformergametests.empty", batch = "distant_remove")
   public static void nearAndHeldItemRemovalRequestsAreRejected(GameTestHelper helper) {
      var player = player(helper);
      var position = helper.absolutePos(new BlockPos(2, 2, 2));
      helper.getLevel().setBlock(position, Blocks.STONE.defaultBlockState(), 2);
      lookDown(player, position, 2);
      helper.assertFalse(ServerInputDispatcher.removeDistantBlock(player, request(player)), "near removal bypassed vanilla interaction");
      lookDown(player, position, 40);
      player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STONE));
      helper.assertFalse(ServerInputDispatcher.removeDistantBlock(player, request(player)), "held-item removal was accepted");
      player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
      io.github.fastformer.fastplace.settings.FastPlaceSettings.load(player).toggleDistantBlockRemoval(player);
      helper.assertFalse(ServerInputDispatcher.removeDistantBlock(player, request(player)), "disabled distant removal was accepted");
      helper.assertTrue(helper.getLevel().getBlockState(position).is(Blocks.STONE), "rejected request changed the block");
      helper.succeed();
   }

   private static ServerPlayer player(GameTestHelper helper) {
      var player = helper.makeMockServerPlayerInLevel();
      WorldHistoryManager.awaitInitialHistoryLoadForTest(player, 10_000);
      player.setGameMode(GameType.CREATIVE);
      player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
      player.getAttribute(Attributes.BLOCK_INTERACTION_RANGE).setBaseValue(20);
      return player;
   }

   private static void lookDown(ServerPlayer player, BlockPos position, int distance) {
      player.setPos(position.getCenter().add(0, distance, 0));
      player.setXRot(90);
   }

   private static DistantBlockRemovePayload request(ServerPlayer player) {
      var eye = player.getEyePosition();
      var view = player.getViewVector(1);
      return new DistantBlockRemovePayload(LongRangeBlockRaycast.clipForSelection(player.level(), player, eye, view, false).hit(), eye, view, false);
   }
}
