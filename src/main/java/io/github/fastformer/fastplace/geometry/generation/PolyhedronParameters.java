package io.github.fastformer.fastplace.geometry.generation;

import io.github.fastformer.fastplace.geometry.GeometryNumbers;
import io.github.fastformer.fastplace.geometry.TransformFrame;
import java.util.List;
import net.minecraft.world.phys.Vec3;

public record PolyhedronParameters(
   Vec3 center,
   Vec3 radiusPoint,
   int shapeVariant,
   double[] rotation,
   Vec3 localScale,
   Vec3 worldScale,
   boolean gizmoLocal
) {
   public PolyhedronParameters {
      center = finiteOrNull(center);
      radiusPoint = finiteOrNull(radiusPoint);
      rotation = rotation == null ? null : rotation.clone();
      localScale = cleanScale(localScale);
      worldScale = cleanScale(worldScale);
   }

   public PolyhedronParameters(Vec3 center, Vec3 radiusPoint, int shapeVariant, double[] rotation) {
      this(center, radiusPoint, shapeVariant, rotation, new Vec3(1.0, 1.0, 1.0), new Vec3(1.0, 1.0, 1.0), false);
   }

   public boolean ready() {
      return this.center != null && this.radiusPoint != null && this.center.distanceToSqr(this.radiusPoint) > 1.0E-12;
   }

   public double radius(double fallback) {
      return this.ready() ? Math.max(0.5, this.center.distanceTo(this.radiusPoint)) : fallback;
   }

   public List<Vec3> points() {
      if (this.center == null) {
         return List.of();
      }
      return this.radiusPoint == null ? List.of(this.center) : List.of(this.center, this.radiusPoint);
   }

   public double maxScale() {
      return maxComponent(this.localScale) * maxComponent(this.worldScale);
   }

   public TransformFrame gizmoFrame() {
      Vec3 origin = this.center == null ? Vec3.ZERO : this.center;
      if (!this.gizmoLocal || this.rotation == null || this.rotation.length != 9) {
         return TransformFrame.world(origin);
      }
      return TransformFrame.local(
         origin,
         new Vec3(this.rotation[0], this.rotation[3], this.rotation[6]),
         new Vec3(this.rotation[1], this.rotation[4], this.rotation[7]),
         new Vec3(this.rotation[2], this.rotation[5], this.rotation[8])
      );
   }

   private static Vec3 finiteOrNull(Vec3 value) {
      return value == null || !GeometryNumbers.finite(value.x, value.y, value.z) ? null : value;
   }

   private static Vec3 cleanScale(Vec3 value) {
      Vec3 finite = value == null
         ? new Vec3(1.0, 1.0, 1.0)
         : new Vec3(
            GeometryNumbers.finiteOr(value.x, 1.0),
            GeometryNumbers.finiteOr(value.y, 1.0),
            GeometryNumbers.finiteOr(value.z, 1.0)
         );
      return new Vec3(
         Math.clamp(finite.x, 0.125, 8.0),
         Math.clamp(finite.y, 0.125, 8.0),
         Math.clamp(finite.z, 0.125, 8.0)
      );
   }

   private static double maxComponent(Vec3 value) {
      return Math.max(value.x, Math.max(value.y, value.z));
   }
}
