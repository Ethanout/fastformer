package io.github.fastformer.client.operation;

import io.github.fastformer.fastplace.OperationSelectionMode;
import io.github.fastformer.fastplace.geometry.AxisGizmo;
import net.minecraft.world.phys.AABB;

/** Converts scale-handle travel into whole structure-sized repeat steps. */
public final class RepeatDragQuantizer {
   private RepeatDragQuantizer() {
   }

   public static int copiesForOffset(double offset, int structureExtent) {
      if (!Double.isFinite(offset)) {
         return 0;
      }
      int extent = Math.max(1, structureExtent);
      return extent == 1
         ? offset >= 0.0 ? (int)Math.floor(offset + 0.5) : -(int)Math.floor(-offset + 0.5)
         : (int)(offset / extent);
   }

   public static int structureExtent(ClientSelectionPart part, AxisGizmo.Axis axis) {
      if (part == null || axis == null) {
         return 1;
      }
      double extent = part.selection() != null && part.selection().mode() == OperationSelectionMode.CUBOID
         ? boundsExtent(part.selection().bounds(), axis)
         : OccupiedBlockBounds.from(part.blocks().keySet()).map(bounds -> (double)bounds.width(axis)).orElse(1.0);
      double scale = switch (axis) {
         case X -> part.transform().scale().x;
         case Y -> part.transform().scale().y;
         case Z -> part.transform().scale().z;
      };
      return (int)Math.clamp(Math.round(extent * scale), 1L, (long)Integer.MAX_VALUE);
   }

   private static double boundsExtent(AABB bounds, AxisGizmo.Axis axis) {
      return switch (axis) {
         case X -> bounds.maxX - bounds.minX;
         case Y -> bounds.maxY - bounds.minY;
         case Z -> bounds.maxZ - bounds.minZ;
      };
   }
}
