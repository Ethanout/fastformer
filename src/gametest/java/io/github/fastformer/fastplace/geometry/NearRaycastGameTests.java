package io.github.fastformer.fastplace.geometry;

import io.github.fastformer.FastFormer;
import io.github.fastformer.fastplace.geometry.raycast.LongRangeBlockRaycast;
import io.github.fastformer.fastplace.geometry.raycast.SelectionTargetShape;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(FastFormer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class NearRaycastGameTests {
   private NearRaycastGameTests() { }

   @GameTest(template = "fastformergametests.empty", batch = "reach_raycast")
   public static void selectionUsesFluidHeightAndAltPassesThrough(GameTestHelper helper) {
      BlockPos fluidPos = helper.absolutePos(new BlockPos(2, 3, 3));
      BlockPos backdrop = fluidPos.south(3);
      var level = helper.getLevel();
      level.setBlock(backdrop, Blocks.STONE.defaultBlockState(), 2);
      var player = helper.makeMockServerPlayerInLevel();
      var view = new Vec3(0, 0, 1);
      for (var block : java.util.List.of(Blocks.WATER, Blocks.LAVA)) {
         for (int amount : new int[]{0, 5}) {
            level.setBlock(fluidPos, block.defaultBlockState().setValue(net.minecraft.world.level.block.LiquidBlock.LEVEL, amount), 2);
            double height = level.getFluidState(fluidPos).getHeight(level, fluidPos);
            var shape = SelectionTargetShape.resolve(level, fluidPos,
               net.minecraft.world.phys.shapes.CollisionContext.of(player), false);
            helper.assertTrue(Math.abs(shape.max(net.minecraft.core.Direction.Axis.Y) - height) < 1.0E-6,
               "fluid preview must use the actual fluid height");
            Vec3 eye = new Vec3(fluidPos.getX() + 0.5, fluidPos.getY() + height / 2, fluidPos.getZ() - 2);
            var selection = LongRangeBlockRaycast.clipForSelection(level, player, eye, view, false).hit();
            helper.assertTrue(selection.getBlockPos().equals(fluidPos), "selection must stop at source and flowing fluids");
            var previewHit = shape.clip(eye, eye.add(view.scale(10)), fluidPos);
            helper.assertTrue(previewHit != null && previewHit.getLocation().distanceToSqr(selection.getLocation()) < 1.0E-10,
               "preview and selection must hit the same fluid surface");
            for (var pass : java.util.List.of(
               LongRangeBlockRaycast.clipForSelection(level, player, eye, view, true).hit(),
               LongRangeBlockRaycast.clip(level, player, eye, view).hit(),
               LongRangeBlockRaycast.clipForPlacement(level, player, eye, view).hit(),
               LongRangeBlockRaycast.clipForReachTransition(level, player, eye, view).hit())) {
               helper.assertTrue(pass.getBlockPos().equals(backdrop), "Alt and non-selection rays must pass through fluid");
            }
            Vec3 aboveSurface = eye.add(0, (1.0 + height) / 2 - height / 2, 0);
            helper.assertTrue(LongRangeBlockRaycast.clipForSelection(level, player, aboveSurface, view, false)
               .hit().getBlockPos().equals(backdrop), "selection must pass above a shallow fluid surface");
            helper.assertTrue(SelectionTargetShape.resolve(level, fluidPos,
               net.minecraft.world.phys.shapes.CollisionContext.of(player), true).isEmpty(),
               "Alt must remove the fluid target outline");
         }
      }
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", batch = "reach_raycast")
   public static void selectionHitsGrassWhileBuildingSkipsIt(GameTestHelper helper) {
      BlockPos grass = helper.absolutePos(new BlockPos(2, 3, 3));
      helper.getLevel().setBlock(grass, Blocks.SHORT_GRASS.defaultBlockState(), 2);
      helper.getLevel().setBlock(grass.south(3), Blocks.STONE.defaultBlockState(), 2);
      Vec3 eye = new Vec3(grass.getX() + 0.5, grass.getY() + 0.5, grass.getZ() - 2);
      var player = helper.makeMockServerPlayerInLevel();
      var view = new Vec3(0, 0, 1);
      helper.assertTrue(LongRangeBlockRaycast.clip(helper.getLevel(), player, eye, view).hit().getBlockPos().equals(grass),
         "selection ray must hit the grass cell");
      helper.assertTrue(LongRangeBlockRaycast.clipForPlacement(helper.getLevel(), player, eye, view).hit().getBlockPos().equals(grass.south(3)),
         "building ray must retain replaceable-block behavior");
      helper.succeed();
   }

   @GameTest(template = "fastformergametests.empty", batch = "reach_raycast")
   public static void reachAssistanceDoesNotChangeActionTargets(GameTestHelper helper) {
      BlockPos fence = helper.absolutePos(new BlockPos(2, 3, 3));
      helper.getLevel().setBlock(fence, Blocks.OAK_FENCE.defaultBlockState(), 2);
      helper.getLevel().setBlock(fence.south(3), Blocks.STONE.defaultBlockState(), 2);
      helper.getLevel().setBlock(fence.south(3).east(), Blocks.STONE.defaultBlockState(), 2);
      var player = helper.makeMockServerPlayerInLevel();
      var view = new Vec3(0, 0, 1);
      // One ray crosses only the full cell; the other crosses only the expanded cell.
      for (double x : new double[]{0.7, 1.1}) {
         Vec3 eye = new Vec3(fence.getX() + x, fence.getY() + 0.8, fence.getZ() - 2);
         BlockPos backdrop = x < 1 ? fence.south(3) : fence.south(3).east();
         var gate = LongRangeBlockRaycast.clipForReachTransition(helper.getLevel(), player, eye, view).hit();
         helper.assertTrue(gate.getBlockPos().equals(fence), "reach transition must detect the nearby fence");
         var vanilla = helper.getLevel().clip(new net.minecraft.world.level.ClipContext(eye, eye.add(view.scale(10)),
            net.minecraft.world.level.ClipContext.Block.OUTLINE, net.minecraft.world.level.ClipContext.Fluid.NONE, player));
         helper.assertTrue(vanilla.getBlockPos().equals(backdrop), "vanilla ray must pass beside the fence shape");
         for (var actual : java.util.List.of(
            LongRangeBlockRaycast.clip(helper.getLevel(), player, eye, view).hit(),
            LongRangeBlockRaycast.clipForPlacement(helper.getLevel(), player, eye, view).hit())) {
            helper.assertTrue(actual.getBlockPos().equals(vanilla.getBlockPos())
               && actual.getDirection() == vanilla.getDirection()
               && actual.getLocation().distanceToSqr(vanilla.getLocation()) < 1.0E-10,
               "action ray must retain the vanilla shape hit");
         }
      }
      helper.succeed();
   }
}
