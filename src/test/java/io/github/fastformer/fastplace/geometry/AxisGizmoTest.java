package io.github.fastformer.fastplace.geometry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fastformer.fastplace.geometry.controlpoint.ControlPointRole;
import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class AxisGizmoTest {
   @Test
   void moveShaftAcceptsExpandedToleranceButRejectsDistantRays() {
      var handle = new AxisGizmo.Handle(AxisGizmo.Operation.MOVE, AxisGizmo.Axis.X,
         AxisGizmo.Direction.POSITIVE, ControlPointRole.GIZMO_HANDLE);
      var gizmo = new AxisGizmo(Vec3.ZERO, 4, 0.25, TransformFrame.world(Vec3.ZERO), List.of(handle));
      assertNotNull(gizmo.hitTest(new Vec3(2, 0.28, 6), new Vec3(0, 0, -1), 32));
      assertNull(gizmo.hitTest(new Vec3(2, 0.32, 6), new Vec3(0, 0, -1), 32));
   }

   @Test
   void endpointsAcceptExpandedToleranceWithoutChangingVisualSize() {
      for (var operation : List.of(AxisGizmo.Operation.MOVE, AxisGizmo.Operation.SCALE)) {
         var handle = new AxisGizmo.Handle(operation, AxisGizmo.Axis.X,
            AxisGizmo.Direction.POSITIVE, ControlPointRole.GIZMO_HANDLE);
         var gizmo = new AxisGizmo(Vec3.ZERO, 4, 0.25, TransformFrame.world(Vec3.ZERO), List.of(handle));
         double radius = gizmo.visualRadius(handle);
         assertEquals(operation == AxisGizmo.Operation.MOVE ? 0.25 : 0.2, radius, 1.0E-9);
         Vec3 endpoint = gizmo.handleCenter(handle);
         assertNotNull(gizmo.hitTest(endpoint.add(0, radius * 1.4, 6), new Vec3(0, 0, -1), 32));
         assertNull(gizmo.hitTest(endpoint.add(0, radius * 1.6, 6), new Vec3(0, 0, -1), 32));
      }
   }

   @Test
   void rotationRingAcceptsExpandedToleranceButRejectsDistantRays() {
      var handle = new AxisGizmo.Handle(AxisGizmo.Operation.ROTATE, AxisGizmo.Axis.Z,
         AxisGizmo.Direction.BIDIRECTIONAL, ControlPointRole.GIZMO_HANDLE);
      var gizmo = new AxisGizmo(Vec3.ZERO, 4, 0.25, TransformFrame.world(Vec3.ZERO), List.of(handle));
      double radius = gizmo.rotationRingRadius(handle);
      assertNotNull(gizmo.hitTest(new Vec3(radius + 0.44, 0, 6), new Vec3(0, 0, -1), 32));
      assertNull(gizmo.hitTest(new Vec3(radius + 0.5, 0, 6), new Vec3(0, 0, -1), 32));
   }

   @Test
   void moveAxisShaftCanBeHoveredAndDragged() {
      AxisGizmo.Handle moveX = new AxisGizmo.Handle(
         AxisGizmo.Operation.MOVE,
         AxisGizmo.Axis.X,
         AxisGizmo.Direction.POSITIVE,
         ControlPointRole.GIZMO_HANDLE
      );
      AxisGizmo gizmo = new AxisGizmo(Vec3.ZERO, 4.0, 0.25, TransformFrame.world(Vec3.ZERO), List.of(moveX));
      Vec3 eye = new Vec3(2.0, 2.0, 6.0);
      Vec3 target = new Vec3(2.0, 0.0, 0.0);

      AxisGizmo.Hit hit = gizmo.hitTest(eye, target.subtract(eye), 32.0);

      assertNotNull(hit);
      assertEquals(moveX.key(), hit.handle().key());
   }

   @Test
   void moveHandleOutranksAnOverlappingRotationHandle() {
      AxisGizmo.Handle move = new AxisGizmo.Handle(
         AxisGizmo.Operation.MOVE, AxisGizmo.Axis.X, AxisGizmo.Direction.POSITIVE, ControlPointRole.GIZMO_HANDLE
      );
      AxisGizmo.Handle rotate = new AxisGizmo.Handle(
         AxisGizmo.Operation.ROTATE, AxisGizmo.Axis.X, AxisGizmo.Direction.BIDIRECTIONAL, ControlPointRole.GIZMO_HANDLE
      );
      AxisGizmo.Hit preferred = AxisGizmo.preferHit(List.of(
         new AxisGizmo.Hit(rotate, Vec3.ZERO, 1.0, 0.01),
         new AxisGizmo.Hit(move, Vec3.ZERO, 1.1, 0.20)
      ));

      assertEquals(move.key(), preferred.handle().key());
   }

   @Test
   void moveEndpointSitsWellOutsideRotationRing() {
      AxisGizmo.Handle move = new AxisGizmo.Handle(
         AxisGizmo.Operation.MOVE, AxisGizmo.Axis.X, AxisGizmo.Direction.POSITIVE, ControlPointRole.GIZMO_HANDLE
      );
      AxisGizmo.Handle rotate = new AxisGizmo.Handle(
         AxisGizmo.Operation.ROTATE, AxisGizmo.Axis.X, AxisGizmo.Direction.BIDIRECTIONAL, ControlPointRole.GIZMO_HANDLE
      );
      AxisGizmo gizmo = new AxisGizmo(Vec3.ZERO, 4.0, 0.25, TransformFrame.world(Vec3.ZERO), List.of(move, rotate));

      assertTrue(gizmo.endpointDistance(move) > gizmo.rotationRingRadius(rotate) * 1.5);
   }

   @Test
   void moveShaftFillsTheConnectionToItsArrowHead() {
      AxisGizmo gizmo = AxisGizmo.world(Vec3.ZERO, 4.0, 0.25);
      AxisGizmo.Handle move = gizmo.handles().stream()
         .filter(handle -> handle.operation() == AxisGizmo.Operation.MOVE
            && handle.axis() == AxisGizmo.Axis.X
            && handle.direction() == AxisGizmo.Direction.POSITIVE)
         .findFirst().orElseThrow();
      Vec3 target = gizmo.center().add(gizmo.axisVector(AxisGizmo.Axis.X).scale(4.25));
      Vec3 eye = new Vec3(4.25, 2.0, 6.0);

      AxisGizmo.Hit hit = gizmo.hitTest(eye, target.subtract(eye), 32.0);

      assertNotNull(hit);
      assertEquals(move.key(), hit.handle().key());
   }

   @Test
   void scaleEndpointWinsWhenCursorIsCloserThanOverlappingMoveArrow() {
      AxisGizmo gizmo = AxisGizmo.world(Vec3.ZERO, 4.0, 0.25);
      AxisGizmo.Handle scale = gizmo.handles().stream()
         .filter(handle -> handle.operation() == AxisGizmo.Operation.SCALE
            && handle.axis() == AxisGizmo.Axis.X
            && handle.direction() == AxisGizmo.Direction.POSITIVE)
         .findFirst().orElseThrow();
      Vec3 target = gizmo.handleCenter(scale);
      Vec3 eye = new Vec3(target.x, 2.0, 6.0);

      AxisGizmo.Hit hit = gizmo.hitTest(eye, target.subtract(eye), 32.0);

      assertNotNull(hit);
      assertEquals(scale.key(), hit.handle().key());
   }
}
