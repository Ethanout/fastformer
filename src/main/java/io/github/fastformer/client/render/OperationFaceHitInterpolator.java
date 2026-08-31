package io.github.fastformer.client.render;

import io.github.fastformer.fastplace.geometry.OperationGeometry;
import net.minecraft.world.phys.Vec3;

public final class OperationFaceHitInterpolator {
   private static final double EPSILON = 1.0E-7;
   private final long transitionNanos;
   private OperationGeometry.RayHit value;
   private long updatedAt = Long.MIN_VALUE;

   public OperationFaceHitInterpolator(long transitionNanos) {
      this.transitionNanos = Math.max(1L, transitionNanos);
   }

   public OperationGeometry.RayHit update(OperationGeometry.RayHit target, long nowNanos) {
      if (target == null) {
         this.reset();
         return null;
      }
      if (this.value == null || this.updatedAt == Long.MIN_VALUE || nowNanos < this.updatedAt) {
         this.value = target;
         this.updatedAt = nowNanos;
         return target;
      }

      Vec3 previousNormal = normalized(this.value.normal());
      Vec3 targetNormal = normalized(target.normal());
      if (this.value.axis() != target.axis() || previousNormal.dot(targetNormal) < 1.0 - EPSILON) {
         this.value = target;
         this.updatedAt = nowNanos;
         return target;
      }

      double amount = Math.clamp((double)(nowNanos - this.updatedAt) / (double)this.transitionNanos, 0.0, 1.0);
      Vec3 pointDelta = target.point().subtract(this.value.point());
      Vec3 normalDelta = targetNormal.scale(pointDelta.dot(targetNormal));
      Vec3 tangentDelta = pointDelta.subtract(normalDelta);
      Vec3 point = this.value.point().add(normalDelta).add(tangentDelta.scale(amount));
      this.value = new OperationGeometry.RayHit(
         point,
         target.normal(),
         this.value.distance() + (target.distance() - this.value.distance()) * amount,
         target.axis()
      );
      this.updatedAt = nowNanos;
      return this.value;
   }

   private static Vec3 normalized(Vec3 value) {
      return value.lengthSqr() < EPSILON ? Vec3.ZERO : value.normalize();
   }

   public void reset() {
      this.value = null;
      this.updatedAt = Long.MIN_VALUE;
   }
}
