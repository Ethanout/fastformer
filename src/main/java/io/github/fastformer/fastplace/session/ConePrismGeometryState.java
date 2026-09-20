package io.github.fastformer.fastplace.session;

import io.github.fastformer.fastplace.GeometryMode;
import io.github.fastformer.fastplace.GeometryPoints;
import io.github.fastformer.fastplace.ConePlaneMode;
import io.github.fastformer.fastplace.ConePrismStage;
import io.github.fastformer.fastplace.geometry.ControlPointRole;
import net.minecraft.world.phys.Vec3;

final class ConePrismGeometryState extends GeometryShapeState {
   int shapeVariant;
   ConePlaneMode planeMode = ConePlaneMode.RADIUS;
   double scaleX = 1.0;
   double scaleZ = 1.0;
   double radius = 1.0;
   double topScaleOffset;
   Vec3 topOffset = Vec3.ZERO;
   double rotationRadians;
   boolean gizmoLocal = true;

   ConePrismGeometryState() {
      super(GeometryMode.CONE_PRISM);
   }

   @Override
   ControlPointRole nextRole() {
      GeometryPoints.Cone cone = GeometryPoints.cone(this.points(), this.planeMode);
      if (cone.stage() != ConePrismStage.FACE) {
         return ControlPointRole.HEIGHT;
      }
      return switch (this.planeMode) {
         case RADIUS -> this.points().isEmpty() ? ControlPointRole.BASE_CENTER : ControlPointRole.RADIUS;
         case DIAMETER -> this.points().isEmpty() ? ControlPointRole.DIAMETER_A : ControlPointRole.DIAMETER_B;
         case THREE_POINT -> this.points().isEmpty() ? ControlPointRole.PRIMARY : ControlPointRole.BASE_FACE;
      };
   }

   void resetAdjustments() {
      this.shapeVariant = 0;
      this.topScaleOffset = 0.0;
      this.topOffset = Vec3.ZERO;
      this.rotationRadians = 0.0;
      this.gizmoLocal = true;
   }

   @Override
   void afterPointRemoved() {
      if (this.points().size() < GeometryPoints.cone(this.points(), this.planeMode).requiredPointCount()) {
         resetAdjustments();
      }
   }

   void resetAll() {
      this.clearPoints();
      this.shapeVariant = 0;
      this.scaleX = 1.0;
      this.scaleZ = 1.0;
      this.radius = 1.0;
      this.topScaleOffset = 0.0;
      this.topOffset = Vec3.ZERO;
      this.rotationRadians = 0.0;
      this.gizmoLocal = true;
   }
}

