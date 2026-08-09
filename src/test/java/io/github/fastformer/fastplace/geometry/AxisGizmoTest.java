package io.github.fastformer.fastplace.geometry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class AxisGizmoTest {
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
