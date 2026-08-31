package io.github.fastformer.fastplace;

import io.github.fastformer.fastplace.world.*;

import io.github.fastformer.fastplace.session.*;
import io.github.fastformer.fastplace.workflow.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fastformer.fastplace.geometry.ControlPoint;
import io.github.fastformer.fastplace.geometry.ControlPointRole;
import io.github.fastformer.fastplace.geometry.ControlPointState;
import io.github.fastformer.fastplace.geometry.GeometryPreviewGuides;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class PolyhedronSizeModeTest {
   @Test
   void diameterDerivesBlockCenterForAnEvenDiameter() {
      GeometryPoints.Polyhedron input = GeometryPoints.polyhedron(
         List.of(point(10.5), point(14.5)),
         List.of(ControlPointRole.DIAMETER_A, ControlPointRole.DIAMETER_B),
         PolyhedronSizeMode.DIAMETER
      );

      assertEquals(point(12.5), input.center().orElseThrow());
      assertEquals(2.0, input.radius(0.0));
      assertEquals(point(14.5), input.radiusPoint().orElseThrow());
   }

   @Test
   void diameterDerivesHalfGridCenterForAnOddDiameter() {
      GeometryPoints.Polyhedron input = GeometryPoints.polyhedron(
         List.of(point(10.5), point(15.5)),
         List.of(ControlPointRole.DIAMETER_A, ControlPointRole.DIAMETER_B),
         PolyhedronSizeMode.DIAMETER
      );

      assertEquals(point(13.0), input.center().orElseThrow());
      assertEquals(2.5, input.radius(0.0));
   }

   @Test
   void diameterSessionReturnsToSecondPointAfterAdjustmentRollback() {
      GeometrySession session = new GeometrySession();
      session.setMode(GeometryMode.POLYHEDRON);
      session.setPolyhedronSizeMode(PolyhedronSizeMode.DIAMETER);
      session.addPoint(point(10.5));

      assertFalse(session.hasPolyhedronCenter());
      session.enterPolyhedronAdjust(point(14.5));

      assertTrue(session.closed());
      assertEquals(List.of(point(10.5), point(14.5)), session.pointLocations());
      assertEquals(point(12.5), session.polyhedronPoints().center().orElseThrow());

      assertTrue(session.removeOrUndo(new BlockPos(99, 99, 99)));
      assertFalse(session.closed());
      assertEquals(1, session.pointLocations().size());
   }

   @Test
   void changingSizeModeClearsIncompatibleInput() {
      GeometrySession session = new GeometrySession();
      session.setMode(GeometryMode.POLYHEDRON);
      session.addPoint(point(10.5));

      session.setPolyhedronSizeMode(PolyhedronSizeMode.DIAMETER);

      assertEquals(PolyhedronSizeMode.DIAMETER, session.polyhedronSizeMode());
      assertTrue(session.pointLocations().isEmpty());
   }

   @Test
   void diameterControlPointsKeepEndpointRolesAndAddADerivedCenter() {
      List<ControlPoint> controls = GeometryPreviewGuides.polyhedronPoints(
         List.of(point(10.5), point(14.5)),
         List.of(ControlPointRole.DIAMETER_A, ControlPointRole.DIAMETER_B),
         PolyhedronSizeMode.DIAMETER,
         2
      );

      assertEquals(List.of(ControlPointRole.DIAMETER_A, ControlPointRole.DIAMETER_B, ControlPointRole.DERIVED_CENTER),
         controls.stream().map(ControlPoint::role).toList());
      assertEquals(ControlPointState.DERIVED, controls.get(2).state());
   }

   private static Vec3 point(double x) {
      return new Vec3(x, 20.5, 30.5);
   }
}
