package io.github.fastformer.client.render;

import io.github.fastformer.fastplace.geometry.OperationGeometry;
import net.minecraft.world.phys.Vec3;

public final class OperationFaceHitInterpolator {
   private static final double EPSILON = 1.0E-7;
   private final LinearPositionSmoother position;
   private OperationGeometry.RayHit previous;
   private Object owner;

   public OperationFaceHitInterpolator(long transitionNanos) {
      this.position = new LinearPositionSmoother(transitionNanos);
   }

   public OperationGeometry.RayHit update(OperationGeometry.RayHit target, long nowNanos) {
      return update(target, nowNanos, null);
   }

   public OperationGeometry.RayHit update(OperationGeometry.RayHit target, long nowNanos, Object owner) {
      if (target == null) {
         this.reset();
         return null;
      }
      Vec3 normal = target.normal().normalize();
      if (!java.util.Objects.equals(this.owner, owner) || previous != null &&
         (previous.axis() != target.axis() || previous.normal().normalize().dot(normal) < 1.0 - EPSILON)) {
         position.reset();
      }
      this.owner = owner;
      previous = target;
      Vec3 smoothed = position.update(target.point(), nowNanos);
      Vec3 point = smoothed.add(normal.scale(target.point().subtract(smoothed).dot(normal)));
      return new OperationGeometry.RayHit(point, target.normal(), target.distance(), target.axis());
   }

   public void reset() {
      position.reset();
      previous = null;
      owner = null;
   }
}
