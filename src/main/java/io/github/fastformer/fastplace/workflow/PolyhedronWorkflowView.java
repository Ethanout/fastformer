package io.github.fastformer.fastplace.workflow;

import io.github.fastformer.fastplace.geometry.GeometryMode;
import io.github.fastformer.fastplace.geometry.GeometryNumbers;
import io.github.fastformer.fastplace.geometry.polyhedron.PolyhedronSizeMode;
import net.minecraft.world.phys.Vec3;

public record PolyhedronWorkflowView(GeometryWorkflowView.Context context, int polyhedronShapeVariant,
   PolyhedronSizeMode polyhedronSizeMode, double[] rotation, Vec3 polyhedronLocalScale,
   Vec3 polyhedronWorldScale, boolean polyhedronGizmoLocal) implements GeometryWorkflowView {
   public PolyhedronWorkflowView {
      polyhedronSizeMode = polyhedronSizeMode == null ? PolyhedronSizeMode.RADIUS : polyhedronSizeMode;
      rotation = rotation == null || rotation.length != 9
         ? new double[]{1, 0, 0, 0, 1, 0, 0, 0, 1} : rotation.clone();
      polyhedronLocalScale = cleanScale(polyhedronLocalScale);
      polyhedronWorldScale = cleanScale(polyhedronWorldScale);
   }
   @Override public double[] rotation() { return rotation.clone(); }
   @Override public GeometryMode mode() { return GeometryMode.POLYHEDRON; }
   private static Vec3 cleanScale(Vec3 value) {
      if (value == null) return new Vec3(1, 1, 1);
      return new Vec3(Math.clamp(GeometryNumbers.finiteOr(value.x, 1), 0.125, 8),
         Math.clamp(GeometryNumbers.finiteOr(value.y, 1), 0.125, 8),
         Math.clamp(GeometryNumbers.finiteOr(value.z, 1), 0.125, 8));
   }
}
