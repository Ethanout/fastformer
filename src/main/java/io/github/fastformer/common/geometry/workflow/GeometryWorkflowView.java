package io.github.fastformer.common.geometry.workflow;

import io.github.fastformer.common.geometry.session.GeometrySession;
import io.github.fastformer.common.geometry.session.GeometryShapeState;
import io.github.fastformer.common.geometry.session.WallGeometryState;
import io.github.fastformer.common.geometry.session.ConePrismGeometryState;
import io.github.fastformer.common.geometry.session.PolyhedronGeometryState;
import io.github.fastformer.common.geometry.session.CompoundGeometryState;
import io.github.fastformer.common.geometry.session.ConvexPolyhedronGeometryState;

import io.github.fastformer.common.geometry.model.FillMode;
import io.github.fastformer.common.geometry.GeometryMode;
import io.github.fastformer.common.geometry.GeometryNumbers;
import io.github.fastformer.common.geometry.ControlPointRole;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.Vec3;

/** A shape-specific immutable input to shared geometry planning. */
public sealed interface GeometryWorkflowView permits WallWorkflowView, ConeWorkflowView,
   PolyhedronWorkflowView, CompoundWorkflowView, ConvexWorkflowView {
   GeometryMode mode();
   Context context();

   default int pointCount() { return context().pointCount(); }
   default List<Vec3> pointLocations() { return context().pointLocations(); }
   default List<ControlPointRole> pointRoles() { return context().pointRoles(); }
   default boolean closed() { return context().closed(); }
   default boolean modifierHeld() { return context().modifierHeld(); }
   default FillMode fillMode() { return context().fillMode(); }
   default Vec3 view() { return context().view(); }

   record Context(int pointCount, List<Vec3> pointLocations, List<ControlPointRole> pointRoles,
      boolean closed, boolean modifierHeld, FillMode fillMode, Vec3 view) {
      public Context {
         pointLocations = pointLocations == null ? List.of()
            : pointLocations.stream().map(GeometryNumbers::finiteOrZero).toList();
         pointCount = Math.clamp(pointCount, 0, pointLocations.size());
         pointRoles = pointRoles == null ? List.of() : List.copyOf(pointRoles);
         if (pointRoles.size() != pointLocations.size()) {
            ArrayList<ControlPointRole> fallback = new ArrayList<>(pointLocations.size());
            for (int index = 0; index < pointLocations.size(); index++)
               fallback.add(index == 0 ? ControlPointRole.PRIMARY : ControlPointRole.SECONDARY);
            pointRoles = List.copyOf(fallback);
         }
      }
   }

   static GeometryWorkflowView from(GeometrySession session) { return from(session, false); }

   static GeometryWorkflowView from(GeometrySession session, boolean modifierHeld) {
      return from(session.shape(), modifierHeld);
   }

   static GeometryWorkflowView from(GeometryShapeState shape, boolean modifierHeld) {
      Context context = new Context(shape.blockPoints().size(), shape.pointLocations(), shape.pointRoles(),
         shape.closed(), modifierHeld, FillMode.OUTLINE, new Vec3(0, 0, 1));
      return switch (shape) {
         case WallGeometryState wall -> new WallWorkflowView(context, wall.extrusion());
         case ConePrismGeometryState cone -> new ConeWorkflowView(context, cone.coneShapeVariant(),
            cone.conePlaneMode(), cone.coneRadius(), cone.coneScaleX(), cone.coneScaleZ(), cone.coneTopScaleOffset(),
            cone.coneTopOffset(), cone.coneRotationRadians(), cone.coneGizmoLocal());
         case PolyhedronGeometryState polyhedron -> new PolyhedronWorkflowView(context, polyhedron.polyhedronShapeVariant(),
            polyhedron.polyhedronSizeMode(), polyhedron.rotation(), polyhedron.polyhedronLocalScale(),
            polyhedron.polyhedronWorldScale(), polyhedron.polyhedronGizmoLocal());
         case CompoundGeometryState compound -> new CompoundWorkflowView(context, compound.compoundShapeVariant());
         case ConvexPolyhedronGeometryState convex -> new ConvexWorkflowView(context, convex.selectedControlPoint());
      };
   }
}
