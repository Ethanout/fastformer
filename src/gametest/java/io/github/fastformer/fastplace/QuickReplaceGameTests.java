package io.github.fastformer.fastplace;

import io.github.fastformer.FastFormer;
import io.github.fastformer.fastplace.history.WorldHistoryManager;
import io.github.fastformer.fastplace.placement.replace.QuickReplaceManager;
import io.github.fastformer.fastplace.placement.replace.QuickReplaceTarget;
import io.github.fastformer.server.input.ServerReachGate;
import io.github.fastformer.server.session.FastPlaceManager;
import io.github.fastformer.server.session.OperationManager;
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
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(FastFormer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class QuickReplaceGameTests {
   private QuickReplaceGameTests() { }

   @GameTest(template = "fastformergametests.empty", batch = "quick_replace", timeoutTicks = 1000)
   public static void replacementUsesHistoryAndRejectsWritesAfterExit(GameTestHelper helper) {
      var pos = helper.absolutePos(new BlockPos(2, 3, 3));
      helper.getLevel().setBlock(pos, Blocks.STONE.defaultBlockState(), 18);
      var player = player(helper, pos, 2);
      helper.assertTrue(!QuickReplaceManager.replaceCrosshair(player), "Inactive session wrote a block");
      helper.assertTrue(QuickReplaceManager.setActive(player, true), "Session did not start");
      helper.assertTrue(QuickReplaceManager.replaceCrosshair(player), "Replacement failed");
      helper.assertTrue(helper.getLevel().getBlockState(pos).is(Blocks.GOLD_BLOCK), "Replacement missed its target");
      helper.assertTrue(WorldHistoryManager.requestUndo(player, 1), "Undo was refused");
      int[] phase = {0};
      helper.succeedWhen(() -> {
         try { Thread.sleep(5); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
         WorldHistoryManager.tickWorld(helper.getLevel().getServer());
         helper.assertTrue(!WorldHistoryManager.busy(player), "History is pending");
         if (phase[0] == 0) {
            helper.assertTrue(helper.getLevel().getBlockState(pos).is(Blocks.STONE), "Undo lost the original block");
            helper.assertTrue(WorldHistoryManager.requestRedo(player, 1), "Redo was refused");
            phase[0] = 1;
            helper.assertTrue(false, "Redo is pending");
         }
         helper.assertTrue(helper.getLevel().getBlockState(pos).is(Blocks.GOLD_BLOCK), "Redo lost the replacement");
         QuickReplaceManager.setActive(player, false);
         helper.assertTrue(!QuickReplaceManager.active(player), "Session remained active");
         player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_BLOCK));
         helper.assertTrue(!QuickReplaceManager.replaceCrosshair(player), "Closed session accepted a late request");
      });
   }

   @GameTest(template = "fastformergametests.empty", batch = "quick_replace")
   public static void targetUsesOutlineForPlantsAndRetainsStairProperties(GameTestHelper helper) {
      var pos = helper.absolutePos(new BlockPos(2, 3, 3));
      var player = player(helper, pos, 2);
      helper.getLevel().setBlock(pos, Blocks.SHORT_GRASS.defaultBlockState(), 18);
      helper.getLevel().setBlock(pos.south(), Blocks.STONE.defaultBlockState(), 18);
      var plant = QuickReplaceTarget.resolve(player);
      helper.assertTrue(plant != null && plant.position().equals(pos), "Replacement ray passed through grass");
      var stairs = Blocks.OAK_STAIRS.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST)
         .setValue(BlockStateProperties.HALF, net.minecraft.world.level.block.state.properties.Half.TOP);
      helper.getLevel().setBlock(pos, stairs, 18);
      player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STONE_STAIRS));
      var target = QuickReplaceTarget.resolve(player);
      helper.assertTrue(target != null && target.position().equals(pos), "Stair target missing");
      helper.assertTrue(target.state().is(Blocks.STONE_STAIRS)
         && target.state().getValue(BlockStateProperties.HORIZONTAL_FACING) == Direction.WEST
         && target.state().getValue(BlockStateProperties.HALF) == net.minecraft.world.level.block.state.properties.Half.TOP,
         "Replacement lost shared stair properties");
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", batch = "quick_replace")
   public static void sessionKeepsVanillaActionsAtBothDistances(GameTestHelper helper) {
      var pos = helper.absolutePos(new BlockPos(2, 30, 3));
      helper.getLevel().setBlock(pos, Blocks.STONE.defaultBlockState(), 18);
      var player = player(helper, pos, 2);
      helper.assertTrue(QuickReplaceManager.setActive(player, true), "Session did not start");
      for (int distance : new int[] {2, 12}) {
         aim(player, pos, distance);
         ServerReachGate.tick(player);
         helper.assertTrue(ServerReachGate.vanillaAt(player, distance), "Quick replace changed distance ownership");
         var target = QuickReplaceTarget.resolve(player);
         var ray = (net.minecraft.world.phys.BlockHitResult)player.pick(player.blockInteractionRange(), 1, false);
         helper.assertTrue(target != null && target.position().equals(pos),
            "Target differs at distance " + distance + ": expected=" + pos + ", actual=" + target
               + ", reach=" + player.blockInteractionRange() + ", eye=" + player.getEyePosition()
               + ", ray=" + ray.getType() + " " + ray.getBlockPos() + " " + ray.getLocation()
               + ", view=" + player.getViewVector(1) + ", block=" + helper.getLevel().getBlockState(pos));
         var attack = new PlayerInteractEvent.LeftClickBlock(player, pos, Direction.NORTH,
            PlayerInteractEvent.LeftClickBlock.Action.START);
         NeoForge.EVENT_BUS.post(attack);
         helper.assertTrue(!attack.isCanceled(), "Quick replace consumed vanilla attack");
         helper.assertTrue(!FastPlaceManager.active(player) && !OperationManager.active(player), "Attack started an FF session");
      }
      io.github.fastformer.fastplace.settings.FastPlaceSettings.load(player).toggleEnabled(player);
      helper.assertTrue(!QuickReplaceManager.setActive(player, true) && !QuickReplaceManager.active(player),
         "Rejected activation retained server ownership");
      helper.succeed();
   }

   private static ServerPlayer player(GameTestHelper helper, BlockPos target, int distance) {
      var player = helper.makeMockServerPlayerInLevel();
      WorldHistoryManager.awaitInitialHistoryLoadForTest(player, 10_000);
      player.setGameMode(GameType.CREATIVE);
      player.getAttribute(Attributes.BLOCK_INTERACTION_RANGE).setBaseValue(20);
      player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.GOLD_BLOCK));
      aim(player, target, distance);
      return player;
   }

   private static void aim(ServerPlayer player, BlockPos target, int distance) {
      player.setPos(target.getX() + 0.5, target.getY() + 0.7 - player.getEyeHeight(), target.getZ() - distance);
      player.setYRot(0);
      player.setYHeadRot(0);
      player.setXRot(0);
   }
}
