package io.github.fastformer.client.render.model;

import io.github.fastformer.fastplace.geometry.GeometryNumbers;
import java.util.Objects;
import net.minecraft.world.phys.Vec3;

public record TransformStatus(Vec3 position, Vec3 scale, Vec3 rotationDegrees) {
   private static final double EPSILON = 1.0E-7;

   public TransformStatus {
      Objects.requireNonNull(position, "position");
      Objects.requireNonNull(scale, "scale");
      Objects.requireNonNull(rotationDegrees, "rotationDegrees");
   }

   public TransformStatus relativeTo(TransformStatus baseline) {
      Objects.requireNonNull(baseline, "baseline");
      return new TransformStatus(
         position.subtract(baseline.position),
         divide(scale, baseline.scale),
         new Vec3(
            cleanDegreeDelta(rotationDegrees.x - baseline.rotationDegrees.x),
            cleanDegreeDelta(rotationDegrees.y - baseline.rotationDegrees.y),
            cleanDegreeDelta(rotationDegrees.z - baseline.rotationDegrees.z)
         )
      );
   }

   public static Vec3 eulerDegrees(double[] rotation) {
      if (rotation == null || rotation.length != 9) {
         return Vec3.ZERO;
      }
      double y = Math.asin(Math.clamp(-rotation[6], -1.0, 1.0));
      double x;
      double z;
      if (Math.abs(Math.cos(y)) > 1.0E-6) {
         x = Math.atan2(rotation[7], rotation[8]);
         z = Math.atan2(rotation[3], rotation[0]);
      } else {
         x = Math.atan2(-rotation[5], rotation[4]);
         z = 0.0;
      }
      return new Vec3(cleanDegrees(x), cleanDegrees(y), cleanDegrees(z));
   }

   private static Vec3 divide(Vec3 value, Vec3 baseline) {
      return new Vec3(
         value.x / Math.max(EPSILON, baseline.x),
         value.y / Math.max(EPSILON, baseline.y),
         value.z / Math.max(EPSILON, baseline.z)
      );
   }

   private static double cleanDegreeDelta(double value) {
      return GeometryNumbers.cleanZero(Math.IEEEremainder(value, 360.0));
   }

   private static double cleanDegrees(double radians) {
      return GeometryNumbers.cleanZero(Math.IEEEremainder(Math.toDegrees(radians), 360.0));
   }
}
