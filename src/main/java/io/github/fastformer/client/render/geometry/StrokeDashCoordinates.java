package io.github.fastformer.client.render.geometry;

import net.minecraft.world.phys.Vec3;

/** Dash distances belong to the original edge, independent of drawing-sheet offsets. */
record StrokeDashCoordinates(double length, boolean reversed) {
   static StrokeDashCoordinates forLine(Vec3 from, Vec3 to) {
      Vec3 delta = to.subtract(from);
      double component = Math.abs(delta.x) >= Math.abs(delta.y) && Math.abs(delta.x) >= Math.abs(delta.z) ? delta.x
         : Math.abs(delta.y) >= Math.abs(delta.z) ? delta.y : delta.z;
      return new StrokeDashCoordinates(delta.length(), component < 0);
   }

   double at(double fraction) { return (reversed ? 1 - fraction : fraction) * length; }
}
