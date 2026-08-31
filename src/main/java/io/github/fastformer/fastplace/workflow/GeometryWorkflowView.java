package io.github.fastformer.fastplace.workflow;

import io.github.fastformer.fastplace.*;
import io.github.fastformer.fastplace.world.*;
import io.github.fastformer.fastplace.session.*;
import java.util.List;
import io.github.fastformer.fastplace.geometry.ControlPointRole;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

public record GeometryWorkflowView(
   GeometryMode mode,
   int pointCount,
   List<Vec3> pointLocations,
   List<ControlPointRole> pointRoles,
   boolean closed,
   boolean modifierHeld,
   int polyhedronShapeVariant,
   int coneShapeVariant,
   int compoundShapeVariant,
   PolyhedronSizeMode polyhedronSizeMode,
   BlockPos extrusion,
   ConePlaneMode conePlaneMode,
   double coneRadius,
   double coneScaleX,
   double coneScaleZ,
   double coneTopScaleOffset,
   Vec3 coneTopOffset,
   double coneRotationRadians,
   boolean coneGizmoLocal,
   double[] rotation,
   Vec3 polyhedronLocalScale,
   Vec3 polyhedronWorldScale,
   boolean polyhedronGizmoLocal,
   FillMode fillMode,
   Vec3 view,
   int selectedPointIndex
) {
   public GeometryWorkflowView {
      pointLocations = pointLocations == null ? List.of() : pointLocations.stream().map(io.github.fastformer.fastplace.geometry.GeometryNumbers::finiteOrZero).toList();
      pointCount = Math.clamp(pointCount, 0, pointLocations.size());
      pointRoles = pointRoles == null ? List.of() : List.copyOf(pointRoles);
      if (pointRoles.size() != pointLocations.size()) {
         java.util.ArrayList<ControlPointRole> fallback = new java.util.ArrayList<>(pointLocations.size());
         for (int index = 0; index < pointLocations.size(); index++) {
            fallback.add(index == 0 ? ControlPointRole.PRIMARY : ControlPointRole.SECONDARY);
         }
         pointRoles = List.copyOf(fallback);
      }
      polyhedronSizeMode = polyhedronSizeMode == null ? PolyhedronSizeMode.RADIUS : polyhedronSizeMode;
      coneTopOffset = io.github.fastformer.fastplace.geometry.GeometryNumbers.finiteOrZero(coneTopOffset);
      coneRotationRadians = io.github.fastformer.fastplace.geometry.GeometryNumbers.finiteOr(coneRotationRadians, 0.0);
      rotation = rotation == null || rotation.length != 9 ? identityRotation() : rotation.clone();
      polyhedronLocalScale = cleanScale(polyhedronLocalScale);
      polyhedronWorldScale = cleanScale(polyhedronWorldScale);
      selectedPointIndex = selectedPointIndex >= 0 && selectedPointIndex < pointCount ? selectedPointIndex : -1;
   }

   public GeometryWorkflowView(
      GeometryMode mode,
      int pointCount,
      List<Vec3> pointLocations,
      List<ControlPointRole> pointRoles,
      boolean closed,
      boolean modifierHeld,
      int polyhedronShapeVariant,
      int coneShapeVariant,
      int compoundShapeVariant,
      PolyhedronSizeMode polyhedronSizeMode,
      BlockPos extrusion,
      ConePlaneMode conePlaneMode,
      double coneRadius,
      double coneScaleX,
      double coneScaleZ,
      double coneTopScaleOffset,
      Vec3 coneTopOffset,
      double coneRotationRadians,
      boolean coneGizmoLocal,
      double[] rotation,
      Vec3 polyhedronLocalScale,
      Vec3 polyhedronWorldScale,
      boolean polyhedronGizmoLocal,
      FillMode fillMode,
      Vec3 view
   ) {
      this(
         mode,
         pointCount,
         pointLocations,
         pointRoles,
         closed,
         modifierHeld,
         polyhedronShapeVariant,
         coneShapeVariant,
         compoundShapeVariant,
         polyhedronSizeMode,
         extrusion,
         conePlaneMode,
         coneRadius,
         coneScaleX,
         coneScaleZ,
         coneTopScaleOffset,
         coneTopOffset,
         coneRotationRadians,
         coneGizmoLocal,
         rotation,
         polyhedronLocalScale,
         polyhedronWorldScale,
         polyhedronGizmoLocal,
         fillMode,
         view,
         -1
      );
   }

   public static GeometryWorkflowView from(GeometrySession session) {
      return from(session, false);
   }

   public static GeometryWorkflowView from(GeometrySession session, boolean modifierHeld) {
      return new GeometryWorkflowView(
         session.mode(),
         session.points().size(),
         session.pointLocations(),
         session.pointRoles(),
         session.closed(),
         modifierHeld,
         session.polyhedronShapeVariant(),
         session.coneShapeVariant(),
         session.compoundShapeVariant(),
         session.polyhedronSizeMode(),
         session.extrusion(),
         session.conePlaneMode(),
         session.coneRadius(),
         session.coneScaleX(),
         session.coneScaleZ(),
         session.coneTopScaleOffset(),
         session.coneTopOffset(),
         session.coneRotationRadians(),
         session.coneGizmoLocal(),
         session.rotation(),
         session.polyhedronLocalScale(),
         session.polyhedronWorldScale(),
         session.polyhedronGizmoLocal(),
         FillMode.OUTLINE,
         new Vec3(0.0, 0.0, 1.0),
         session.selectedControlPoint()
      );
   }

   public ConePrismStage coneStage() {
      return this.conePlaneMode.stageFor(this.pointCount);
   }

   private static double[] identityRotation() {
      return new double[]{1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0};
   }

   private static Vec3 cleanScale(Vec3 value) {
      if (value == null) {
         return new Vec3(1.0, 1.0, 1.0);
      }
      return new Vec3(
         Math.clamp(io.github.fastformer.fastplace.geometry.GeometryNumbers.finiteOr(value.x, 1.0), 0.125, 8.0),
         Math.clamp(io.github.fastformer.fastplace.geometry.GeometryNumbers.finiteOr(value.y, 1.0), 0.125, 8.0),
         Math.clamp(io.github.fastformer.fastplace.geometry.GeometryNumbers.finiteOr(value.z, 1.0), 0.125, 8.0)
      );
   }
}
