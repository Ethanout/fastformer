package io.github.fastformer.fastplace.geometry.movement;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Sweeps the whole player box along one straight path, without sliding around obstacles. */
public final class ForwardDashPath {
   private static final double EPSILON = 1.0E-7;
   private static final double WALL_CLEARANCE = 0.01;

   private ForwardDashPath() { }

   public static double clearFraction(AABB box, Vec3 movement, Iterable<VoxelShape> obstacles) {
      double fraction = 1.0;
      for (var shape : obstacles) {
         for (AABB obstacle : shape.toAabbs()) fraction = Math.min(fraction, collisionFraction(box, movement, obstacle));
      }
      return fraction < 1.0 ? Math.max(0.0, fraction - WALL_CLEARANCE / Math.max(EPSILON, movement.length())) : 1.0;
   }

   static double collisionFraction(AABB box, Vec3 movement, AABB obstacle) {
      double enter = 0.0;
      double exit = 1.0;
      for (var axis : net.minecraft.core.Direction.Axis.values()) {
         double speed = movement.get(axis);
         if (Math.abs(speed) < EPSILON) {
            // Merely touching a floor or wall does not block travel parallel to it.
            if (box.max(axis) <= obstacle.min(axis) + EPSILON || box.min(axis) >= obstacle.max(axis) - EPSILON) return 1.0;
            continue;
         }
         double first = (obstacle.min(axis) - box.max(axis)) / speed;
         double last = (obstacle.max(axis) - box.min(axis)) / speed;
         enter = Math.max(enter, Math.min(first, last));
         exit = Math.min(exit, Math.max(first, last));
         if (enter >= exit) return 1.0;
      }
      return exit > EPSILON ? enter : 1.0;
   }
}
