package io.github.fastformer.client.render.geometry;

import net.minecraft.world.phys.Vec3;

/** Stable long/short pairs, independent of which endpoint the caller visits first. */
record PencilStrokeEnds(double start, double end) {
   static PencilStrokeEnds forLine(Vec3 from, Vec3 to, double longMin, double longMax, double shortMin, double shortMax) {
      return forLine(from, to, longMin, longMax, shortMin, shortMax, 0, 0);
   }

   static PencilStrokeEnds forLine(Vec3 from, Vec3 to, double longMin, double longMax, double shortMin, double shortMax,
      int sheet, double variation) {
      Vec3 delta = to.subtract(from);
      int axis = Math.abs(delta.x) >= Math.abs(delta.y) && Math.abs(delta.x) >= Math.abs(delta.z) ? 0
         : Math.abs(delta.y) >= Math.abs(delta.z) ? 1 : 2;
      double component = axis == 0 ? delta.x : axis == 1 ? delta.y : delta.z;
      // Alternate the preferred end by axis. Three-edge corners may retain an exception.
      boolean longAtStart = (component >= 0) == (axis % 2 == 0);
      Vec3 middle = from.add(to).scale(0.5);
      long seed = mix(Math.round(middle.x * 4096)) ^ Long.rotateLeft(mix(Math.round(middle.y * 4096)), 21)
         ^ Long.rotateLeft(mix(Math.round(middle.z * 4096)), 42) ^ mix(axis + 1);
      double random = (mix(seed) >>> 11) * 0x1.0p-53;
      double scale = Math.min(1, delta.length());
      if (sheet > 0) {
         double sheetRandom = (mix(seed ^ mix(sheet)) >>> 11) * 0x1.0p-53;
         scale *= 1 + Math.clamp(variation, 0, 1) * (2 * sheetRandom - 1);
      }
      double longEnd = (longMin + Math.max(0, longMax - longMin) * random) * scale;
      double shortEnd = (shortMin + Math.max(0, shortMax - shortMin) * random) * scale;
      return longAtStart ? new PencilStrokeEnds(longEnd, shortEnd) : new PencilStrokeEnds(shortEnd, longEnd);
   }

   private static long mix(long value) {
      value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
      value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
      return value ^ (value >>> 31);
   }
}
