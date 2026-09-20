package io.github.fastformer.client.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import io.github.fastformer.client.input.drag.GeometryGizmoDrag;
import io.github.fastformer.fastplace.geometry.AxisGizmo;
import net.minecraft.world.phys.Vec3;

class GeometryDragControllerTest {
   @Test
   void inactiveGeometryReleasesCaptureBeforeAccessingWorld() {
      var session = capture(PointerGestureState.Kind.BUILDING_GEOMETRY);
      GeometryDragController.update(null, session, false);
      assertNull(session.geometryGizmoDrag);
      assertEquals(PointerGestureState.Kind.NONE, session.pointerGesture.kind());
      assertEquals(0L, session.pointerGestureToken);
      GeometryDragController.finish(null, session);
      assertEquals(PointerGestureState.Kind.NONE, session.pointerGesture.kind());
   }

   @Test
   void inactiveOperationReleaseClearsItsCapture() {
      var session = capture(PointerGestureState.Kind.OPERATION_GIZMO);
      GeometryDragController.finish(null, session);
      assertNull(session.geometryGizmoDrag);
      assertEquals(PointerGestureState.Kind.NONE, session.pointerGesture.kind());
      assertEquals(0L, session.pointerGestureToken);
   }

   @Test
   void staleReleaseDiscardsOldDragWithoutFinishingNewGesture() {
      var session = capture(PointerGestureState.Kind.BUILDING_GEOMETRY);
      long next = session.pointerGesture.begin(PointerGestureState.Kind.WORKSPACE_GIZMO);
      session.pointerGestureToken = next;
      GeometryDragController.finish(null, session);
      assertNull(session.geometryGizmoDrag);
      assertEquals(next, session.pointerGestureToken);
      assertTrue(session.pointerGesture.owns(next, PointerGestureState.Kind.WORKSPACE_GIZMO));
   }

   @Test
   void staleTickCannotAccessWorldOrReleaseReplacementCapture() {
      var session = capture(PointerGestureState.Kind.BUILDING_GEOMETRY);
      long next = session.pointerGesture.begin(PointerGestureState.Kind.BUILDING_GEOMETRY);
      session.pointerGestureToken = next;
      GeometryDragController.update(null, session, false);
      assertNull(session.geometryGizmoDrag);
      assertTrue(session.pointerGesture.owns(next, PointerGestureState.Kind.BUILDING_GEOMETRY));
      GeometryDragController.finish(null, session);
      assertTrue(session.pointerGesture.owns(next, PointerGestureState.Kind.BUILDING_GEOMETRY));
   }

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
