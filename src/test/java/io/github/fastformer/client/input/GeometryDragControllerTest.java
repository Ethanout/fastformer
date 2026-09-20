package io.github.fastformer.client.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

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
      session.pointerGesture.begin(PointerGestureState.Kind.OPERATION_GIZMO);
      assertEquals(GeometryDragController.Target.NONE, GeometryDragController.target(session, true, true));
      session.pointerGesture.cancel();
      assertEquals(GeometryDragController.Target.NONE, GeometryDragController.target(session, true, true));
   }

   private static ClientInputSession capture(PointerGestureState.Kind kind) {
      var session = new ClientInputSession();
      session.pointerGestureToken = session.pointerGesture.begin(kind);
      return session;
   }
}
