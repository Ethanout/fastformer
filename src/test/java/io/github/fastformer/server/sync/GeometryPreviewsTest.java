package io.github.fastformer.server.sync;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.fastplace.geometry.FillMode;
import io.github.fastformer.fastplace.geometry.GeometryMode;
import io.github.fastformer.fastplace.geometry.cone.ConePlaneMode;
import io.github.fastformer.fastplace.session.GeometrySession;
import io.github.fastformer.protocol.callback.OperationCallbackScope;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class GeometryPreviewsTest {
   @Test
   void allShapesPublishCommonIdentityAndOriginalNeutralWireValues() {
      var session = new GeometrySession();
      for (GeometryMode mode : GeometryMode.values()) {
         session.setMode(mode);
         session.addPoint(BlockPos.ZERO);
         var payload = GeometryPreviews.capture(session, true, FillMode.SOLID, OperationCallbackScope.unscoped());
         assertEquals(mode, payload.mode());
         assertEquals(session.draftId(), payload.draftId());
         assertEquals(session.pointLocations(), payload.pointLocations());
         assertEquals(session.pointRoles(), payload.pointRoles());
         assertTrue(payload.ctrlHeld());
         assertEquals(FillMode.SOLID, payload.fillMode());
         assertEquals(BlockPos.ZERO, payload.extrusion());
         assertEquals(0, payload.polyhedronShapeVariant());
         assertEquals(0, payload.coneShapeVariant());
         assertEquals(0, payload.compoundShapeVariant());
         assertEquals(ConePlaneMode.RADIUS, payload.conePlaneMode());
         assertEquals(1, payload.coneRadius());
         assertEquals(1, payload.coneScaleX());
         assertEquals(1, payload.coneScaleZ());
         assertEquals(Vec3.ZERO, payload.coneTopOffset());
         assertTrue(payload.coneGizmoLocal());
         assertFalse(payload.polyhedronGizmoLocal());
         assertEquals(-1, payload.selectedPointIndex());
      }
   }

   @Test
   void shapeSpecificValuesAreNotLostAtTheProtocolBoundary() {
      var session = new GeometrySession();
      session.setMode(GeometryMode.CONE_PRISM);
      session.cone().setConePlaneMode(ConePlaneMode.THREE_POINT);
      session.cone().setConeScale(2, 3);
      session.cone().setConeShapeVariant(2);
      var payload = GeometryPreviews.capture(session, false, FillMode.OUTLINE, OperationCallbackScope.unscoped());
      assertEquals(ConePlaneMode.THREE_POINT, payload.conePlaneMode());
      assertEquals(2, payload.coneScaleX());
      assertEquals(3, payload.coneScaleZ());
      assertEquals(2, payload.coneShapeVariant());
   }
}
