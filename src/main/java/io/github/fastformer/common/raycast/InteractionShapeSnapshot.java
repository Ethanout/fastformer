package io.github.fastformer.common.raycast;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.phys.shapes.CollisionContext;

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
      return capture(level, position, false);
   }

   public static InteractionShapeSnapshot capture(BlockGetter level, BlockPos position, boolean throughFluids) {
      return capture(level, position, throughFluids, CollisionContext.empty());
   }

   public static InteractionShapeSnapshot capture(BlockGetter level, BlockPos position, boolean throughFluids, CollisionContext context) {
      var state = level.getBlockState(position);
      VoxelShape shape = state.getShape(level, position, context);
      if (!throughFluids && !state.getFluidState().isEmpty()) shape = Shapes.or(shape, state.getFluidState().getShape(level, position));
      return new InteractionShapeSnapshot(position, shape.toAabbs());
   }

   public BlockHitResult clip(Vec3 eye, Vec3 view) {
      VoxelShape shape = Shapes.empty();
      for (AABB box : boxes) shape = Shapes.or(shape, Shapes.create(box));
      double distance = eye.distanceTo(Vec3.atCenterOf(position)) + 4;
      return shape.clip(eye, eye.add(view.scale(distance)), position);
   }

   /** Exact hits win. A padded miss is projected back onto the original box, never the enlarged surface. */
   public BlockHitResult clip(Vec3 eye, Vec3 view, double padding) {
      var exact = clip(eye, view);
      if (exact != null || padding <= 0) return exact;
      Vec3 end = eye.add(view.scale(eye.distanceTo(Vec3.atCenterOf(position)) + 4 + padding));
      BlockHitResult nearest = null;
      double nearestDistance = Double.POSITIVE_INFINITY;
      for (AABB box : boxes) {
         var hit = Shapes.create(box.inflate(padding)).clip(eye, end, position);
         if (hit == null) continue;
         double distance = eye.distanceToSqr(hit.getLocation());
         if (distance >= nearestDistance) continue;
         nearestDistance = distance;
         AABB worldBox = box.move(position);
         Vec3 point = hit.getLocation();
         Vec3 projected = new Vec3(
            net.minecraft.util.Mth.clamp(point.x, worldBox.minX, worldBox.maxX),
            net.minecraft.util.Mth.clamp(point.y, worldBox.minY, worldBox.maxY),
            net.minecraft.util.Mth.clamp(point.z, worldBox.minZ, worldBox.maxZ));
         nearest = new BlockHitResult(projected, hit.getDirection(), position, hit.isInside());
      }
      return nearest;
   }
   private static boolean finite(double value) { return Double.isFinite(value) && Math.abs(value) <= 64; }
}
