package io.github.fastformer.common.math;

import net.minecraft.world.phys.Vec3;

/** Closest point on an infinite axis to the forward portion of a ray. */
public final class RayAxisProjection {
   private static final double PARALLEL_EPSILON = 1.0E-7;

   private RayAxisProjection() { }

   public static Vec3 closestPoint(Vec3 axisOrigin, Vec3 axis, Vec3 eye, Vec3 view) {
      Vec3 localAxis = MinecraftVectors.normalize(axis);
      Vec3 localView = MinecraftVectors.normalize(view);
      double parallel = localAxis.dot(localView);
      double denominator = 1.0 - parallel * parallel;
      Vec3 eyeFromAxis = eye.subtract(axisOrigin);
      if (denominator < PARALLEL_EPSILON) {
         return axisOrigin.add(localAxis.scale(eyeFromAxis.dot(localAxis)));
      }
      double rayDistance = (parallel * localAxis.dot(eyeFromAxis) - localView.dot(eyeFromAxis)) / denominator;
      Vec3 rayPoint = eye.add(localView.scale(Math.max(0.0, rayDistance)));
      return axisOrigin.add(localAxis.scale(rayPoint.subtract(axisOrigin).dot(localAxis)));
   }
}
