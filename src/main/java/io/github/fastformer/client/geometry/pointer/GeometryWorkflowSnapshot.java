package io.github.fastformer.client.geometry.pointer;

import io.github.fastformer.fastplace.workflow.GeometryWorkflowView;
import io.github.fastformer.protocol.s2c.geometry.GeometryPreviewPayload;
import net.minecraft.world.phys.Vec3;

/** Adapts accepted geometry data to the shared workflow without client lookups. */
public final class GeometryWorkflowSnapshot {
   private GeometryWorkflowSnapshot() {}

   public static GeometryWorkflowView view(GeometryPreviewPayload snapshot, boolean modifierHeld, Vec3 view) {
      return new GeometryWorkflowView(snapshot.mode(), snapshot.points().size(), snapshot.pointLocations(), snapshot.pointRoles(),
         snapshot.closed(), modifierHeld, snapshot.polyhedronShapeVariant(), snapshot.coneShapeVariant(), snapshot.compoundShapeVariant(),
         snapshot.polyhedronSizeMode(), snapshot.extrusion(), snapshot.conePlaneMode(), snapshot.coneRadius(), snapshot.coneScaleX(),
         snapshot.coneScaleZ(), snapshot.coneTopScaleOffset(), snapshot.coneTopOffset(), snapshot.coneRotationRadians(), snapshot.coneGizmoLocal(),
         snapshot.rotation(), snapshot.polyhedronLocalScale(), snapshot.polyhedronWorldScale(), snapshot.polyhedronGizmoLocal(),
         snapshot.fillMode(), view, snapshot.selectedPointIndex());
   }
}
