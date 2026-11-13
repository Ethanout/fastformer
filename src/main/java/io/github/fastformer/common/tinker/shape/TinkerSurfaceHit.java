package io.github.fastformer.common.tinker.shape;

import java.util.function.Predicate;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Shared block-local edge distances; ties retain the current surface action. */
public final class TinkerSurfaceHit {
   static final double EDGE_WIDTH = 0.125;
   private static final double EPSILON = 1.0E-7;

   private TinkerSurfaceHit() {}

   public static Vec3 local(BlockHitResult hit) {
      return hit.getLocation().subtract(Vec3.atLowerCornerOf(hit.getBlockPos()));
   }

   public static double distance(Vec3 point, Direction side) {
      double coordinate = side.getAxis().choose(point.x, point.y, point.z);
      return Math.abs(side.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1 - coordinate : coordinate);
   }

   public static Direction edge(BlockHitResult hit, Direction surface, Predicate<Direction> allowed) {
      Vec3 point = local(hit);
      Direction nearest = null;
      double distance = EDGE_WIDTH + EPSILON;
      boolean tied = false;
      for (Direction side : Direction.values()) {
         if (side.getAxis() == surface.getAxis() || !allowed.test(side)) continue;
         double candidate = distance(point, side);
         if (candidate <= EDGE_WIDTH + EPSILON && (nearest == null || candidate < distance - EPSILON)) {
            nearest = side;
            distance = candidate;
            tied = false;
         } else if (nearest != null && Math.abs(candidate - distance) <= EPSILON) {
            tied = true;
         }
      }
      return tied ? null : nearest;
   }

   public static Direction sideOperation(BlockHitResult hit) {
      Direction edge = edge(hit, hit.getDirection(), direction -> direction.getAxis().isHorizontal());
      return edge == null ? hit.getDirection() : edge;
   }

   public static Direction nearestSide(BlockHitResult hit) {
      Direction nearest = hit.getDirection();
      double best = distance(local(hit), nearest);
      for (Direction side : Direction.values()) {
         double candidate = distance(local(hit), side);
         if (candidate < best - EPSILON) {
            best = candidate;
            nearest = side;
         }
      }
      return nearest;
   }
}
