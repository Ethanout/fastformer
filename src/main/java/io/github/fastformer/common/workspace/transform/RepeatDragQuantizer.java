package io.github.fastformer.common.workspace.transform;

import io.github.fastformer.core.math.Axis;

import io.github.fastformer.common.workspace.model.ClientSelectionPart;
import io.github.fastformer.common.workspace.selection.OccupiedBlockBounds;
import io.github.fastformer.common.selection.OperationSelectionMode;
import net.minecraft.world.phys.AABB;

/** Converts scale-handle travel into whole structure-sized repeat steps. */
public final class RepeatDragQuantizer {
   private RepeatDragQuantizer() {
   }

   public static int structureExtent(ClientSelectionPart part, Axis axis) {
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

   private static double boundsExtent(AABB bounds, Axis axis) {
      return switch (axis) {
         case X -> bounds.maxX - bounds.minX;
         case Y -> bounds.maxY - bounds.minY;
         case Z -> bounds.maxZ - bounds.minZ;
      };
   }
}
