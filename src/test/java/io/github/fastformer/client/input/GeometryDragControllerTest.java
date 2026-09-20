package io.github.fastformer.client.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;
import io.github.fastformer.client.input.drag.GeometryGizmoDrag;
import io.github.fastformer.fastplace.geometry.AxisGizmo;
import net.minecraft.world.phys.Vec3;

class GeometryDragControllerTest {
   @Test
   void geometryCaptureCannotBecomeAnOperationWhenSelectionAppears() {
      var session = capture(PointerGestureState.Kind.BUILDING_GEOMETRY);
      assertEquals(GeometryDragController.Target.GEOMETRY, GeometryDragController.target(session, true, true));
      assertEquals(GeometryDragController.Target.NONE, GeometryDragController.target(session, false, true));
   }

   @Test
   void operationCaptureCannotFallBackToGeometryWhenSelectionDisappears() {
      var session = capture(PointerGestureState.Kind.OPERATION_GIZMO);
      assertEquals(GeometryDragController.Target.OPERATION, GeometryDragController.target(session, true, true));
      assertEquals(GeometryDragController.Target.NONE, GeometryDragController.target(session, true, false));
   }

   @Test
   void replacedOrCancelledCaptureCannotRouteOldDrag() {
      var session = capture(PointerGestureState.Kind.BUILDING_GEOMETRY);
      session.pointerGestureToken = session.pointerGesture.begin(PointerGestureState.Kind.OPERATION_GIZMO);
      session.geometryGizmoDrag = session.geometryGizmoDrag.withSentSteps(2);
      assertEquals(GeometryDragController.Target.NONE, GeometryDragController.target(session, true, true));
      session.pointerGesture.cancel();
      assertEquals(GeometryDragController.Target.NONE, GeometryDragController.target(session, true, true));
   }

   private static ClientInputSession capture(PointerGestureState.Kind kind) {
      var session = new ClientInputSession();
      session.pointerGestureToken = session.pointerGesture.begin(kind);
      session.geometryGizmoDrag = new GeometryGizmoDrag(AxisGizmo.Operation.MOVE, AxisGizmo.Axis.X,
         Vec3.ZERO, new Vec3(1, 0, 0), 0, 0, Vec3.ZERO, Vec3.ZERO, Vec3.ZERO,
         AxisGizmo.Direction.POSITIVE, 0).withCapture(session.pointerGestureToken);
      return session;
   }
}
