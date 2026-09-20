package io.github.fastformer.fastplace.session;

import io.github.fastformer.fastplace.GeometryMode;
import io.github.fastformer.fastplace.GeometryPoint;
import io.github.fastformer.fastplace.PolyhedronSizeMode;
import io.github.fastformer.fastplace.geometry.ControlPointRole;
import java.util.List;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.BlockPos;

final class PolyhedronGeometryState extends GeometryShapeState {
   static final double[] IDENTITY_ROTATION = {1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0};
   private List<GeometryPoint> adjustmentBaseline = List.of();
   int shapeVariant;
   double[] rotation = IDENTITY_ROTATION.clone();
   Vec3 localScale = new Vec3(1.0, 1.0, 1.0);
   Vec3 worldScale = new Vec3(1.0, 1.0, 1.0);
   boolean gizmoLocal;
   PolyhedronSizeMode sizeMode = PolyhedronSizeMode.RADIUS;

   PolyhedronGeometryState() {
      super(GeometryMode.POLYHEDRON);
   }

   @Override
   ControlPointRole nextRole() {
      if (this.sizeMode == PolyhedronSizeMode.DIAMETER) {
         return this.points().isEmpty() ? ControlPointRole.DIAMETER_A : ControlPointRole.DIAMETER_B;
      }
      return this.points().isEmpty() ? ControlPointRole.CENTER : ControlPointRole.RADIUS;
   }

   void resetTransform() {
      this.rotation = IDENTITY_ROTATION.clone();
      this.localScale = new Vec3(1.0, 1.0, 1.0);
      this.worldScale = new Vec3(1.0, 1.0, 1.0);
      this.gizmoLocal = false;
      this.adjustmentBaseline = List.of();
   }

   @Override
   boolean removeOrUndo(BlockPos point, boolean useHitPoint) {
      if (!this.closed()) return super.removeOrUndo(point, useHitPoint);
      this.setClosed(false);
      this.restoreAdjustmentBaseline();
      if (this.points().size() > 1) this.points().removeLast();
      this.resetTransform();
      return true;
   }

   void captureAdjustmentBaseline() {
      this.adjustmentBaseline = List.copyOf(this.points());
   }

   void restoreAdjustmentBaseline() {
      if (this.adjustmentBaseline.isEmpty()) {
         return;
      }
      this.points().clear();
      this.points().addAll(this.adjustmentBaseline);
   }
}

