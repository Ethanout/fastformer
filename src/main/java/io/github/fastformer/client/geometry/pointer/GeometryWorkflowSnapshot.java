package io.github.fastformer.client.geometry.pointer;

import io.github.fastformer.fastplace.workflow.*;
import io.github.fastformer.protocol.s2c.geometry.GeometryPreviewPayload;
import net.minecraft.world.phys.Vec3;

/** Adapts accepted geometry data to the shared workflow without client lookups. */
public final class GeometryWorkflowSnapshot {
   private GeometryWorkflowSnapshot() {}

   public static GeometryWorkflowView view(GeometryPreviewPayload snapshot, boolean modifierHeld, Vec3 view) {
      var context = new GeometryWorkflowView.Context(snapshot.points().size(), snapshot.pointLocations(), snapshot.pointRoles(),
         snapshot.closed(), modifierHeld, snapshot.fillMode(), view);
      return switch (snapshot.mode()) {
         case WALL -> new WallWorkflowView(context, snapshot.extrusion());
         case CONE_PRISM -> new ConeWorkflowView(context, snapshot.coneShapeVariant(), snapshot.conePlaneMode(), snapshot.coneRadius(),
            snapshot.coneScaleX(), snapshot.coneScaleZ(), snapshot.coneTopScaleOffset(), snapshot.coneTopOffset(),
            snapshot.coneRotationRadians(), snapshot.coneGizmoLocal());
         case POLYHEDRON -> new PolyhedronWorkflowView(context, snapshot.polyhedronShapeVariant(), snapshot.polyhedronSizeMode(),
            snapshot.rotation(), snapshot.polyhedronLocalScale(), snapshot.polyhedronWorldScale(), snapshot.polyhedronGizmoLocal());
         case COMPOUND -> new CompoundWorkflowView(context, snapshot.compoundShapeVariant());
         case CONVEX_POLYHEDRON -> new ConvexWorkflowView(context, snapshot.selectedPointIndex());
      };
   }
}
