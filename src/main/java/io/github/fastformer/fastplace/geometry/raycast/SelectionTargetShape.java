package io.github.fastformer.fastplace.geometry.raycast;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Matches the block outline and actual fluid volume used by the selection ray. */
public final class SelectionTargetShape {
   private SelectionTargetShape() { }

   public static VoxelShape resolve(BlockGetter level, BlockPos position, CollisionContext context, boolean throughFluids) {
      var state = level.getBlockState(position);
      var outline = state.getShape(level, position, context);
      var fluid = state.getFluidState();
      return throughFluids || fluid.isEmpty() ? outline : Shapes.or(outline, fluid.getShape(level, position));
   }
}
