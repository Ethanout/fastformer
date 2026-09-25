package io.github.fastformer.client.input;

import static org.junit.jupiter.api.Assertions.*;
import io.github.fastformer.client.input.drag.OperationPointDrag;
import io.github.fastformer.fastplace.OperationPointDragConstraint;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class OperationPointInputControllerTest {
   @Test
   void queuedCancelCannotClearReplacementDrag() {
      var session = capture();
      long previous = session.pointerGestureToken;
      session.operationPointPointer.dispatched(7L, 11L, previous);
      long replacement = session.pointerGesture.begin(PointerGestureState.Kind.OPERATION_POINT);
      session.pointerGestureToken = replacement;
      session.operationPointDrag = new OperationPointDrag(1, 0, BlockPos.ZERO, BlockPos.ZERO,
         null, Vec3.ZERO, null, 0, Vec3.ZERO, OperationPointDragConstraint.FREE, 20L, replacement);
      var current = session.operationPointDrag;
      session.lastOperationPointLeftClickAt = 30L;

      OperationPointInputController.dispatch(null, session, new OperationPointDragEvent.Cancel(7L));

      assertSame(current, session.operationPointDrag);
      assertTrue(session.pointerGesture.owns(replacement, PointerGestureState.Kind.OPERATION_POINT));
      assertEquals(30L, session.lastOperationPointLeftClickAt);
   }

   @Test
   void cancelReleasesOwnedCaptureAndClickHistoryOnlyOnce() {
      var session = capture();
      session.lastOperationPointLeftClickAt = 100L;
      session.lastOperationPointLeftClickIndex = 2;
      session.lastOperationPointRightClickAt = 200L;
      session.lastOperationPointRightClickIndex = 3;
      OperationPointInputController.cancel(session);
      OperationPointInputController.cancel(session);
      assertNull(session.operationPointDrag);
      assertEquals(0L, session.pointerGestureToken);
      assertEquals(PointerGestureState.Kind.NONE, session.pointerGesture.kind());
      assertEquals(0L, session.lastOperationPointLeftClickAt);
      assertEquals(-1, session.lastOperationPointLeftClickIndex);
      assertEquals(0L, session.lastOperationPointRightClickAt);
      assertEquals(-1, session.lastOperationPointRightClickIndex);
      assertNull(OperationPointInputController.finishOperationPointDrag(null, session));
   }

   @Test
   void cancelStaleDragPreservesReplacementCapture() {
      var session = capture();
      long next = session.pointerGesture.begin(PointerGestureState.Kind.OPERATION_POINT);
      session.pointerGestureToken = next;
      OperationPointInputController.cancel(session);
      assertNull(session.operationPointDrag);
      assertEquals(next, session.pointerGestureToken);
      assertTrue(session.pointerGesture.owns(next, PointerGestureState.Kind.OPERATION_POINT));
   }

   @Test
   void lateUpdateKeepsNewCaptureEvenWhenItHasSameKind() {
      var session = capture();
      long next = session.pointerGesture.begin(PointerGestureState.Kind.OPERATION_POINT);
      session.pointerGestureToken = next;
      session.operationPointDrag = session.operationPointDrag.withSentTarget(new BlockPos(1, 0, 0))
         .withConstraint(OperationPointDragConstraint.LINE);
      OperationPointInputController.updateOperationPointDrag(null, session);
      assertNull(session.operationPointDrag);
      assertEquals(next, session.pointerGestureToken);
      assertTrue(session.pointerGesture.owns(next, PointerGestureState.Kind.OPERATION_POINT));
   }

   @Test
   void lateReleaseCannotFinishNewWorkspaceCapture() {
      var session = capture();
      long next = session.pointerGesture.begin(PointerGestureState.Kind.WORKSPACE_GIZMO);
      session.pointerGestureToken = next;
      assertNull(OperationPointInputController.finishOperationPointDrag(null, session));
      assertNull(OperationPointInputController.finishOperationPointDrag(null, session));
      assertEquals(next, session.pointerGestureToken);
      assertTrue(session.pointerGesture.owns(next, PointerGestureState.Kind.WORKSPACE_GIZMO));
   }

   @Test
   void inactiveSelectionClearsOnlyItsOwnCaptureWithoutWorldAccess() {
      var session = capture();
      OperationPointInputController.updateOperationPointDrag(null, session);
      assertNull(session.operationPointDrag);
      assertEquals(0L, session.pointerGestureToken);
      assertEquals(PointerGestureState.Kind.NONE, session.pointerGesture.kind());
   }

   private static ClientInputSession capture() {
      var session = new ClientInputSession();
      session.pointerGestureToken = session.pointerGesture.begin(PointerGestureState.Kind.OPERATION_POINT);
      session.operationPointDrag = new OperationPointDrag(0, 0, BlockPos.ZERO, BlockPos.ZERO,
         null, Vec3.ZERO, null, 0, Vec3.ZERO, OperationPointDragConstraint.FREE, 10L,
         session.pointerGestureToken);
      return session;
   }
}
