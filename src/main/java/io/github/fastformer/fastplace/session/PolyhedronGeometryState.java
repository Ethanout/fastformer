package io.github.fastformer.fastplace.session;

import io.github.fastformer.core.math.Axis;
import io.github.fastformer.fastplace.geometry.GeometryMode;
import io.github.fastformer.fastplace.geometry.GeometryNumbers;
import io.github.fastformer.fastplace.geometry.GeometryPoint;
import io.github.fastformer.fastplace.geometry.GeometryPoints;
import io.github.fastformer.fastplace.geometry.controlpoint.ControlPointRole;
import io.github.fastformer.fastplace.geometry.polyhedron.PolyhedronSizeMode;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

public final class PolyhedronGeometryState extends GeometryShapeState {
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

   public int polyhedronShapeVariant() {
      return 0;
   }

   public boolean hasPolyhedronCenter() {
      return this.polyhedronPoints().center().isPresent();
   }

   public boolean hasPolyhedronFirstInput() {
      return this.polyhedronPoints().hasFirstInput();
   }

   public boolean hasPolyhedronRadiusPoint() {
      return this.polyhedronPoints().radiusPoint().isPresent();
   }

   public boolean canGeneratePolyhedron() {
      return this.polyhedronPoints().canGenerate();
   }

   public GeometryPoints.Polyhedron polyhedronPoints() {
      return GeometryPoints.polyhedron(this.pointLocations(), this.pointRoles(), this.polyhedronSizeMode());
   }

   public PolyhedronSizeMode polyhedronSizeMode() {
      return this.sizeMode;
   }

   public void setPolyhedronSizeMode(PolyhedronSizeMode mode) {
      if (this.closed()
         || mode == null
         || mode == this.sizeMode) {
         return;
      }
      this.sizeMode = mode;
      this.clearPoints();
      this.resetTransform();
   }

   public void cyclePolyhedronSizeMode() {
      this.setPolyhedronSizeMode(this.polyhedronSizeMode().next());
   }

   public double[] rotation() {
      return this.rotation.clone();
   }

   public Vec3 polyhedronLocalScale() {
      return this.localScale;
   }

   public Vec3 polyhedronWorldScale() {
      return this.worldScale;
   }

   public boolean polyhedronGizmoLocal() {
      return this.gizmoLocal;
   }

   public void togglePolyhedronGizmoFrame() {
      if (this.closed()) {
         this.gizmoLocal = !this.gizmoLocal;
      }
   }

   public void setPolyhedronRadius(double radius) {
      if (!this.hasPolyhedronFirstInput()) {
         return;
      }
      double distance = Math.clamp(Math.round(radius * 2.0) * 0.5, 0.5, 256.0);
      if (this.sizeMode == PolyhedronSizeMode.DIAMETER) {
         if (this.points().size() < 2) {
            return;
         }
         Vec3 first = this.points().getFirst().location();
         Vec3 second = this.points().get(1).location();
         Vec3 center = first.add(second).scale(0.5);
         Vec3 direction = second.subtract(first);
         Vec3 axis = direction.lengthSqr() < 1.0E-12 ? new Vec3(1.0, 0.0, 0.0) : direction.normalize();
         this.points().set(0, new GeometryPoint(center.add(axis.scale(-distance)), ControlPointRole.DIAMETER_A));
         this.points().set(1, new GeometryPoint(center.add(axis.scale(distance)), ControlPointRole.DIAMETER_B));
      } else {
         Vec3 center = this.points().getFirst().location();
         GeometryPoint radiusPoint = new GeometryPoint(center.add(distance, 0.0, 0.0), ControlPointRole.RADIUS);
         if (this.points().size() == 1) {
            this.points().add(radiusPoint);
         } else {
            this.points().set(1, radiusPoint);
         }
      }
   }

   public void adjustPolyhedronRadius(int steps) {
      if (steps == 0 || !this.hasPolyhedronCenter()) {
         return;
      }
      double current = this.polyhedronPoints().radius(1.0);
      this.setPolyhedronRadius(current + (double)steps * 0.5);
   }

   public void movePolyhedron(Vec3 offset) {
      if (offset == null || offset.lengthSqr() < 1.0E-12) {
         return;
      }
      for (int i = 0; i < this.points().size(); i++) {
         this.points().set(i, this.points().get(i).offset(offset));
      }
   }

   public Vec3 polyhedronGizmoAxis(Axis axis) {
      if (!this.gizmoLocal) {
         return switch (axis) {
            case X -> new Vec3(1.0, 0.0, 0.0);
            case Y -> new Vec3(0.0, 1.0, 0.0);
            case Z -> new Vec3(0.0, 0.0, 1.0);
         };
      }
      return switch (axis) {
         case X -> new Vec3(this.rotation[0], this.rotation[3], this.rotation[6]).normalize();
         case Y -> new Vec3(this.rotation[1], this.rotation[4], this.rotation[7]).normalize();
         case Z -> new Vec3(this.rotation[2], this.rotation[5], this.rotation[8]).normalize();
      };
   }

   public void adjustPolyhedronScale(Axis axis, int steps) {
      if (steps == 0 || !this.hasPolyhedronRadiusPoint()) {
         return;
      }
      double radius = this.polyhedronPoints().radius(0.5);
      Vec3 current = this.gizmoLocal ? this.localScale : this.worldScale;
      double value = Math.clamp(scaleComponent(current, axis) + (double)steps * 0.5 / radius, 0.125, 8.0);
      Vec3 adjusted = withScaleComponent(current, axis, GeometryNumbers.cleanZero(value));
      if (this.gizmoLocal) {
         this.localScale = adjusted;
      } else {
         this.worldScale = adjusted;
      }
   }

   public void rotatePolyhedron(Axis axis, int steps, int divisor) {
      if (steps == 0) {
         return;
      }
      double angle = Math.PI * 2.0 * (double)steps / (double)Math.max(1, divisor);
      double[] delta = axisRotation(axis.ordinal(), angle);
      this.rotation = GeometryNumbers.cleanZero(
         this.gizmoLocal ? multiply(this.rotation, delta) : multiply(delta, this.rotation)
      );
   }

   public void setPolyhedronRadiusPoint(BlockPos point) {
      this.setPolyhedronRadiusPoint(Vec3.atCenterOf(point));
   }

   public void setPolyhedronRadiusPoint(Vec3 point) {
      if (this.sizeMode != PolyhedronSizeMode.RADIUS
         || !this.hasPolyhedronFirstInput()) {
         return;
      }
      Vec3 center = this.points().getFirst().location();
      Vec3 direction = point.subtract(center);
      double radius = Math.clamp(Math.round(direction.length() * 2.0) * 0.5, 0.5, 256.0);
      Vec3 normalized = direction.lengthSqr() < 1.0E-12 ? new Vec3(1.0, 0.0, 0.0) : direction.normalize();
      GeometryPoint value = new GeometryPoint(center.add(normalized.scale(radius)), ControlPointRole.RADIUS);
      if (this.points().size() == 1) {
         this.points().add(value);
      } else {
         this.points().set(1, value);
      }
   }

   public void enterPolyhedronAdjust(BlockPos fallbackRadiusPoint) {
      this.enterPolyhedronAdjust(Vec3.atCenterOf(fallbackRadiusPoint));
   }

   public void enterPolyhedronAdjust(Vec3 fallbackRadiusPoint) {
      if (!this.hasPolyhedronFirstInput()) {
         return;
      }
      if (this.points().size() < 2 && this.sizeMode == PolyhedronSizeMode.RADIUS) {
         this.setPolyhedronRadiusPoint(fallbackRadiusPoint);
      } else if (this.points().size() < 2) {
         this.addPoint(fallbackRadiusPoint);
      }
      this.captureAdjustmentBaseline();
      this.setClosed(this.canGeneratePolyhedron());
   }

   public void rotateSnap(int axis, int steps, int divisor) {
      if (steps == 0) {
         return;
      }
      double angle = Math.PI * 2.0 * (double)steps / (double)Math.max(1, divisor);
      this.rotation = GeometryNumbers.cleanZero(multiply(axisRotation(axis, angle), this.rotation));
   }

   public boolean setEulerDegrees(double xDegrees, double yDegrees, double zDegrees) {
      if (!allFinite(xDegrees, yDegrees, zDegrees)) {
         return false;
      }
      double[] x = axisRotation(0, Math.toRadians(xDegrees));
      double[] y = axisRotation(1, Math.toRadians(yDegrees));
      double[] z = axisRotation(2, Math.toRadians(zDegrees));
      this.rotation = GeometryNumbers.cleanZero(multiply(z, multiply(y, x)));
      return true;
   }

   public boolean setQuaternion(double w, double x, double y, double z) {
      if (!allFinite(w, x, y, z)) {
         return false;
      }
      double max = Math.max(Math.max(Math.abs(w), Math.abs(x)), Math.max(Math.abs(y), Math.abs(z)));
      if (max < 1.0E-8) {
         this.rotation = PolyhedronGeometryState.IDENTITY_ROTATION.clone();
         return true;
      }
      w /= max;
      x /= max;
      y /= max;
      z /= max;
      double length = Math.sqrt(w * w + x * x + y * y + z * z);
      if (length < 1.0E-8) {
         this.rotation = PolyhedronGeometryState.IDENTITY_ROTATION.clone();
         return true;
      }
      w /= length;
      x /= length;
      y /= length;
      z /= length;
      this.rotation = GeometryNumbers.cleanZero(new double[]{
         1.0 - 2.0 * (y * y + z * z), 2.0 * (x * y - z * w), 2.0 * (x * z + y * w),
         2.0 * (x * y + z * w), 1.0 - 2.0 * (x * x + z * z), 2.0 * (y * z - x * w),
         2.0 * (x * z - y * w), 2.0 * (y * z + x * w), 1.0 - 2.0 * (x * x + y * y)
      });
      return true;
   }

   public boolean setMatrix(double[] matrix) {
      if (matrix == null
         || matrix.length != 9
         || !allFinite(matrix)) {
         return false;
      }
      this.rotation = GeometryNumbers.cleanZero(matrix);
      return true;
   }

   private static double[] axisRotation(int axis, double angle) {
      double c = Math.cos(angle);
      double s = Math.sin(angle);
      return switch (Math.floorMod(axis, 3)) {
         case 0 -> new double[]{1.0, 0.0, 0.0, 0.0, c, -s, 0.0, s, c};
         case 1 -> new double[]{c, 0.0, s, 0.0, 1.0, 0.0, -s, 0.0, c};
         default -> new double[]{c, -s, 0.0, s, c, 0.0, 0.0, 0.0, 1.0};
      };
   }

   private static double[] multiply(double[] left, double[] right) {
      double[] result = new double[9];
      for (int row = 0; row < 3; row++) {
         for (int column = 0; column < 3; column++) {
            result[row * 3 + column] = left[row * 3] * right[column]
               + left[row * 3 + 1] * right[3 + column]
               + left[row * 3 + 2] * right[6 + column];
         }
      }
      return result;
   }

   private static boolean allFinite(double... values) {
      return GeometryNumbers.finite(values);
   }

   private static double scaleComponent(Vec3 scale, Axis axis) {
      return switch (axis) {
         case X -> scale.x;
         case Y -> scale.y;
         case Z -> scale.z;
      };
   }

   private static Vec3 withScaleComponent(Vec3 scale, Axis axis, double value) {
      return switch (axis) {
         case X -> new Vec3(value, scale.y, scale.z);
         case Y -> new Vec3(scale.x, value, scale.z);
         case Z -> new Vec3(scale.x, scale.y, value);
      };
   }
}
