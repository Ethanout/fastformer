package io.github.fastformer.server.sync;

import io.github.fastformer.common.geometry.session.GeometrySession;
import io.github.fastformer.common.geometry.session.WallGeometryState;
import io.github.fastformer.common.geometry.session.ConePrismGeometryState;
import io.github.fastformer.common.geometry.session.PolyhedronGeometryState;
import io.github.fastformer.common.geometry.session.CompoundGeometryState;
import io.github.fastformer.common.geometry.session.ConvexPolyhedronGeometryState;

import io.github.fastformer.common.geometry.model.FillMode;
import io.github.fastformer.common.geometry.ConePlaneMode;
import io.github.fastformer.common.geometry.PolyhedronSizeMode;
import io.github.fastformer.common.selection.session.*;
import io.github.fastformer.protocol.callback.OperationCallbackScope;
import io.github.fastformer.protocol.s2c.geometry.GeometryPreviewPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Maps typed live geometry to the protocol's fixed field order. Neutral wire fields stay at this boundary. */
public final class GeometryPreviews {
   private GeometryPreviews() {}

   public static GeometryPreviewPayload capture(GeometrySession session, boolean modifierHeld,
      FillMode fillMode, OperationCallbackScope scope) {
      var values = new WireValues();
      switch (session.shape()) {
         case WallGeometryState wall -> values.extrusion = wall.extrusion();
         case ConePrismGeometryState cone -> values.cone(cone);
         case PolyhedronGeometryState polyhedron -> values.polyhedron(polyhedron);
         case CompoundGeometryState compound -> values.compoundVariant = compound.compoundShapeVariant();
         case ConvexPolyhedronGeometryState convex -> values.selectedPoint = convex.selectedControlPoint();
      }
      return new GeometryPreviewPayload(0L, session.draftId(), true, session.mode(), session.points(),
         session.pointLocations(), session.pointRoles(), session.closed(), modifierHeld, values.extrusion,
         0, values.coneVariant, values.compoundVariant, values.sizeMode, fillMode, values.planeMode,
         values.radius, values.scaleX, values.scaleZ, values.topScaleOffset, values.topOffset, values.rotationRadians,
         values.coneLocal, values.rotation, values.localScale, values.worldScale, values.polyhedronLocal,
         values.selectedPoint, scope);
   }

   private static final class WireValues {
      private BlockPos extrusion = BlockPos.ZERO;
      private int coneVariant;
      private int compoundVariant;
      private PolyhedronSizeMode sizeMode = PolyhedronSizeMode.RADIUS;
      private ConePlaneMode planeMode = ConePlaneMode.RADIUS;
      private double radius = 1.0;
      private double scaleX = 1.0;
      private double scaleZ = 1.0;
      private double topScaleOffset;
      private Vec3 topOffset = Vec3.ZERO;
      private double rotationRadians;
      private boolean coneLocal = true;
      private double[] rotation = {1, 0, 0, 0, 1, 0, 0, 0, 1};
      private Vec3 localScale = new Vec3(1, 1, 1);
      private Vec3 worldScale = new Vec3(1, 1, 1);
      private boolean polyhedronLocal;
      private int selectedPoint = -1;

      private void cone(ConePrismGeometryState cone) {
         coneVariant = cone.coneShapeVariant();
         planeMode = cone.conePlaneMode();
         radius = cone.coneRadius();
         scaleX = cone.coneScaleX();
         scaleZ = cone.coneScaleZ();
         topScaleOffset = cone.coneTopScaleOffset();
         topOffset = cone.coneTopOffset();
         rotationRadians = cone.coneRotationRadians();
         coneLocal = cone.coneGizmoLocal();
      }

      private void polyhedron(PolyhedronGeometryState polyhedron) {
         sizeMode = polyhedron.polyhedronSizeMode();
         rotation = polyhedron.rotation();
         localScale = polyhedron.polyhedronLocalScale();
         worldScale = polyhedron.polyhedronWorldScale();
         polyhedronLocal = polyhedron.polyhedronGizmoLocal();
      }
   }
}
