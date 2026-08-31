package io.github.fastformer.client.operation.clipboard;

import io.github.fastformer.client.operation.selection.OccupiedBlockBounds;
import io.github.fastformer.fastplace.geometry.AxisGizmo;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/** Stable placement offsets for active-workspace and empty-session paste. */
public final class PastePlacement {
   private PastePlacement() {
   }

   public static Vec3 inWorkspace(OccupiedBlockBounds bounds) {
      AxisGizmo.Axis axis = bounds.smallestAxis();
      int width = bounds.width(axis);
      return switch (axis) {
         case X -> new Vec3(width, 0.0, 0.0);
         case Y -> new Vec3(0.0, width, 0.0);
         case Z -> new Vec3(0.0, 0.0, width);
      };
   }

   public static Vec3 atSurface(OccupiedBlockBounds bounds, Vec3 hit, Direction face) {
      Vec3 center = bounds.center();
      Vec3 anchor = switch (face) {
         case EAST -> new Vec3(bounds.min().getX(), center.y, center.z);
         case WEST -> new Vec3(bounds.max().getX() + 1.0, center.y, center.z);
         case UP -> new Vec3(center.x, bounds.min().getY(), center.z);
         case DOWN -> new Vec3(center.x, bounds.max().getY() + 1.0, center.z);
         case SOUTH -> new Vec3(center.x, center.y, bounds.min().getZ());
         case NORTH -> new Vec3(center.x, center.y, bounds.max().getZ() + 1.0);
      };
      return hit.subtract(anchor);
   }
}
