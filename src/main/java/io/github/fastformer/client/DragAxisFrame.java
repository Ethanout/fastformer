package io.github.fastformer.client;

import net.minecraft.world.phys.Vec3;

/** Keeps a continuous drag total while its direction rule changes. */
record DragAxisFrame(Vec3 origin, int baselineSteps, boolean reversed) {
   DragAxisFrame {
      origin = origin == null ? Vec3.ZERO : origin;
   }

   static DragAxisFrame start(Vec3 origin, boolean reversed) {
      return new DragAxisFrame(origin, 0, reversed);
   }

   DragAxisFrame rebase(Vec3 axisPoint, int sentSteps, boolean reversed) {
      return new DragAxisFrame(axisPoint, sentSteps, reversed);
   }

   int project(Vec3 axisPoint, Vec3 axis) {
      int localSteps = (int)Math.round(axisPoint.subtract(this.origin).dot(axis));
      return this.baselineSteps + (this.reversed ? -localSteps : localSteps);
   }
}
