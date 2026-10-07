package io.github.fastformer.fastplace;

import io.github.fastformer.FastFormer;
import io.github.fastformer.fastplace.placement.ForcedPlacement;
import io.github.fastformer.fastplace.settings.FastPlaceSettings;
import io.github.fastformer.fastplace.settings.LegacyReachMigration;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(FastFormer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ForcedPlacementGameTests {
   @GameTest(template = "fastformergametests.empty", batch = "forced_seeds", timeoutTicks = 600)
   public static void pumpkinAndMelonSeedsStartAtFullGrowth(GameTestHelper helper) {
      var player = helper.makeMockServerPlayerInLevel();
      player.setGameMode(GameType.CREATIVE);
      io.github.fastformer.fastplace.history.WorldHistoryManager.awaitInitialHistoryLoadForTest(player, 10_000L);
      var level = helper.getLevel();
      var pumpkinFloor = helper.absolutePos(new BlockPos(2, 2, 2));
      var melonFloor = pumpkinFloor.east(2);
      for (var floor : java.util.List.of(pumpkinFloor, melonFloor)) {
         level.setBlock(floor, Blocks.FARMLAND.defaultBlockState(), 2);
         level.setBlock(floor.above(), Blocks.AIR.defaultBlockState(), 2);
      }
      player.setPos(pumpkinFloor.getX() + 0.5, pumpkinFloor.getY() + 1, pumpkinFloor.getZ() + 0.5);
      helper.succeedWhen(() -> {
         java.util.concurrent.locks.LockSupport.parkNanos(5_000_000L);
         helper.assertFalse(io.github.fastformer.fastplace.world.WorldWriteCoordinator.busy(level.getServer(), level.dimension()),
            "waiting for world writer");
         player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.PUMPKIN_SEEDS));
         helper.assertTrue(ForcedPlacement.use(player, new BlockHitResult(Vec3.atLowerCornerOf(pumpkinFloor).add(0.5, 1, 0.5),
            Direction.UP, pumpkinFloor, false)), "pumpkin placement was not consumed");
         var pumpkin = level.getBlockState(pumpkinFloor.above());
         helper.assertTrue(pumpkin.is(Blocks.PUMPKIN_STEM) && pumpkin.getValue(
            net.minecraft.world.level.block.state.properties.BlockStateProperties.AGE_7) == 7, "pumpkin did not start mature");
         player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.MELON_SEEDS));
         helper.assertTrue(ForcedPlacement.use(player, new BlockHitResult(Vec3.atLowerCornerOf(melonFloor).add(0.5, 1, 0.5),
            Direction.UP, melonFloor, false)), "melon placement was not consumed");
         var melon = level.getBlockState(melonFloor.above());
         helper.assertTrue(melon.is(Blocks.MELON_STEM) && melon.getValue(
            net.minecraft.world.level.block.state.properties.BlockStateProperties.AGE_7) == 7, "melon did not start mature");
      });
   }

   @GameTest(template = "fastformergametests.empty", batch = "forced_door", timeoutTicks = 1200)
   public static void forcedDoorPlacesBothHalvesAndOneUndoRemovesBoth(GameTestHelper helper) {
      var player = helper.makeMockServerPlayerInLevel();
      player.setGameMode(GameType.CREATIVE);
      io.github.fastformer.fastplace.history.WorldHistoryManager.awaitInitialHistoryLoadForTest(player, 10_000L);
      var level = helper.getLevel();
      var floor = helper.absolutePos(new BlockPos(2, 2, 2));
      level.setBlock(floor, Blocks.STONE.defaultBlockState(), 2);
      level.setBlock(floor.above(), Blocks.AIR.defaultBlockState(), 2);
      level.setBlock(floor.above(2), Blocks.AIR.defaultBlockState(), 2);
      player.setPos(floor.getX() + 0.5, floor.getY() + 1, floor.getZ() + 0.5);
      player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.OAK_DOOR));
      var hit = new BlockHitResult(Vec3.atLowerCornerOf(floor).add(0.5, 1, 0.5), Direction.UP, floor, false);
      int[] phase = {0};
      helper.succeedWhen(() -> {
         java.util.concurrent.locks.LockSupport.parkNanos(5_000_000L);
         io.github.fastformer.fastplace.history.WorldHistoryManager.tickWorld(level.getServer());
         if (phase[0] == 0) {
            helper.assertFalse(io.github.fastformer.fastplace.world.WorldWriteCoordinator.busy(level.getServer(), level.dimension()),
               "waiting for world writer");
            var result = player.gameMode.useItemOn(player, level, player.getMainHandItem(), InteractionHand.MAIN_HAND, hit);
            helper.assertTrue(result.consumesAction(), "door click did not stop vanilla placement");
            var lower = level.getBlockState(floor.above());
            var upper = level.getBlockState(floor.above(2));
            helper.assertTrue(lower.is(Blocks.OAK_DOOR) && lower.getValue(net.minecraft.world.level.block.DoorBlock.HALF)
               == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER, "door lower half missing");
            helper.assertTrue(upper.equals(lower.setValue(net.minecraft.world.level.block.DoorBlock.HALF,
               net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER)), "door halves disagree");
            helper.assertTrue(player.getMainHandItem().getCount() == 1, "creative door placement consumed the item");
            phase[0] = 1;
         }
         if (phase[0] == 1) {
            helper.assertTrue(io.github.fastformer.fastplace.history.WorldHistoryManager.requestUndo(player, 1), "door undo was refused");
            phase[0] = 2;
         }
         helper.assertFalse(io.github.fastformer.fastplace.history.WorldHistoryManager.busy(player), "door undo is pending");
         helper.assertTrue(level.getBlockState(floor.above()).isAir() && level.getBlockState(floor.above(2)).isAir(),
            "one undo did not remove both halves");
         helper.assertTrue(level.getBlockState(floor).is(Blocks.STONE), "door undo changed the floor");
      });
   }

   @GameTest(template = "fastformergametests.empty", batch = "forced_door_obstruction", timeoutTicks = 400)
   public static void blockedUpperDoorCellDoesNotLeaveALowerHalf(GameTestHelper helper) {
      var player = helper.makeMockServerPlayerInLevel();
      player.setGameMode(GameType.CREATIVE);
      io.github.fastformer.fastplace.history.WorldHistoryManager.awaitInitialHistoryLoadForTest(player, 10_000L);
      var floor = helper.absolutePos(new BlockPos(2, 2, 2));
      var level = helper.getLevel();
      level.setBlock(floor, Blocks.STONE.defaultBlockState(), 2);
      level.setBlock(floor.above(), Blocks.AIR.defaultBlockState(), 2);
      level.setBlock(floor.above(2), Blocks.STONE.defaultBlockState(), 2);
      player.setPos(floor.getX() + 0.5, floor.getY() + 1, floor.getZ() + 0.5);
      player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.OAK_DOOR));
      var hit = new BlockHitResult(Vec3.atLowerCornerOf(floor).add(0.5, 1, 0.5), Direction.UP, floor, false);
      helper.succeedWhen(() -> {
         helper.assertFalse(io.github.fastformer.fastplace.world.WorldWriteCoordinator.busy(level.getServer(), level.dimension()),
            "waiting for world writer");
         helper.assertTrue(ForcedPlacement.use(player, hit), "blocked door yielded to vanilla");
         helper.assertTrue(level.getBlockState(floor.above()).isAir(), "blocked door left a lower half");
         helper.assertTrue(level.getBlockState(floor.above(2)).is(Blocks.STONE), "blocked door overwrote its obstruction");
      });
   }

   @GameTest(template = "fastformergametests.empty", batch = "forced_placement", timeoutTicks = 600)
   public static void turningAfterTheClickDoesNotRetargetOrDuplicateTheLog(GameTestHelper helper) {
      var player = helper.makeMockServerPlayerInLevel();
      player.setGameMode(GameType.CREATIVE);
      io.github.fastformer.fastplace.history.WorldHistoryManager.awaitInitialHistoryLoadForTest(player, 10_000L);
      var floor = helper.absolutePos(new BlockPos(2, 2, 2));
      var other = floor.east(2);
      helper.getLevel().setBlock(floor, Blocks.STONE.defaultBlockState(), 2);
      helper.getLevel().setBlock(other, Blocks.STONE.defaultBlockState(), 2);
      player.setPos(floor.getX() + 0.5, floor.getY() + 1, floor.getZ() + 0.5);
      player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.OAK_LOG));
      var hit = new BlockHitResult(Vec3.atLowerCornerOf(floor).add(0.5, 1, 0.5), Direction.UP, floor, false);
      player.setYRot(180);
      player.setXRot(-60);
      boolean[] clicked = {false};
      helper.succeedWhen(() -> {
         java.util.concurrent.locks.LockSupport.parkNanos(5_000_000L);
         helper.assertFalse(io.github.fastformer.fastplace.world.WorldWriteCoordinator.busy(
            player.getServer(), helper.getLevel().dimension()), "waiting for world writer");
         if (!clicked[0]) {
            var result = player.gameMode.useItemOn(player, helper.getLevel(), player.getMainHandItem(), InteractionHand.MAIN_HAND, hit);
            helper.assertTrue(result.consumesAction(), "forced use must stop the vanilla placement path");
            clicked[0] = true;
         }
         helper.assertTrue(helper.getLevel().getBlockState(floor.above()).is(Blocks.OAK_LOG), "clicked target missing");
         helper.assertTrue(helper.getLevel().getBlockState(floor.above(2)).isAir(), "one click stacked a second log");
         helper.assertTrue(helper.getLevel().getBlockState(other.above()).isAir(), "turning moved the placement target");
      });
   }

   @GameTest(template = "fastformergametests.empty", batch = "forced_placement", timeoutTicks = 400)
   public static void unsupportedLadderUsesHistoryTransaction(GameTestHelper helper) {
      var player = helper.makeMockServerPlayerInLevel();
      player.setGameMode(GameType.CREATIVE);
      var floor = helper.absolutePos(new BlockPos(2, 2, 2));
      helper.getLevel().setBlock(floor, Blocks.STONE.defaultBlockState(), 2);
      player.setPos(floor.getX() + 0.5, floor.getY() + 1, floor.getZ() + 0.5);
      player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.LADDER));
      // Mock players do not run ServerPlayer's normal creative attribute update.
      player.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.BLOCK_INTERACTION_RANGE)
         .addOrUpdateTransientModifier(new net.minecraft.world.entity.ai.attributes.AttributeModifier(
            net.minecraft.resources.ResourceLocation.withDefaultNamespace("creative_mode_block_range"), 0.5,
            net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_VALUE));
      player.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.ENTITY_INTERACTION_RANGE)
         .addOrUpdateTransientModifier(new net.minecraft.world.entity.ai.attributes.AttributeModifier(
            net.minecraft.resources.ResourceLocation.withDefaultNamespace("creative_mode_entity_range"), 2,
            net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_VALUE));
      var blockRange = player.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.BLOCK_INTERACTION_RANGE);
      blockRange.setBaseValue(19.5);
      player.getPersistentData().putBoolean("fastformerReachInitialized", true);
      LegacyReachMigration.apply(player);
      helper.assertTrue(Math.abs(player.blockInteractionRange() - 5) < 1.0E-6, "legacy range was not restored");
      blockRange.setBaseValue(15.5);
      LegacyReachMigration.apply(player);
      helper.assertTrue(Math.abs(player.blockInteractionRange() - 16) < 1.0E-6, "migration overwrote another owner's range");
      var hit = new BlockHitResult(Vec3.atLowerCornerOf(floor).add(0.5, 1, 0.5), Direction.UP, floor, false);
      helper.succeedWhen(() -> {
         try { Thread.sleep(5); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
         helper.assertTrue(ForcedPlacement.use(player, hit), "force placement must consume the click");
         helper.assertTrue(helper.getLevel().getBlockState(floor.above()).is(Blocks.LADDER), "unsupported ladder missing");
         FastPlaceSettings.load(player).setForcePlacement(player, false);
         helper.assertTrue(!ForcedPlacement.use(player, hit), "disabled mode must yield to vanilla");
      });
   }
}
