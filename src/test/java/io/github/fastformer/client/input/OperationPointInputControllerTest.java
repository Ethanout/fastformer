package io.github.fastformer.client.input;

import static org.junit.jupiter.api.Assertions.*;
import io.github.fastformer.client.input.drag.OperationPointDrag;
import io.github.fastformer.fastplace.OperationPointDragConstraint;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class OperationPointInputControllerTest {
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
