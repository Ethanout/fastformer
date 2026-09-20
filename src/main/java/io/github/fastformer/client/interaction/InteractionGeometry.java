package io.github.fastformer.client.interaction;

import java.util.OptionalDouble;
import net.minecraft.world.phys.Vec3;

public final class InteractionGeometry {
   private InteractionGeometry() { }

   /** Returns the closest ray distance to the world-space anchor within the pick tolerance. */
   public static OptionalDouble hitDistance(InteractionObject object, Vec3 eye, Vec3 view, double reach) {
      Vec3 anchor = object.require(InteractionComponents.ANCHOR);
      double radius = object.require(InteractionComponents.PICK_SPHERE).radius();
      if (!finite(anchor) || !finite(eye) || !finite(view) || !Double.isFinite(reach) || reach < 0.0) {
         return OptionalDouble.empty();
      }
      double length = view.length();
      if (!Double.isFinite(length) || length < 1.0E-12) return OptionalDouble.empty();
      Vec3 direction = view.scale(1.0 / length);
      double distance = Math.max(0.0, anchor.subtract(eye).dot(direction));
      return distance <= reach && eye.add(direction.scale(distance)).distanceToSqr(anchor) <= radius * radius
         ? OptionalDouble.of(distance) : OptionalDouble.empty();
   }

   private static boolean finite(Vec3 point) {
      return point != null && Double.isFinite(point.x) && Double.isFinite(point.y) && Double.isFinite(point.z);
   }
}
