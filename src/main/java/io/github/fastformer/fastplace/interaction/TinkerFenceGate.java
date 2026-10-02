package io.github.fastformer.fastplace.interaction;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;

/** The face center returns to vanilla; edge strips edit the gate geometry. */
final class TinkerFenceGate {
   private static final double EDGE_WIDTH = 0.125;
   private static final double EPSILON = 1.0E-7;

   private TinkerFenceGate() {}

   static BlockState apply(BlockState state, BlockHitResult hit, AABB shape) {
      Direction face = hit.getDirection();
      if (face.getAxis().isVertical()) return state.cycle(BlockStateProperties.IN_WALL);
      Direction facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
      if (face.getAxis() != facing.getAxis()) return state.setValue(BlockStateProperties.HORIZONTAL_FACING, face);
      var local = hit.getLocation().subtract(hit.getBlockPos().getX(), hit.getBlockPos().getY(), hit.getBlockPos().getZ());
      if (local.y - shape.minY <= EDGE_WIDTH + EPSILON || shape.maxY - local.y <= EDGE_WIDTH + EPSILON) {
         return state.cycle(BlockStateProperties.IN_WALL);
      }
      double across = facing.getAxis() == Direction.Axis.Z ? local.x : local.z;
      if (across <= EDGE_WIDTH + EPSILON || across >= 1 - EDGE_WIDTH - EPSILON) {
         Direction side = facing.getAxis() == Direction.Axis.Z
            ? (across < 0.5 ? Direction.WEST : Direction.EAST)
            : (across < 0.5 ? Direction.NORTH : Direction.SOUTH);
         return state.setValue(BlockStateProperties.HORIZONTAL_FACING, side);
      }
      return null;
   }
}
