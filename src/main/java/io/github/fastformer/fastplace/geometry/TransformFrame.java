package io.github.fastformer.fastplace.geometry;

import net.minecraft.world.phys.Vec3;

public record TransformFrame(Vec3 origin, Vec3 xAxis, Vec3 yAxis, Vec3 zAxis, Kind kind) {
   private static final double EPSILON = 1.0E-7;

   public TransformFrame {
      origin = origin == null ? Vec3.ZERO : origin;
      xAxis = normalizeOr(xAxis, new Vec3(1.0, 0.0, 0.0));
      yAxis = normalizeOr(yAxis, new Vec3(0.0, 1.0, 0.0));
      zAxis = normalizeOr(zAxis, new Vec3(0.0, 0.0, 1.0));
      kind = kind == null ? Kind.WORLD : kind;
   }

   public static TransformFrame world(Vec3 origin) {
      return new TransformFrame(origin, new Vec3(1.0, 0.0, 0.0), new Vec3(0.0, 1.0, 0.0), new Vec3(0.0, 0.0, 1.0), Kind.WORLD);
   }

   public static TransformFrame local(Vec3 origin, Vec3 xAxis, Vec3 yAxis, Vec3 zAxis) {
      return new TransformFrame(origin, xAxis, yAxis, zAxis, Kind.LOCAL);
   }

   public Vec3 axis(AxisGizmo.Axis axis) {
      return switch (axis) {
         case X -> this.xAxis;
         case Y -> this.yAxis;
         case Z -> this.zAxis;
      };
   }

   private static Vec3 normalizeOr(Vec3 value, Vec3 fallback) {
      if (value == null || value.lengthSqr() < EPSILON) {
         return fallback;
      }
      return value.normalize();
   }

   public enum Kind {
      WORLD,
      LOCAL
   }
}
