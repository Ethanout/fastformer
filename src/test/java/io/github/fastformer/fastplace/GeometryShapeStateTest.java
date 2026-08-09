package io.github.fastformer.fastplace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fastformer.fastplace.geometry.ControlPointRole;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class GeometryShapeStateTest {
   @Test
   void modeSwitchCreatesIndependentNamedState() {
      GeometrySession session = new GeometrySession();
      session.setMode(GeometryMode.POLYHEDRON);
      assertEquals(0, session.polyhedronShapeVariant());

      session.setMode(GeometryMode.CONE_PRISM);
      session.setConeShapeVariant(2);
      assertEquals(2, session.coneShapeVariant());

      session.setMode(GeometryMode.COMPOUND);
      session.cycleCompoundShapeVariant(1);
      assertEquals(1, session.compoundShapeVariant());

      session.setMode(GeometryMode.POLYHEDRON);
      assertEquals(0, session.polyhedronShapeVariant());
   }

   @Test
   void polyhedronInputsCarryRolesInSessionState() {
      GeometrySession session = new GeometrySession();
      session.setMode(GeometryMode.POLYHEDRON);
      session.addPoint(point(0.5, 0.5, 0.5));
      session.addPoint(point(3.5, 0.5, 0.5));

      assertEquals(List.of(ControlPointRole.CENTER, ControlPointRole.RADIUS), session.pointRoles());

      session.setPolyhedronSizeMode(PolyhedronSizeMode.DIAMETER);
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
      assertEquals(point(0.5, 5.5, 0.5), session.conePoints().heightPointLocation().orElseThrow());
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
      session.enterPolyhedronAdjust(point(4.5, 0.5, 0.5));
      session.movePolyhedron(new Vec3(3.0, 2.0, -1.0));

      assertTrue(session.closed());
      assertTrue(session.undoStep());

      assertFalse(session.closed());
      assertEquals(List.of(center), session.pointLocations());
      assertEquals(List.of(ControlPointRole.CENTER), session.pointRoles());
   }

   @Test
   void clearingPointSelectionLeavesTheNextPointInputAvailable() {
      GeometrySession session = new GeometrySession();
      session.setMode(GeometryMode.CONVEX_POLYHEDRON);
      session.addPoint(point(0.5, 0.5, 0.5));
      session.addPoint(point(4.5, 0.5, 0.5));
      session.addPoint(point(0.5, 0.5, 4.5));
      session.selectControlPoint(1);

      session.clearSelectedControlPoint();
      assertEquals(-1, session.selectedControlPoint());

      ConvexPolyhedronWorkflow workflow = new ConvexPolyhedronWorkflow();
      workflow.onAddPoint(session, new GeometryActionContext(null, false), GeometryHit.point(new BlockPos(0, 4, 0)));

      assertEquals(4, session.points().size());
      assertEquals(-1, session.selectedControlPoint());
   }

   private static Vec3 point(double x, double y, double z) {
      return new Vec3(x, y, z);
   }
}
