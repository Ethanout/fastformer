package io.github.fastformer.fastplace;

import io.github.fastformer.FastFormer;
import io.github.fastformer.fastplace.placement.ForcedPlacement;
import io.github.fastformer.fastplace.settings.FastPlaceSettings;
import io.github.fastformer.fastplace.settings.PlayerReachAttributes;
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
      PlayerReachAttributes.initialize(player);
      helper.assertTrue(Math.abs(player.blockInteractionRange() - 20) < 1.0E-6, "block reach must be 20");
      helper.assertTrue(Math.abs(player.entityInteractionRange() - 20) < 1.0E-6, "entity reach must be 20");
      var blockRange = player.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.BLOCK_INTERACTION_RANGE);
      blockRange.setBaseValue(15.5);
      PlayerReachAttributes.initialize(player);
      helper.assertTrue(Math.abs(player.blockInteractionRange() - 16) < 1.0E-6,
         "initialization must not overwrite later attribute changes");
      PlayerReachAttributes.set(player, 20);
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
