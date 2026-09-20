package io.github.fastformer.client.input;

import static org.junit.jupiter.api.Assertions.*;
import io.github.fastformer.client.input.drag.DeferredDragClick;
import io.github.fastformer.client.input.drag.DragAxisFrame;
import io.github.fastformer.client.input.drag.OperationDrag;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class OperationDragControllerTest {
   @Test
   void lateUpdateCannotEndNewCaptureOfSameKind() {
      var session = capture();
      long next = session.pointerGesture.begin(PointerGestureState.Kind.OPERATION_FACE);
      session.pointerGestureToken = next;
      session.operationDrag = session.operationDrag.withSentSteps(3)
         .withFrame(DragAxisFrame.start(Vec3.ZERO, true))
         .withDeferredClick(DeferredDragClick.none());
      OperationDragController.update(null, session);
      assertNull(session.operationDrag);
      assertEquals(next, session.pointerGestureToken);
      assertTrue(session.pointerGesture.owns(next, PointerGestureState.Kind.OPERATION_FACE));
   }

   @Test
   void lateReleaseCannotEndNewWorkspaceCapture() {
      var session = capture();
      long next = session.pointerGesture.begin(PointerGestureState.Kind.WORKSPACE_GIZMO);
      session.pointerGestureToken = next;
      OperationDragController.finish(null, session, 20L);
      OperationDragController.finish(null, session, 30L);
      assertNull(session.operationDrag);
      assertEquals(next, session.pointerGestureToken);
      assertTrue(session.pointerGesture.owns(next, PointerGestureState.Kind.WORKSPACE_GIZMO));
   }

   @Test
   void inactivePreviewReleasesCaptureBeforeWorldAccess() {
      var session = capture();
      OperationDragController.update(null, session);
      assertNull(session.operationDrag);
      assertEquals(0L, session.pointerGestureToken);
      assertEquals(PointerGestureState.Kind.NONE, session.pointerGesture.kind());
   }

   @Test
   void cancelReleasesCaptureAndRepeatedCancelIsHarmless() {
      var session = capture();
      OperationDragController.cancel(session);
      OperationDragController.cancel(session);
      OperationDragController.finish(null, session, 20L);
      assertNull(session.operationDrag);
      assertEquals(0L, session.pointerGestureToken);
      assertEquals(PointerGestureState.Kind.NONE, session.pointerGesture.kind());
   }

   private static ClientInputSession capture() {
      var session = new ClientInputSession();
      session.pointerGestureToken = session.pointerGesture.begin(PointerGestureState.Kind.OPERATION_FACE);
      session.operationDrag = new OperationDrag(0, true, DragAxisFrame.start(Vec3.ZERO, false),
         new Vec3(1, 0, 0), 0, 0, null, null, 0.0, DeferredDragClick.none(),
         session.pointerGestureToken);
      return session;
   }
}
