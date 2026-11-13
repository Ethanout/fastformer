package io.github.fastformer.fastplace.geometry;

import io.github.fastformer.core.math.Axis;
import io.github.fastformer.core.math.Epsilon;
import io.github.fastformer.common.math.MinecraftVectors;

import net.minecraft.world.phys.Vec3;

public record TransformFrame(Vec3 origin, Vec3 xAxis, Vec3 yAxis, Vec3 zAxis, Kind kind) {
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

   public Vec3 axis(Axis axis) {
      return switch (axis) {
         case X -> this.xAxis;
         case Y -> this.yAxis;
         case Z -> this.zAxis;
      };
   }

   private static Vec3 normalizeOr(Vec3 value, Vec3 fallback) {
      if (value == null || value.lengthSqr() < Epsilon.DIRECTION_LENGTH_SQUARED) {
         return fallback;
      }
      return MinecraftVectors.normalizeDirection(value);
   }

   public enum Kind {
      WORLD,
      LOCAL
   }
}
