package io.github.fastformer.fastplace.geometry;

import net.minecraft.world.phys.Vec3;

public enum ControlPointShape {
   BLOCK(0.46, 0.46, 0.46),
   FACE_X(0.07, 0.46, 0.46),
   FACE_Y(0.46, 0.07, 0.46),
   FACE_Z(0.46, 0.46, 0.07),
   EDGE_X(0.46, 0.07, 0.07),
   EDGE_Y(0.07, 0.46, 0.07),
   EDGE_Z(0.07, 0.07, 0.46),
   POINT(0.16, 0.16, 0.16);

   private static final double EPSILON = 1.0E-7;
   private static final Vec3 FULL_BLOCK_HALF_EXTENTS = new Vec3(0.5, 0.5, 0.5);
   private final Vec3 halfExtents;

   ControlPointShape(double x, double y, double z) {
      this.halfExtents = new Vec3(x, y, z);
   }

   public Vec3 halfExtents() {
      return this.halfExtents;
   }

   /** Visual bounds stay independent from the smaller interaction target. */
   public Vec3 visualHalfExtents(Vec3 location) {
      return this == POINT || atBlockCenter(location) ? FULL_BLOCK_HALF_EXTENTS : this.halfExtents;
   }

   public static ControlPointShape at(Vec3 location) {
      boolean xBoundary = onInteger(location.x);
      boolean yBoundary = onInteger(location.y);
      boolean zBoundary = onInteger(location.z);
      int boundaries = (xBoundary ? 1 : 0) + (yBoundary ? 1 : 0) + (zBoundary ? 1 : 0);
      if (boundaries == 0) {
         return BLOCK;
      }
      if (boundaries == 1) {
         return xBoundary ? FACE_X : yBoundary ? FACE_Y : FACE_Z;
      }
      if (boundaries == 2) {
         return !xBoundary ? EDGE_X : !yBoundary ? EDGE_Y : EDGE_Z;
      }
      return POINT;
   }

   private static boolean onInteger(double value) {
      return Math.abs(value - Math.rint(value)) < EPSILON;
   }

   private static boolean atBlockCenter(Vec3 location) {
      return onHalfInteger(location.x) && onHalfInteger(location.y) && onHalfInteger(location.z);
   }

   private static boolean onHalfInteger(double value) {
      return Math.abs(value - (Math.rint(value - 0.5) + 0.5)) < EPSILON;
   }
}
