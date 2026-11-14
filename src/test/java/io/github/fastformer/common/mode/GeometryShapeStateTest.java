package io.github.fastformer.common.mode;

import io.github.fastformer.common.geometry.session.GeometrySession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fastformer.common.geometry.GeometryMode;
import io.github.fastformer.common.geometry.ControlPointRole;
import io.github.fastformer.common.geometry.PolyhedronSizeMode;
import java.util.List;
import java.util.UUID;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class GeometryShapeStateTest {
   @Test
   void modeSwitchCreatesIndependentNamedState() {
      GeometrySession session = new GeometrySession();
      session.setMode(GeometryMode.POLYHEDRON);
      assertEquals(0, session.polyhedron().polyhedronShapeVariant());

      session.setMode(GeometryMode.CONE_PRISM);
      session.cone().setConeShapeVariant(2);
      assertEquals(2, session.cone().coneShapeVariant());

      session.setMode(GeometryMode.COMPOUND);
      session.compound().cycleCompoundShapeVariant(1);
      assertEquals(1, session.compound().compoundShapeVariant());

      session.setMode(GeometryMode.POLYHEDRON);
      assertEquals(0, session.polyhedron().polyhedronShapeVariant());
   }

   @Test
   void modeSwitchReplacesTheDraftIdentity() {
      GeometrySession session = new GeometrySession();
      UUID originalDraft = session.draftId();

      session.setMode(GeometryMode.CONVEX_POLYHEDRON);

      assertFalse(originalDraft.equals(session.draftId()));
   }

   @Test
   void polyhedronInputsCarryRolesInSessionState() {
      GeometrySession session = new GeometrySession();
      session.setMode(GeometryMode.POLYHEDRON);
      session.addPoint(point(0.5, 0.5, 0.5));
      session.addPoint(point(3.5, 0.5, 0.5));

      assertEquals(List.of(ControlPointRole.CENTER, ControlPointRole.RADIUS), session.pointRoles());

      session.polyhedron().setPolyhedronSizeMode(PolyhedronSizeMode.DIAMETER);
      session.addPoint(point(0.5, 0.5, 0.5));
      session.addPoint(point(4.5, 0.5, 0.5));

      assertEquals(List.of(ControlPointRole.DIAMETER_A, ControlPointRole.DIAMETER_B), session.pointRoles());
   }

   @Test
   void coneHeightIsNamedInsteadOfInferredByAConsumer() {
      GeometrySession session = new GeometrySession();
      session.setMode(GeometryMode.CONE_PRISM);
      session.addPoint(point(0.5, 0.5, 0.5));
      session.addPoint(point(4.5, 0.5, 0.5));
      session.addPoint(point(0.5, 5.5, 0.5));

      assertEquals(
         List.of(ControlPointRole.BASE_CENTER, ControlPointRole.RADIUS, ControlPointRole.HEIGHT),
         session.pointRoles()
      );
      assertEquals(point(0.5, 5.5, 0.5), session.cone().conePoints().heightPointLocation().orElseThrow());
   }

   @Test
   void backKeepsEverySelectedGeometryWorkflowForOneMoreClick() {
      for (GeometryMode mode : GeometryMode.values()) {
         GeometrySession session = new GeometrySession();
         session.setMode(mode);
         session.addPoint(point(0.5, 0.5, 0.5));

         assertTrue(session.undoStep(), mode.name());
         assertTrue(session.points().isEmpty(), mode.name());
         assertTrue(session.canUndoStep(), mode.name());
         assertFalse(session.undoStep(), mode.name());
      }
   }

   @Test
   void sphereAdjustBackRestoresTheFirstPointAndRemovesTheSecond() {
      GeometrySession session = new GeometrySession();
      session.setMode(GeometryMode.POLYHEDRON);
      Vec3 center = point(0.5, 0.5, 0.5);
      session.addPoint(center);
      session.polyhedron().enterPolyhedronAdjust(point(4.5, 0.5, 0.5));
      session.polyhedron().movePolyhedron(new Vec3(3.0, 2.0, -1.0));

      assertTrue(session.closed());
      assertTrue(session.undoStep());

      assertFalse(session.closed());
      assertEquals(List.of(center), session.pointLocations());
      assertEquals(List.of(ControlPointRole.CENTER), session.pointRoles());
   }

   private static Vec3 point(double x, double y, double z) {
      return new Vec3(x, y, z);
   }
}
