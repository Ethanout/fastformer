package io.github.fastformer.fastplace.interaction;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** An immutable interaction shape, independent of later world changes. */
public record InteractionShapeSnapshot(BlockPos position, List<AABB> boxes) {

   public InteractionShapeSnapshot {
      position = position.immutable();
      boxes = List.copyOf(boxes);
      if (boxes.size() > 256) throw new IllegalArgumentException("Interaction shape has too many boxes");
      for (AABB box : boxes) {
         if (!finite(box.minX) || !finite(box.minY) || !finite(box.minZ)
            || !finite(box.maxX) || !finite(box.maxY) || !finite(box.maxZ)) throw new IllegalArgumentException("Invalid interaction shape");
      }
   }

   public static InteractionShapeSnapshot capture(BlockGetter level, BlockPos position) {
      var state = level.getBlockState(position);
      VoxelShape shape = state.getShape(level, position);
      if (!state.getFluidState().isEmpty()) shape = Shapes.or(shape, state.getFluidState().getShape(level, position));
      return new InteractionShapeSnapshot(position, shape.toAabbs());
   }

   public BlockHitResult clip(Vec3 eye, Vec3 view) {
      VoxelShape shape = Shapes.empty();
      for (AABB box : boxes) shape = Shapes.or(shape, Shapes.create(box));
      double distance = eye.distanceTo(Vec3.atCenterOf(position)) + 4;
      return shape.clip(eye, eye.add(view.scale(distance)), position);
   }
   private static boolean finite(double value) { return Double.isFinite(value) && Math.abs(value) <= 64; }
}
