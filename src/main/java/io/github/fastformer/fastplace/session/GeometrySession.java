package io.github.fastformer.fastplace.session;

import io.github.fastformer.fastplace.*;
import io.github.fastformer.fastplace.world.*;
import io.github.fastformer.fastplace.GeometryPoints;
import java.util.List;
import io.github.fastformer.fastplace.geometry.ControlPointRole;
import io.github.fastformer.fastplace.geometry.AxisGizmo;
import io.github.fastformer.fastplace.geometry.GeometryNumbers;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

public final class GeometrySession implements SessionLifecycle {
   private GeometryShapeState state = GeometryShapeState.create(GeometryMode.WALL);

   public List<BlockPos> points() {
      return this.state.blockPoints();
   }

   public List<Vec3> pointLocations() {
      return this.state.pointLocations();
   }

   public List<ControlPointRole> pointRoles() {
      return this.state.points().stream().map(GeometryPoint::role).toList();
   }

   public GeometryMode mode() {
      return this.state.mode();
   }

   public void setMode(GeometryMode mode) {
      this.clearSelectedControlPoint();
      this.state = GeometryShapeState.create(mode == null ? GeometryMode.WALL : mode);
   }

   public BlockPos extrusion() {
      return this.state instanceof WallGeometryState wall ? wall.extrusion : BlockPos.ZERO;
   }

   public boolean closed() {
      return this.state.closed();
   }

   public int selectedControlPoint() {
      return this.state instanceof ConvexPolyhedronGeometryState convex
         && convex.selectedPointIndex >= 0
         && convex.selectedPointIndex < convex.points().size()
         ? convex.selectedPointIndex
         : -1;
   }

   public void selectControlPoint(int index) {
      if (!(this.state instanceof ConvexPolyhedronGeometryState convex)) {
         return;
      }
      convex.selectedPointIndex = index >= 0 && index < convex.points().size() ? index : -1;
   }

   public void clearSelectedControlPoint() {
      if (this.state instanceof ConvexPolyhedronGeometryState convex) {
         convex.selectedPointIndex = -1;
      }
   }

   public int polyhedronShapeVariant() {
      return 0;
   }

   public int coneShapeVariant() {
      return this.state instanceof ConePrismGeometryState cone ? cone.shapeVariant : 0;
   }

   public void setConeShapeVariant(int variant) {
      if (this.state instanceof ConePrismGeometryState cone) {
         cone.shapeVariant = Math.floorMod(variant, 3);
         cone.topScaleOffset = this.clampConeTopScaleOffset(cone.topScaleOffset);
      }
   }

   public int compoundShapeVariant() {
      return this.state instanceof CompoundGeometryState compound ? compound.shapeVariant : 0;
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
      return this.state instanceof PolyhedronGeometryState polyhedron ? polyhedron.sizeMode : PolyhedronSizeMode.RADIUS;
   }

   public void setPolyhedronSizeMode(PolyhedronSizeMode mode) {
      if (!(this.state instanceof PolyhedronGeometryState polyhedron)
         || polyhedron.closed()
         || mode == null
         || mode == polyhedron.sizeMode) {
         return;
      }
      polyhedron.sizeMode = mode;
      polyhedron.clearPoints();
      this.resetPolyhedronTransform();
   }

   public void cyclePolyhedronSizeMode() {
      this.setPolyhedronSizeMode(this.polyhedronSizeMode().next());
   }

   public ConePlaneMode conePlaneMode() {
      return this.state instanceof ConePrismGeometryState cone ? cone.planeMode : ConePlaneMode.RADIUS;
   }

   public ConePrismStage coneStage() {
      return this.state instanceof ConePrismGeometryState ? this.conePoints().stage() : null;
   }

   public int coneFacePointCount() {
      return this.conePoints().facePointCount();
   }

   public int coneRequiredPoints() {
      return this.conePoints().requiredPointCount();
   }

   public GeometryPoints.Cone conePoints() {
      return GeometryPoints.cone(this.state.points(), this.conePlaneMode());
   }

   public boolean coneFaceComplete() {
      return this.conePoints().faceComplete();
   }

   public boolean coneBodyComplete() {
      return this.conePoints().bodyComplete();
   }

   public double coneScaleX() {
      return this.state instanceof ConePrismGeometryState cone ? cone.scaleX : 1.0;
   }

   public double coneScaleZ() {
      return this.state instanceof ConePrismGeometryState cone ? cone.scaleZ : 1.0;
   }

   public double coneRadius() {
      return this.state instanceof ConePrismGeometryState cone ? cone.radius : 1.0;
   }

   public double coneTopScaleOffset() {
      return this.state instanceof ConePrismGeometryState cone ? cone.topScaleOffset : 0.0;
   }

   public Vec3 coneTopOffset() {
      return this.state instanceof ConePrismGeometryState cone ? cone.topOffset : Vec3.ZERO;
   }

   public double coneRotationRadians() {
      return this.state instanceof ConePrismGeometryState cone ? cone.rotationRadians : 0.0;
   }

   public boolean coneGizmoLocal() {
      return !(this.state instanceof ConePrismGeometryState cone) || cone.gizmoLocal;
   }

   public void toggleConeGizmoFrame() {
      if (this.state instanceof ConePrismGeometryState cone && this.coneStage() == ConePrismStage.ADJUST) {
         cone.gizmoLocal = !cone.gizmoLocal;
      }
   }

   public void setConePlaneMode(ConePlaneMode mode) {
      if (!(this.state instanceof ConePrismGeometryState cone) || mode == null) {
         return;
      }
      if (cone.planeMode != mode) {
         cone.resetAll();
         cone.planeMode = mode;
      }
   }

   public void cycleConePlaneMode() {
      this.setConePlaneMode(this.conePlaneMode().next());
   }

   public boolean canAcceptConePoint() {
      return this.coneStage() != ConePrismStage.ADJUST;
   }

   public void resetConeWorkflow() {
      if (this.state instanceof ConePrismGeometryState cone) {
         cone.resetAll();
      }
   }

   public boolean setConeScale(double x, double z) {
      if (!(this.state instanceof ConePrismGeometryState cone) || !GeometryNumbers.finite(x, z)) {
         return false;
      }
      cone.scaleX = GeometryNumbers.cleanZero(Math.clamp(x, 0.125, 8.0));
      cone.scaleZ = GeometryNumbers.cleanZero(Math.clamp(z, 0.125, 8.0));
      return true;
   }

   public void adjustConeScale(AxisGizmo.Axis axis, int steps, double radius) {
      if (steps == 0 || !GeometryNumbers.finite(radius) || radius < 1.0E-7) {
         return;
      }
      double delta = (double)steps * 0.5 / radius;
      if (axis == AxisGizmo.Axis.X) {
         this.setConeScale(this.coneScaleX() + delta, this.coneScaleZ());
      } else if (axis == AxisGizmo.Axis.Z) {
         this.setConeScale(this.coneScaleX(), this.coneScaleZ() + delta);
      }
   }

   public void moveCone(Vec3 delta) {
      if (!(this.state instanceof ConePrismGeometryState cone) || delta == null || !GeometryNumbers.finite(delta.x, delta.y, delta.z)) {
         return;
      }
      for (int index = 0; index < cone.points().size(); index++) {
         GeometryPoint point = cone.points().get(index);
         cone.points().set(index, new GeometryPoint(point.location().add(delta), point.role()));
      }
   }

   public void setConeRadius(double radius) {
      if (this.state instanceof ConePrismGeometryState cone && GeometryNumbers.finite(radius)) {
         cone.radius = GeometryNumbers.cleanZero(Math.clamp(Math.round(radius * 2.0) * 0.5, 0.5, 256.0));
      }
   }

   public void adjustConeTopScale(int steps) {
      if (steps == 0) {
         return;
      }
      if (this.state instanceof ConePrismGeometryState cone) {
         cone.topScaleOffset = GeometryNumbers.cleanZero(
            Math.clamp(cone.topScaleOffset + (double)Integer.signum(steps) * 0.125, this.coneTopScaleOffsetMin(), 7.0)
         );
      }
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
      if (this.state instanceof ConePrismGeometryState cone) {
         cone.topOffset = new Vec3(
            snappedHalf(Math.clamp(cone.topOffset.x + deltaU, -128.0, 128.0)),
            0.0,
            snappedHalf(Math.clamp(cone.topOffset.z + deltaV, -128.0, 128.0))
         );
      }
   }

   public void adjustConeHeight(Vec3 baseCenter, Vec3 normal, double delta) {
      if (!(this.state instanceof ConePrismGeometryState cone)
         || this.coneStage() != ConePrismStage.ADJUST
         || !GeometryNumbers.finite(delta)
         || baseCenter == null
         || normal == null
         || normal.lengthSqr() < 1.0E-7) {
         return;
      }
      int index = -1;
      for (int pointIndex = 0; pointIndex < cone.points().size(); pointIndex++) {
         if (cone.points().get(pointIndex).role() == ControlPointRole.HEIGHT) {
            index = pointIndex;
            break;
         }
      }
      if (index < 0) {
         return;
      }
      Vec3 axis = normal.normalize();
      double current = cone.points().get(index).location().subtract(baseCenter).dot(axis);
      double adjusted = Math.clamp(snappedHalf(current + delta), -256.0, 256.0);
      if (Math.abs(adjusted) < 0.5) {
         adjusted = current < 0.0 ? -0.5 : 0.5;
      }
      cone.points().set(index, new GeometryPoint(baseCenter.add(axis.scale(adjusted)), ControlPointRole.HEIGHT));
   }

   public void rotateCone(int steps, int divisor) {
      if (steps == 0 || !(this.state instanceof ConePrismGeometryState cone) || this.coneStage() != ConePrismStage.ADJUST) {
         return;
      }
      double delta = Math.PI * 2.0 * (double)steps / (double)Math.max(1, divisor);
      cone.rotationRadians = GeometryNumbers.cleanZero(Math.IEEEremainder(cone.rotationRadians + delta, Math.PI * 2.0));
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

   public double[] rotation() {
      return this.state instanceof PolyhedronGeometryState polyhedron
         ? polyhedron.rotation.clone()
         : PolyhedronGeometryState.IDENTITY_ROTATION.clone();
   }

   public Vec3 polyhedronLocalScale() {
      return this.state instanceof PolyhedronGeometryState polyhedron
         ? polyhedron.localScale
         : new Vec3(1.0, 1.0, 1.0);
   }

   public Vec3 polyhedronWorldScale() {
      return this.state instanceof PolyhedronGeometryState polyhedron
         ? polyhedron.worldScale
         : new Vec3(1.0, 1.0, 1.0);
   }

   public boolean polyhedronGizmoLocal() {
      return this.state instanceof PolyhedronGeometryState polyhedron && polyhedron.gizmoLocal;
   }

   public void togglePolyhedronGizmoFrame() {
      if (this.state instanceof PolyhedronGeometryState polyhedron && polyhedron.closed()) {
         polyhedron.gizmoLocal = !polyhedron.gizmoLocal;
      }
   }

   public void cycleCompoundShapeVariant(int steps) {
      if (this.state instanceof CompoundGeometryState compound) {
         compound.shapeVariant = Math.floorMod(compound.shapeVariant + Integer.signum(steps), 2);
      }
   }

   public void setPolyhedronRadius(double radius) {
      if (!(this.state instanceof PolyhedronGeometryState polyhedron) || !this.hasPolyhedronFirstInput()) {
         return;
      }
      double distance = Math.clamp(Math.round(radius * 2.0) * 0.5, 0.5, 256.0);
      if (polyhedron.sizeMode == PolyhedronSizeMode.DIAMETER) {
         if (polyhedron.points().size() < 2) {
            return;
         }
         Vec3 first = polyhedron.points().getFirst().location();
         Vec3 second = polyhedron.points().get(1).location();
         Vec3 center = first.add(second).scale(0.5);
         Vec3 direction = second.subtract(first);
         Vec3 axis = direction.lengthSqr() < 1.0E-12 ? new Vec3(1.0, 0.0, 0.0) : direction.normalize();
         polyhedron.points().set(0, new GeometryPoint(center.add(axis.scale(-distance)), ControlPointRole.DIAMETER_A));
         polyhedron.points().set(1, new GeometryPoint(center.add(axis.scale(distance)), ControlPointRole.DIAMETER_B));
      } else {
         Vec3 center = polyhedron.points().getFirst().location();
         GeometryPoint radiusPoint = new GeometryPoint(center.add(distance, 0.0, 0.0), ControlPointRole.RADIUS);
         if (polyhedron.points().size() == 1) {
            polyhedron.points().add(radiusPoint);
         } else {
            polyhedron.points().set(1, radiusPoint);
         }
      }
   }

   public void adjustPolyhedronRadius(int steps) {
      if (steps == 0 || !(this.state instanceof PolyhedronGeometryState) || !this.hasPolyhedronCenter()) {
         return;
      }
      double current = this.polyhedronPoints().radius(1.0);
      this.setPolyhedronRadius(current + (double)steps * 0.5);
   }

   public void movePolyhedron(Vec3 offset) {
      if (!(this.state instanceof PolyhedronGeometryState polyhedron) || offset == null || offset.lengthSqr() < 1.0E-12) {
         return;
      }
      for (int i = 0; i < polyhedron.points().size(); i++) {
         polyhedron.points().set(i, polyhedron.points().get(i).offset(offset));
      }
   }

   public void moveSelectedPoint(Vec3 delta) {
      if (!(this.state instanceof ConvexPolyhedronGeometryState convex)
         || delta == null
         || !GeometryNumbers.finite(delta.x, delta.y, delta.z)) {
         return;
      }
      int index = this.selectedControlPoint();
      if (index < 0) {
         return;
      }
      GeometryPoint point = convex.points().get(index);
      Vec3 location = point.location().add(delta);
      convex.points().set(index, point.withLocation(new Vec3(snappedHalf(location.x), snappedHalf(location.y), snappedHalf(location.z))));
   }

   public Vec3 polyhedronGizmoAxis(AxisGizmo.Axis axis) {
      if (!(this.state instanceof PolyhedronGeometryState polyhedron) || !polyhedron.gizmoLocal) {
         return switch (axis) {
            case X -> new Vec3(1.0, 0.0, 0.0);
            case Y -> new Vec3(0.0, 1.0, 0.0);
            case Z -> new Vec3(0.0, 0.0, 1.0);
         };
      }
      return switch (axis) {
         case X -> new Vec3(polyhedron.rotation[0], polyhedron.rotation[3], polyhedron.rotation[6]).normalize();
         case Y -> new Vec3(polyhedron.rotation[1], polyhedron.rotation[4], polyhedron.rotation[7]).normalize();
         case Z -> new Vec3(polyhedron.rotation[2], polyhedron.rotation[5], polyhedron.rotation[8]).normalize();
      };
   }

   public void adjustPolyhedronScale(AxisGizmo.Axis axis, int steps) {
      if (steps == 0 || !(this.state instanceof PolyhedronGeometryState polyhedron) || !this.hasPolyhedronRadiusPoint()) {
         return;
      }
      double radius = this.polyhedronPoints().radius(0.5);
      Vec3 current = polyhedron.gizmoLocal ? polyhedron.localScale : polyhedron.worldScale;
      double value = Math.clamp(scaleComponent(current, axis) + (double)steps * 0.5 / radius, 0.125, 8.0);
      Vec3 adjusted = withScaleComponent(current, axis, GeometryNumbers.cleanZero(value));
      if (polyhedron.gizmoLocal) {
         polyhedron.localScale = adjusted;
      } else {
         polyhedron.worldScale = adjusted;
      }
   }

   public void rotatePolyhedron(AxisGizmo.Axis axis, int steps, int divisor) {
      if (steps == 0 || !(this.state instanceof PolyhedronGeometryState polyhedron)) {
         return;
      }
      double angle = Math.PI * 2.0 * (double)steps / (double)Math.max(1, divisor);
      double[] delta = axisRotation(axis.ordinal(), angle);
      polyhedron.rotation = GeometryNumbers.cleanZero(
         polyhedron.gizmoLocal ? multiply(polyhedron.rotation, delta) : multiply(delta, polyhedron.rotation)
      );
   }

   public void setPolyhedronRadiusPoint(BlockPos point) {
      this.setPolyhedronRadiusPoint(Vec3.atCenterOf(point));
   }

   public void setPolyhedronRadiusPoint(Vec3 point) {
      if (!(this.state instanceof PolyhedronGeometryState polyhedron)
         || polyhedron.sizeMode != PolyhedronSizeMode.RADIUS
         || !this.hasPolyhedronFirstInput()) {
         return;
      }
      Vec3 center = polyhedron.points().getFirst().location();
      Vec3 direction = point.subtract(center);
      double radius = Math.clamp(Math.round(direction.length() * 2.0) * 0.5, 0.5, 256.0);
      Vec3 normalized = direction.lengthSqr() < 1.0E-12 ? new Vec3(1.0, 0.0, 0.0) : direction.normalize();
      GeometryPoint value = new GeometryPoint(center.add(normalized.scale(radius)), ControlPointRole.RADIUS);
      if (polyhedron.points().size() == 1) {
         polyhedron.points().add(value);
      } else {
         polyhedron.points().set(1, value);
      }
   }

   public void enterPolyhedronAdjust(BlockPos fallbackRadiusPoint) {
      this.enterPolyhedronAdjust(Vec3.atCenterOf(fallbackRadiusPoint));
   }

   public void enterPolyhedronAdjust(Vec3 fallbackRadiusPoint) {
      if (!(this.state instanceof PolyhedronGeometryState polyhedron) || !this.hasPolyhedronFirstInput()) {
         return;
      }
      if (polyhedron.points().size() < 2 && polyhedron.sizeMode == PolyhedronSizeMode.RADIUS) {
         this.setPolyhedronRadiusPoint(fallbackRadiusPoint);
      } else if (polyhedron.points().size() < 2) {
         this.addPoint(fallbackRadiusPoint);
      }
      polyhedron.captureAdjustmentBaseline();
      polyhedron.setClosed(this.canGeneratePolyhedron());
   }

   public void rotateSnap(int axis, int steps, int divisor) {
      if (steps == 0 || !(this.state instanceof PolyhedronGeometryState polyhedron)) {
         return;
      }
      double angle = Math.PI * 2.0 * (double)steps / (double)Math.max(1, divisor);
      polyhedron.rotation = GeometryNumbers.cleanZero(multiply(axisRotation(axis, angle), polyhedron.rotation));
   }

   public boolean setEulerDegrees(double xDegrees, double yDegrees, double zDegrees) {
      if (!(this.state instanceof PolyhedronGeometryState polyhedron) || !allFinite(xDegrees, yDegrees, zDegrees)) {
         return false;
      }
      double[] x = axisRotation(0, Math.toRadians(xDegrees));
      double[] y = axisRotation(1, Math.toRadians(yDegrees));
      double[] z = axisRotation(2, Math.toRadians(zDegrees));
      polyhedron.rotation = GeometryNumbers.cleanZero(multiply(z, multiply(y, x)));
      return true;
   }

   public boolean setQuaternion(double w, double x, double y, double z) {
      if (!(this.state instanceof PolyhedronGeometryState polyhedron) || !allFinite(w, x, y, z)) {
         return false;
      }
      double max = Math.max(Math.max(Math.abs(w), Math.abs(x)), Math.max(Math.abs(y), Math.abs(z)));
      if (max < 1.0E-8) {
         polyhedron.rotation = PolyhedronGeometryState.IDENTITY_ROTATION.clone();
         return true;
      }
      w /= max;
      x /= max;
      y /= max;
      z /= max;
      double length = Math.sqrt(w * w + x * x + y * y + z * z);
      if (length < 1.0E-8) {
         polyhedron.rotation = PolyhedronGeometryState.IDENTITY_ROTATION.clone();
         return true;
      }
      w /= length;
      x /= length;
      y /= length;
      z /= length;
      polyhedron.rotation = GeometryNumbers.cleanZero(new double[]{
         1.0 - 2.0 * (y * y + z * z), 2.0 * (x * y - z * w), 2.0 * (x * z + y * w),
         2.0 * (x * y + z * w), 1.0 - 2.0 * (x * x + z * z), 2.0 * (y * z - x * w),
         2.0 * (x * z - y * w), 2.0 * (y * z + x * w), 1.0 - 2.0 * (x * x + y * y)
      });
      return true;
   }

   public boolean setMatrix(double[] matrix) {
      if (!(this.state instanceof PolyhedronGeometryState polyhedron)
         || matrix == null
         || matrix.length != 9
         || !allFinite(matrix)) {
         return false;
      }
      polyhedron.rotation = GeometryNumbers.cleanZero(matrix);
      return true;
   }

   public void addOrClose(BlockPos point) {
      this.clearSelectedControlPoint();
      BlockPos value = point.immutable();
      if (this.state.points().size() >= 3 && this.state.points().getFirst().block().equals(value)) {
         this.close();
      } else {
         this.addPoint(value);
      }
   }

   public void addPoint(BlockPos point) {
      this.addPoint(Vec3.atCenterOf(point));
   }

   public void addPoint(Vec3 point) {
      this.clearSelectedControlPoint();
      this.state.addPoint(point);
   }

   public void addPoint(Vec3 point, ControlPointRole role) {
      this.clearSelectedControlPoint();
      this.state.addPoint(point, role);
   }

   public boolean close() {
      if (this.state.closed()) {
         return false;
      }
      this.state.setClosed(true);
      this.clearSelectedControlPoint();
      return true;
   }

   public void adjustExtrusion(BlockPos step) {
      if (this.state instanceof WallGeometryState wall && wall.closed()) {
         wall.extrusion = new BlockPos(
            Math.clamp(wall.extrusion.getX() + step.getX(), -128, 128),
            Math.clamp(wall.extrusion.getY() + step.getY(), -128, 128),
            Math.clamp(wall.extrusion.getZ() + step.getZ(), -128, 128)
         );
      }
   }

   public boolean removeOrUndo(BlockPos point) {
      if (this.state instanceof PolyhedronGeometryState polyhedron && polyhedron.closed()) {
         polyhedron.setClosed(false);
         polyhedron.restoreAdjustmentBaseline();
         if (polyhedron.points().size() > 1) {
            polyhedron.points().removeLast();
         }
         this.resetPolyhedronTransform();
         this.clearSelectedControlPoint();
         return true;
      }
      int index = -1;
      for (int i = 0; i < this.state.points().size(); i++) {
         if (this.state.points().get(i).block().equals(point)) {
            index = i;
            break;
         }
      }
      if (index >= 0) {
         this.state.points().remove(index);
      } else if (!this.state.points().isEmpty()) {
         this.state.points().removeLast();
      } else {
         return false;
      }
      this.state.setClosed(false);
      if (this.state instanceof WallGeometryState wall) {
         wall.extrusion = BlockPos.ZERO;
      }
      if (this.state instanceof ConePrismGeometryState cone && cone.points().size() < this.coneRequiredPoints()) {
         cone.resetAdjustments();
      }
      this.clearSelectedControlPoint();
      return true;
   }

   @Override
   public boolean undoStep() {
      return this.removeOrUndo(BlockPos.ZERO);
   }

   @Override
   public boolean canUndoStep() {
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

   private void resetPolyhedronTransform() {
      if (this.state instanceof PolyhedronGeometryState polyhedron) {
         polyhedron.resetTransform();
      }
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

   private static double scaleComponent(Vec3 scale, AxisGizmo.Axis axis) {
      return switch (axis) {
         case X -> scale.x;
         case Y -> scale.y;
         case Z -> scale.z;
      };
   }

   private static Vec3 withScaleComponent(Vec3 scale, AxisGizmo.Axis axis, double value) {
      return switch (axis) {
         case X -> new Vec3(value, scale.y, scale.z);
         case Y -> new Vec3(scale.x, value, scale.z);
         case Z -> new Vec3(scale.x, scale.y, value);
      };
   }
}
