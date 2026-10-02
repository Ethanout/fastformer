package io.github.fastformer.client.render.geometry;

import net.minecraft.world.phys.Vec3;

/** Stable drawing offsets. Shared corners use the same seed on every incident edge. */
final class BoilJitter {
   private BoilJitter() { }

   static Vec3 cornerOffset(Vec3 corner, int sheet, double worldPerPx, double jitterPx) {
      if (sheet == 0 || jitterPx <= 0) return Vec3.ZERO;
      long seed = seed(corner, sheet);
      return new Vec3(signed(seed), signed(seed + 0x9e3779b97f4a7c15L), signed(seed + 0x3c6ef372fe94f82aL))
         .scale(jitterPx * worldPerPx);
   }

   static double bow(Vec3 from, Vec3 to, int sheet) {
      return sheet == 0 ? 0 : signed(seed(from.add(to).scale(0.5), sheet));
   }

   private static long seed(Vec3 point, int sheet) {
      return mix(Math.round(point.x * 4096)) ^ Long.rotateLeft(mix(Math.round(point.y * 4096)), 21)
         ^ Long.rotateLeft(mix(Math.round(point.z * 4096)), 42) ^ mix(sheet);
   }

   private static double signed(long seed) { return (mix(seed) >>> 11) * 0x1.0p-52 - 1; }

   static long mix(long value) {
      value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
      value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
      return value ^ (value >>> 31);
   }
}
