package io.github.fastformer.common.geometry.session;

import io.github.fastformer.common.geometry.GeometryMode;
import io.github.fastformer.common.geometry.GeometryNumbers;
import io.github.fastformer.common.geometry.GeometryPoint;
import net.minecraft.world.phys.Vec3;

public final class ConvexPolyhedronGeometryState extends GeometryShapeState {
   int selectedPointIndex = -1;

   ConvexPolyhedronGeometryState() {
      super(GeometryMode.CONVEX_POLYHEDRON);
   }

   @Override void beforePointChange() { selectedPointIndex = -1; }

   public int selectedControlPoint() {
      return this.selectedPointIndex >= 0 && this.selectedPointIndex < this.points().size() ? this.selectedPointIndex : -1;
   }

   public void selectControlPoint(int index) {
      this.selectedPointIndex = index >= 0 && index < this.points().size() ? index : -1;
   }

   public void clearSelectedControlPoint() {
      this.selectedPointIndex = -1;
   }

   public void moveSelectedPoint(Vec3 delta) {
      if (delta == null
         || !GeometryNumbers.finite(delta.x, delta.y, delta.z)) {
         return;
      }
      int index = this.selectedControlPoint();
      if (index < 0) {
         return;
      }
      GeometryPoint point = this.points().get(index);
      Vec3 location = point.location().add(delta);
      this.points().set(index, point.withLocation(new Vec3(snappedHalf(location.x), snappedHalf(location.y), snappedHalf(location.z))));
   }

   private static double snappedHalf(double value) {
      return GeometryNumbers.cleanZero(Math.round(value * 2.0) * 0.5);
   }
}
