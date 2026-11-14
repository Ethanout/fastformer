package io.github.fastformer.fastplace.workflow;

import io.github.fastformer.fastplace.geometry.FillMode;
import io.github.fastformer.fastplace.geometry.GeometryMode;
import io.github.fastformer.fastplace.geometry.GeometryNumbers;
import io.github.fastformer.fastplace.geometry.controlpoint.ControlPointRole;
import io.github.fastformer.fastplace.session.*;
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
      Context context = new Context(session.points().size(), session.pointLocations(), session.pointRoles(),
         session.closed(), modifierHeld, FillMode.OUTLINE, new Vec3(0, 0, 1));
      return switch (session.shape()) {
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
