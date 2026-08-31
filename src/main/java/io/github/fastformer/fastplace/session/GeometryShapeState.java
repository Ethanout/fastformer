package io.github.fastformer.fastplace.session;

import io.github.fastformer.fastplace.*;
import io.github.fastformer.fastplace.world.*;
import io.github.fastformer.fastplace.GeometryPoints;
import io.github.fastformer.fastplace.geometry.ControlPointRole;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

abstract sealed class GeometryShapeState permits WallGeometryState, PolyhedronGeometryState, ConePrismGeometryState,
   CompoundGeometryState, ConvexPolyhedronGeometryState {
   private final GeometryMode mode;
   private final ArrayList<GeometryPoint> points = new ArrayList<>();
   private boolean closed;

   GeometryShapeState(GeometryMode mode) {
      this.mode = mode;
   }

   static GeometryShapeState create(GeometryMode mode) {
      return switch (mode) {
         case WALL -> new WallGeometryState();
         case POLYHEDRON -> new PolyhedronGeometryState();
         case CONE_PRISM -> new ConePrismGeometryState();
         case COMPOUND -> new CompoundGeometryState();
         case CONVEX_POLYHEDRON -> new ConvexPolyhedronGeometryState();
      };
   }

   final GeometryMode mode() {
      return this.mode;
   }

   final List<GeometryPoint> points() {
      return this.points;
   }

   final List<BlockPos> blockPoints() {
      return this.points.stream().map(GeometryPoint::block).toList();
   }

   final List<Vec3> pointLocations() {
      return this.points.stream().map(GeometryPoint::location).toList();
   }

   final boolean closed() {
      return this.closed;
   }

   final void setClosed(boolean closed) {
      this.closed = closed;
   }

   final void clearPoints() {
      this.points.clear();
      this.closed = false;
   }

   final void addPoint(Vec3 point) {
      GeometryPoint value = new GeometryPoint(point, this.nextRole());
      if (!this.closed && (this.points.isEmpty() || !this.points.getLast().location().equals(value.location()))) {
         this.points.add(value);
      }
   }

   final void addPoint(Vec3 point, ControlPointRole role) {
      GeometryPoint value = new GeometryPoint(point, role);
      if (!this.closed && (this.points.isEmpty() || !this.points.getLast().location().equals(value.location()))) {
         this.points.add(value);
      }
   }

   ControlPointRole nextRole() {
      return this.points.isEmpty() ? ControlPointRole.PRIMARY : ControlPointRole.SECONDARY;
   }
}

final class WallGeometryState extends GeometryShapeState {
   BlockPos extrusion = BlockPos.ZERO;

   WallGeometryState() {
      super(GeometryMode.WALL);
   }
}

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

final class CompoundGeometryState extends GeometryShapeState {
   int shapeVariant;

   CompoundGeometryState() {
      super(GeometryMode.COMPOUND);
   }
}

final class ConvexPolyhedronGeometryState extends GeometryShapeState {
   int selectedPointIndex = -1;

   ConvexPolyhedronGeometryState() {
      super(GeometryMode.CONVEX_POLYHEDRON);
   }
}
