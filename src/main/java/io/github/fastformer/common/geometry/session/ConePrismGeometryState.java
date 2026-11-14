package io.github.fastformer.common.geometry.session;

import io.github.fastformer.core.math.Axis;
import io.github.fastformer.common.geometry.GeometryMode;
import io.github.fastformer.common.geometry.GeometryNumbers;
import io.github.fastformer.common.geometry.GeometryPoint;
import io.github.fastformer.common.geometry.GeometryPoints;
import io.github.fastformer.common.geometry.ConePlaneMode;
import io.github.fastformer.common.geometry.ConePrismStage;
import io.github.fastformer.common.geometry.ControlPointRole;
import net.minecraft.world.phys.Vec3;

public final class ConePrismGeometryState extends GeometryShapeState {
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

   public int coneShapeVariant() {
      return this.shapeVariant;
   }

   public void setConeShapeVariant(int variant) {
      this.shapeVariant = Math.floorMod(variant, 3);
      this.topScaleOffset = this.clampConeTopScaleOffset(this.topScaleOffset);
   }

   public ConePlaneMode conePlaneMode() {
      return this.planeMode;
   }

   public ConePrismStage coneStage() {
      return this.conePoints().stage();
   }

   public int coneFacePointCount() {
      return this.conePoints().facePointCount();
   }

   public int coneRequiredPoints() {
      return this.conePoints().requiredPointCount();
   }

   public GeometryPoints.Cone conePoints() {
      return GeometryPoints.cone(this.points(), this.conePlaneMode());
   }

   public boolean coneFaceComplete() {
      return this.conePoints().faceComplete();
   }

   public boolean coneBodyComplete() {
      return this.conePoints().bodyComplete();
   }

   public double coneScaleX() {
      return this.scaleX;
   }

   public double coneScaleZ() {
      return this.scaleZ;
   }

   public double coneRadius() {
      return this.radius;
   }

   public double coneTopScaleOffset() {
      return this.topScaleOffset;
   }

   public Vec3 coneTopOffset() {
      return this.topOffset;
   }

   public double coneRotationRadians() {
      return this.rotationRadians;
   }

   public boolean coneGizmoLocal() {
      return this.gizmoLocal;
   }

   public void toggleConeGizmoFrame() {
      if (this.coneStage() == ConePrismStage.ADJUST) {
         this.gizmoLocal = !this.gizmoLocal;
      }
   }

   public void setConePlaneMode(ConePlaneMode mode) {
      if (mode == null) {
         return;
      }
      if (this.planeMode != mode) {
         this.resetAll();
         this.planeMode = mode;
      }
   }

   public void cycleConePlaneMode() {
      this.setConePlaneMode(this.conePlaneMode().next());
   }

   public boolean canAcceptConePoint() {
      return this.coneStage() != ConePrismStage.ADJUST;
   }

   public void resetConeWorkflow() {
      this.resetAll();
   }

   public boolean setConeScale(double x, double z) {
      if (!GeometryNumbers.finite(x, z)) {
         return false;
      }
      this.scaleX = GeometryNumbers.cleanZero(Math.clamp(x, 0.125, 8.0));
      this.scaleZ = GeometryNumbers.cleanZero(Math.clamp(z, 0.125, 8.0));
      return true;
   }

   public void adjustConeScale(Axis axis, int steps, double radius) {
      if (steps == 0 || !GeometryNumbers.finite(radius) || radius < 1.0E-7) {
         return;
      }
      double delta = (double)steps * 0.5 / radius;
      if (axis == Axis.X) {
         this.setConeScale(this.coneScaleX() + delta, this.coneScaleZ());
      } else if (axis == Axis.Z) {
         this.setConeScale(this.coneScaleX(), this.coneScaleZ() + delta);
      }
   }

   public void moveCone(Vec3 delta) {
      if (delta == null || !GeometryNumbers.finite(delta.x, delta.y, delta.z)) {
         return;
      }
      for (int index = 0; index < this.points().size(); index++) {
         GeometryPoint point = this.points().get(index);
         this.points().set(index, new GeometryPoint(point.location().add(delta), point.role()));
      }
   }

   public void setConeRadius(double radius) {
      if (GeometryNumbers.finite(radius)) {
         this.radius = GeometryNumbers.cleanZero(Math.clamp(Math.round(radius * 2.0) * 0.5, 0.5, 256.0));
      }
   }

   public void adjustConeTopScale(int steps) {
      if (steps == 0) {
         return;
      }
      this.topScaleOffset = GeometryNumbers.cleanZero(
         Math.clamp(this.topScaleOffset + (double)Integer.signum(steps) * 0.125, this.coneTopScaleOffsetMin(), 7.0)
      );
   }

   public void adjustConeTopOffset(Vec3 view, Vec3 axisU, Vec3 axisV, int steps) {
      if (steps == 0) {
         return;
      }
      Vec3 direction = view.lengthSqr() < 1.0E-7 ? new Vec3(0.0, 0.0, 1.0) : view.normalize();
      Vec3 localU = axisU.lengthSqr() < 1.0E-7 ? new Vec3(1.0, 0.0, 0.0) : axisU.normalize();
      Vec3 localV = axisV.lengthSqr() < 1.0E-7 ? new Vec3(0.0, 0.0, 1.0) : axisV.normalize();
      double uAlignment = direction.dot(localU);
      double vAlignment = direction.dot(localV);
      double du = Math.abs(uAlignment) >= Math.abs(vAlignment) ? signedSteps(uAlignment, steps) : 0.0;
      double dv = Math.abs(uAlignment) >= Math.abs(vAlignment) ? 0.0 : signedSteps(vAlignment, steps);
      this.adjustConeTopOffset(du, dv);
   }

   public void adjustConeTopOffset(double deltaU, double deltaV) {
      if (!GeometryNumbers.finite(deltaU, deltaV)) {
         return;
      }
      this.topOffset = new Vec3(
         snappedHalf(Math.clamp(this.topOffset.x + deltaU, -128.0, 128.0)),
         0.0,
         snappedHalf(Math.clamp(this.topOffset.z + deltaV, -128.0, 128.0))
      );
   }

   public void adjustConeHeight(Vec3 baseCenter, Vec3 normal, double delta) {
      if (this.coneStage() != ConePrismStage.ADJUST
         || !GeometryNumbers.finite(delta)
         || baseCenter == null
         || normal == null
         || normal.lengthSqr() < 1.0E-7) {
         return;
      }
      int index = -1;
      for (int pointIndex = 0; pointIndex < this.points().size(); pointIndex++) {
         if (this.points().get(pointIndex).role() == ControlPointRole.HEIGHT) {
            index = pointIndex;
            break;
         }
      }
      if (index < 0) {
         return;
      }
      Vec3 axis = normal.normalize();
      double current = this.points().get(index).location().subtract(baseCenter).dot(axis);
      double adjusted = Math.clamp(snappedHalf(current + delta), -256.0, 256.0);
      if (Math.abs(adjusted) < 0.5) {
         adjusted = current < 0.0 ? -0.5 : 0.5;
      }
      this.points().set(index, new GeometryPoint(baseCenter.add(axis.scale(adjusted)), ControlPointRole.HEIGHT));
   }

   public void rotateCone(int steps, int divisor) {
      if (steps == 0 || this.coneStage() != ConePrismStage.ADJUST) {
         return;
      }
      double delta = Math.PI * 2.0 * (double)steps / (double)Math.max(1, divisor);
      this.rotationRadians = GeometryNumbers.cleanZero(Math.IEEEremainder(this.rotationRadians + delta, Math.PI * 2.0));
   }

   private static int signedSteps(double alignment, int steps) {
      return (alignment < 0.0 ? -1 : 1) * steps;
   }

   private static double snappedHalf(double value) {
      return GeometryNumbers.cleanZero(Math.round(value * 2.0) * 0.5);
   }

   private double clampConeTopScaleOffset(double value) {
      return GeometryNumbers.cleanZero(Math.clamp(value, this.coneTopScaleOffsetMin(), 7.0));
   }

   private double coneTopScaleOffsetMin() {
      return switch (Math.floorMod(this.coneShapeVariant(), 3)) {
         case 0 -> -1.0;
         case 1 -> 0.0;
         default -> -0.5;
      };
   }
}
