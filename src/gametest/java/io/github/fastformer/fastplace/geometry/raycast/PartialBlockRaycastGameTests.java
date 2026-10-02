package io.github.fastformer.fastplace.geometry.raycast;

import io.github.fastformer.FastFormer;
import io.github.fastformer.fastplace.placement.context.PlacementContextSnapshot;
import io.github.fastformer.server.session.FastPlaceManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(FastFormer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PartialBlockRaycastGameTests {
   private PartialBlockRaycastGameTests() {}

   @GameTest(template = "fastformergametests.empty", batch = "placement_context")
   public static void grazingFarmlandUsesActualShapeTopFace(GameTestHelper helper) {
      assertTopPlacement(helper, Blocks.FARMLAND.defaultBlockState(), Blocks.STONE.defaultBlockState(), false);
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", batch = "placement_context")
   public static void grazingCropsUsesActualShapeTopFace(GameTestHelper helper) {
      for (int age : new int[]{0, 3, 7}) {
         assertTopPlacement(helper, Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, age),
            Blocks.STONE.defaultBlockState(), false);
      }
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", batch = "placement_context")
   public static void grazingSlabStillMergesInTheHitCell(GameTestHelper helper) {
      assertTopPlacement(helper, Blocks.OAK_SLAB.defaultBlockState(), Blocks.OAK_SLAB.defaultBlockState(), true);
      helper.succeed();
   }

   private static void assertTopPlacement(
      GameTestHelper helper, BlockState targetState, BlockState heldState, boolean merges
   ) {
      var level = helper.getLevel();
      var player = helper.makeMockServerPlayerInLevel();
      var stack = new ItemStack(heldState.getBlock());
      player.setItemInHand(InteractionHand.MAIN_HAND, stack);
      BlockPos target = helper.absolutePos(new BlockPos(3, 2, 3));
      level.setBlock(target.below(), Blocks.FARMLAND.defaultBlockState(), 2);
      level.setBlock(target, targetState, 2);
      level.setBlock(target.west(), targetState.is(Blocks.FARMLAND) ? targetState : Blocks.AIR.defaultBlockState(), 2);
      level.setBlock(target.above(), Blocks.AIR.defaultBlockState(), 2);
      double height = targetState.getShape(level, target).max(Direction.Axis.Y);
      // These rays enter the cell through its side, then hit the shape's top.
      for (double offset : new double[]{-0.01, 0.0, 0.01}) {
         Vec3 surface = Vec3.atLowerCornerOf(target).add(0.5 + offset, height, 0.5);
         Vec3 start = surface.add(-1.0, 0.1, 0.0);
         Vec3 direction = surface.subtract(start);
         Vec3 end = surface.add(direction);
         var expected = level.clip(new ClipContext(start, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
         helper.assertTrue(expected.getType() == HitResult.Type.BLOCK && expected.getBlockPos().equals(target),
            "test ray missed the target shape");
         helper.assertTrue(expected.getDirection() == Direction.UP, "test ray missed the shape top");
         var actual = LongRangeBlockRaycast.clipForPlacement(level, player, start, direction).hit();
         helper.assertTrue(actual.getBlockPos().equals(expected.getBlockPos()), "placement ray changed the hit block");
         helper.assertTrue(actual.getDirection() == expected.getDirection(), "placement ray changed the actual shape face: " + targetState);
         helper.assertTrue(actual.getLocation().distanceToSqr(expected.getLocation()) < 1.0E-10,
            "placement ray changed the actual hit location");
         var selection = LongRangeBlockRaycast.clip(level, player, start, direction).hit();
         helper.assertTrue(selection.getDirection() == expected.getDirection()
            && selection.getLocation().distanceToSqr(expected.getLocation()) < 1.0E-10,
            "selection ray changed the actual shape hit");
         var snapshot = PlacementContextSnapshot.capture(level, player, stack, actual, false);
         BlockPos expectedPosition = merges ? target : target.above();
         helper.assertTrue(snapshot.placementPosition().equals(expectedPosition), "preview moved into the wrong cell");
         try {
            FastPlaceManager.addInitialPoint(player, actual, false);
            helper.assertTrue(FastPlaceManager.session(player).orElseThrow().points().getFirst().equals(expectedPosition),
               "first point differs from the preview");
         } finally {
            FastPlaceManager.cancel(player);
         }
         try {
            var embedded = PlacementContextSnapshot.capture(level, player, stack, actual, true);
            helper.assertTrue(embedded.clickedFace() == Direction.UP && embedded.hitLocation().equals(actual.getLocation()),
               "embedded placement changed the shape entry face");
            FastPlaceManager.addInitialPoint(player, actual, true);
            helper.assertTrue(FastPlaceManager.session(player).orElseThrow().points().getFirst().equals(target),
               "embedded point moved outside the hit cell");
         } finally {
            FastPlaceManager.cancel(player);
         }
      }
   }
}
