package io.github.fastformer.fastplace;

import io.github.fastformer.common.placement.context.QuickReplaceTargets;

import io.github.fastformer.server.history.HistoryTestAccess;

import io.github.fastformer.server.history.HistorySystem;

import io.github.fastformer.FastFormer;
import io.github.fastformer.server.history.HistoryService;
import io.github.fastformer.server.replace.QuickReplaceSystem;
import io.github.fastformer.common.replace.QuickReplaceTarget;
import io.github.fastformer.server.session.QuickShapeSystem;
import io.github.fastformer.server.session.OperationSystem;
import io.github.fastformer.server.session.SessionSystem;
import io.github.fastformer.server.player.PlayerStateRegistry;
import io.github.fastformer.server.mode.ModeInput;
import io.github.fastformer.server.mode.ModeInputResult;
import io.github.fastformer.common.mode.BuiltinModes;
import io.github.fastformer.server.gate.ServerOperationGates;
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

   @GameTest(template = "fastformergametests.empty", batch = "quick_replace_mode")
   public static void registeredToolKeepsVanillaRoutesAndRejectsOldToggles(GameTestHelper helper) {
      var target = helper.absolutePos(new BlockPos(2, 3, 3));
      helper.getLevel().setBlock(target, Blocks.STONE.defaultBlockState(), 18);
      var player = player(helper, target, 2);
      var modes = SessionSystem.of(player).modes();
      helper.assertTrue(QuickReplaceSystem.requestActive(player, 10, true), "tool request did not activate");
      helper.assertTrue(modes.active(player).orElseThrow().id().equals(BuiltinModes.QUICK_REPLACE), "tool is outside the mode registry");
      helper.assertFalse(modes.hasEditing(player), "temporary tool became a durable editing session");
      helper.assertTrue(ServerOperationGates.NO_SESSION.test(player).allowed(), "tool locked its own world history admission");
      helper.assertTrue(modes.input(player, ModeInput.RIGHT_BLOCK) == ModeInputResult.CONSUMED, "replacement allowed ordinary placement");
      helper.assertTrue(modes.input(player, ModeInput.RIGHT_ITEM) == ModeInputResult.VANILLA, "item use fell through to FF entry");
      helper.assertTrue(modes.input(player, ModeInput.LEFT_BLOCK) == ModeInputResult.VANILLA, "attack fell through to FF selection");
      helper.assertTrue(modes.input(player, ModeInput.SWAP_HANDS) == ModeInputResult.VANILLA, "F no longer passes to vanilla");
      helper.assertFalse(modes.quitTemporary(player).handled(), "history Q changed the replacement toggle protocol");
      helper.assertFalse(QuickReplaceSystem.requestActive(player, 11, false), "tool did not deactivate");
      helper.assertFalse(QuickReplaceSystem.requestActive(player, 10, true), "late activation reopened the tool");
      helper.assertTrue(QuickReplaceSystem.requestActive(player, 12, true), "new request failed");
      helper.assertTrue(QuickReplaceSystem.requestActive(player, 11, false), "late stop closed the current tool");
      SessionSystem.of(player).stopUnavailable(player);
      helper.assertFalse(modes.active(player, BuiltinModes.QUICK_REPLACE), "unavailable boundary retained tool ownership");
      PlayerStateRegistry.detach(player);
      helper.assertFalse(QuickReplaceSystem.requestActive(player, 13, true), "late packet recreated a detached scope");
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", batch = "quick_replace_mode")
   public static void toolRestartRetainsTheSeenStrokeFence(GameTestHelper helper) {
      var target = helper.absolutePos(new BlockPos(2, 3, 3));
      var player = player(helper, target, 2);
      helper.assertTrue(QuickReplaceSystem.requestActive(player, 1, true), "initial activation failed");
      // A release can arrive before its sample; that press must still remain retired.
      QuickReplaceSystem.sample(player, 21, true);
      QuickReplaceSystem.requestActive(player, 2, false);
      helper.assertTrue(QuickReplaceSystem.requestActive(player, 3, true), "reactivation failed");
      helper.getLevel().setBlock(target, Blocks.STONE.defaultBlockState(), 18);
      helper.assertFalse(QuickReplaceSystem.sample(player, 21, false), "old press reopened after tool restart");
      helper.assertTrue(helper.getLevel().getBlockState(target).is(Blocks.STONE), "late stroke wrote after restart");
      PlayerStateRegistry.dimensionChanged(player);
      helper.assertFalse(QuickReplaceSystem.active(player), "dimension retained temporary tool");
      helper.assertFalse(QuickReplaceSystem.requestActive(player, 2, true), "old request crossed the dimension boundary");
      helper.assertTrue(QuickReplaceSystem.requestActive(player, 4, true), "fresh request after dimension failed");
      helper.assertFalse(QuickReplaceSystem.sample(player, 21, false), "old press crossed the dimension boundary");
      SessionSystem.of(player).discardPlayer(player);
      PlayerStateRegistry.detach(player);
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", batch = "quick_replace_history", timeoutTicks = 1000)
   public static void replacementUsesHistoryAndRejectsWritesAfterExit(GameTestHelper helper) {
      var pos = helper.absolutePos(new BlockPos(2, 3, 3));
      helper.getLevel().setBlock(pos, Blocks.STONE.defaultBlockState(), 18);
      var player = player(helper, pos, 2);
      int[] phase = {-1};
      helper.succeedWhen(() -> {
         try { Thread.sleep(5); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
         HistorySystem.of(helper.getLevel().getServer()).tickWorld(helper.getLevel().getServer());
         if (phase[0] == -1) {
            helper.assertTrue(!io.github.fastformer.server.recovery.RecoverySystem.of(player.getServer()).writeLeases().busy(helper.getLevel().dimension()), "Waiting for the world writer");
            helper.assertTrue(!QuickReplaceSystem.replaceCrosshair(player), "Inactive session wrote a block");
            helper.assertTrue(QuickReplaceSystem.setActive(player, true), "Session did not start");
            helper.assertTrue(QuickReplaceSystem.replaceCrosshair(player), "Replacement failed");
            helper.assertTrue(helper.getLevel().getBlockState(pos).is(Blocks.GOLD_BLOCK), "Replacement missed its target");
            helper.assertTrue(HistorySystem.of(player).requestUndo(player, 1), "Undo was refused");
            phase[0] = 0;
         }
         helper.assertTrue(!HistorySystem.of(player).busy(player), "History is pending");
         if (phase[0] == 0) {
            helper.assertTrue(helper.getLevel().getBlockState(pos).is(Blocks.STONE), "Undo lost the original block");
            helper.assertTrue(HistorySystem.of(player).requestRedo(player, 1), "Redo was refused");
            phase[0] = 1;
            helper.assertTrue(false, "Redo is pending");
         }
         helper.assertTrue(helper.getLevel().getBlockState(pos).is(Blocks.GOLD_BLOCK), "Redo lost the replacement");
         QuickReplaceSystem.setActive(player, false);
         helper.assertTrue(!QuickReplaceSystem.active(player), "Session remained active");
         player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_BLOCK));
         helper.assertTrue(!QuickReplaceSystem.replaceCrosshair(player), "Closed session accepted a late request");
      });
   }

   @GameTest(template = "fastformergametests.empty", batch = "quick_replace")
   public static void targetUsesOutlineForPlantsAndRetainsStairProperties(GameTestHelper helper) {
      var pos = helper.absolutePos(new BlockPos(2, 3, 3));
      var player = player(helper, pos, 2);
      helper.getLevel().setBlock(pos, Blocks.SHORT_GRASS.defaultBlockState(), 18);
      helper.getLevel().setBlock(pos.south(), Blocks.STONE.defaultBlockState(), 18);
      var plant = QuickReplaceTargets.resolve(player);
      helper.assertTrue(plant != null && plant.position().equals(pos), "Replacement ray passed through grass");
      var stairs = Blocks.OAK_STAIRS.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST)
         .setValue(BlockStateProperties.HALF, net.minecraft.world.level.block.state.properties.Half.TOP);
      helper.getLevel().setBlock(pos, stairs, 18);
      player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STONE_STAIRS));
      var target = QuickReplaceTargets.resolve(player);
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
      helper.assertTrue(QuickReplaceSystem.setActive(player, true), "Session did not start");
      for (int distance : new int[] {2, 12}) {
         aim(player, pos, distance);
         var target = QuickReplaceTargets.resolve(player);
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
         helper.assertTrue(!QuickShapeSystem.of(player).active(player) && !OperationSystem.of(player).active(player), "Attack started an FF session");
      }
      io.github.fastformer.fastplace.settings.FastPlaceSettings.load(player).toggleEnabled(player);
      helper.assertTrue(!QuickReplaceSystem.setActive(player, true) && !QuickReplaceSystem.active(player),
         "Rejected activation retained server ownership");
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", batch = "quick_replace_held", timeoutTicks = 1000)
   public static void heldStrokeConnectsSurfaceAndUndoesAndRedoesAsOneRecord(GameTestHelper helper) {
      var first = helper.absolutePos(new BlockPos(1, 3, 3));
      var last = first.east(6);
      var player = player(helper, first, 5);
      for (int x = 0; x <= 6; x++) helper.getLevel().setBlock(first.east(x), Blocks.STONE.defaultBlockState(), 18);
      int[] phase = {0};
      helper.succeedWhen(() -> {
         HistorySystem.of(player.getServer()).tickWorld(player.getServer());
         if (phase[0] == 0) {
            helper.assertFalse(io.github.fastformer.server.recovery.RecoverySystem.of(player.getServer()).writeLeases().busy(player.level().dimension()),
               "waiting for world writer");
            helper.assertTrue(QuickReplaceSystem.setActive(player, true), "activation failed");
            helper.assertTrue(QuickReplaceSystem.sample(player, 1, false), "first sample failed");
            phase[0] = 1;
            helper.fail("waiting for next tick");
         }
         if (phase[0] == 1) {
            var delta = last.getCenter().add(0, 0, -0.5).subtract(player.getEyePosition());
            player.setYRot((float)-Math.toDegrees(Math.atan2(delta.x, delta.z)));
            player.setYHeadRot(player.getYRot());
            player.setXRot((float)-Math.toDegrees(Math.atan2(delta.y, Math.hypot(delta.x, delta.z))));
            var target = QuickReplaceTargets.resolve(player);
            helper.assertTrue(QuickReplaceSystem.sample(player, 1, false), "second sample failed: target=" + target
               + ", expected=" + last + ", busy=" + HistorySystem.of(player).busy(player));
            QuickReplaceSystem.sample(player, 1, true);
            helper.assertFalse(QuickReplaceSystem.sample(player, 1, false), "release accepted a late sample");
            phase[0] = 2;
         }
         if (phase[0] == 2) {
            for (int x = 0; x <= 6; x++) helper.assertTrue(helper.getLevel().getBlockState(first.east(x)).is(Blocks.GOLD_BLOCK),
               "stroke left a surface gap at " + x);
            helper.assertTrue(HistorySystem.of(player).requestUndo(player, 1), "undo refused");
            phase[0] = 3;
         }
         if (phase[0] == 3) {
            helper.assertFalse(HistorySystem.of(player).busy(player), "waiting for undo");
            for (int x = 0; x <= 6; x++) helper.assertTrue(helper.getLevel().getBlockState(first.east(x)).is(Blocks.STONE),
               "one undo did not restore the whole stroke at " + x);
            helper.assertTrue(HistorySystem.of(player).requestRedo(player, 1), "redo refused");
            phase[0] = 4;
         }
         helper.assertFalse(HistorySystem.of(player).busy(player), "waiting for redo");
         for (int x = 0; x <= 6; x++) helper.assertTrue(helper.getLevel().getBlockState(first.east(x)).is(Blocks.GOLD_BLOCK),
            "one redo did not restore the whole stroke at " + x);
         QuickReplaceSystem.setActive(player, false);
      });
   }

   @GameTest(template = "fastformergametests.empty", batch = "quick_replace_presses", timeoutTicks = 1000)
   public static void separatePressesAndRepeatedPositionsRetainDistinctUndoBoundaries(GameTestHelper helper) {
      var pos = helper.absolutePos(new BlockPos(2, 3, 3));
      helper.getLevel().setBlock(pos, Blocks.STONE.defaultBlockState(), 18);
      var player = player(helper, pos, 2);
      int[] phase = {0};
      helper.succeedWhen(() -> {
         HistorySystem.of(helper.getLevel().getServer()).tickWorld(helper.getLevel().getServer());
         if (phase[0] == 0) {
            helper.assertFalse(io.github.fastformer.server.recovery.RecoverySystem.of(player.getServer()).writeLeases().busy(player.level().dimension()),
               "waiting for writer");
            helper.assertTrue(QuickReplaceSystem.setActive(player, true), "activation failed");
            helper.assertTrue(QuickReplaceSystem.sample(player, 10, false), "first sample failed");
            phase[0] = 1;
            helper.fail("waiting for next tick");
         }
         if (phase[0] == 1) {
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_BLOCK));
            helper.assertTrue(QuickReplaceSystem.sample(player, 10, false), "revisited cell failed");
            QuickReplaceSystem.sample(player, 10, true);
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.EMERALD_BLOCK));
            helper.assertTrue(QuickReplaceSystem.sample(player, 11, false), "new press in same tick failed");
            QuickReplaceSystem.sample(player, 11, true);
            helper.assertFalse(QuickReplaceSystem.sample(player, 10, false), "old press reopened");
            helper.assertTrue(HistorySystem.of(player).requestUndo(player, 1), "undo refused");
            phase[0] = 2;
         }
         if (phase[0] == 2) {
            helper.assertFalse(HistorySystem.of(player).busy(player), "waiting for second press undo");
            helper.assertTrue(helper.getLevel().getBlockState(pos).is(Blocks.DIAMOND_BLOCK), "separate presses were merged");
            helper.assertTrue(HistorySystem.of(player).requestUndo(player, 1), "second undo refused");
            phase[0] = 3;
         }
         helper.assertFalse(HistorySystem.of(player).busy(player), "waiting for first press undo");
         helper.assertTrue(helper.getLevel().getBlockState(pos).is(Blocks.STONE), "first press lost its original state");
         QuickReplaceSystem.setActive(player, false);
      });
   }

   @GameTest(template = "fastformergametests.empty", batch = "quick_replace_occlusion", timeoutTicks = 1000)
   public static void sweepRespectsForegroundAndReleaseBreaksThePath(GameTestHelper helper) {
      var first = helper.absolutePos(new BlockPos(1, 40, 3));
      var foreground = first.east(3).north(2);
      for (int x = 0; x <= 6; x++) helper.getLevel().setBlock(first.east(x), Blocks.DIAMOND_BLOCK.defaultBlockState(), 18);
      helper.getLevel().setBlock(foreground, Blocks.STONE.defaultBlockState(), 18);
      var player = player(helper, first, 5);
      int[] phase = {0};
      helper.succeedWhen(() -> {
         HistorySystem.of(player.getServer()).tickWorld(player.getServer());
         if (phase[0] == 0) {
            helper.assertFalse(io.github.fastformer.server.recovery.RecoverySystem.of(player.getServer()).writeLeases().busy(player.level().dimension()),
               "waiting for world writer");
            helper.assertTrue(QuickReplaceSystem.setActive(player, true), "activation failed");
            helper.assertTrue(QuickReplaceSystem.sample(player, 1, false), "first sample failed");
            phase[0] = 1;
            helper.fail("waiting for next tick");
         }
         if (phase[0] == 1) {
            aim(player, first.east(6), 5);
            helper.assertTrue(QuickReplaceSystem.sample(player, 1, false), "sweep failed");
            QuickReplaceSystem.sample(player, 1, true);
            phase[0] = 2;
         }
         if (phase[0] == 2) {
            for (int x = 0; x <= 6; x++) helper.assertTrue(helper.getLevel().getBlockState(first.east(x))
               .is(x == 3 ? Blocks.DIAMOND_BLOCK : Blocks.GOLD_BLOCK), "occlusion mismatch at " + x);
            helper.assertTrue(helper.getLevel().getBlockState(foreground).is(Blocks.GOLD_BLOCK), "foreground was skipped");
            // A new press aimed back across the wall must not inherit the old sweep.
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.EMERALD_BLOCK));
            aim(player, first, 5);
            helper.assertTrue(QuickReplaceSystem.sample(player, 2, false), "new press failed");
            QuickReplaceSystem.sample(player, 2, true);
            phase[0] = 3;
         }
         helper.assertTrue(helper.getLevel().getBlockState(first).is(Blocks.EMERALD_BLOCK), "new press missed endpoint");
         for (int x = 1; x <= 6; x++) helper.assertFalse(helper.getLevel().getBlockState(first.east(x)).is(Blocks.EMERALD_BLOCK),
            "new press bridged the released path at " + x);
         QuickReplaceSystem.setActive(player, false);
      });
   }

   private static ServerPlayer player(GameTestHelper helper, BlockPos target, int distance) {
      var player = helper.makeMockServerPlayerInLevel();
      HistoryTestAccess.awaitInitialHistoryLoad(HistorySystem.of(player), player, 10_000);
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
