package io.github.fastformer.client.input;

import io.github.fastformer.client.input.drag.WorkspaceFaceDrag;
import io.github.fastformer.client.input.drag.WorkspaceGizmoDrag;
import io.github.fastformer.client.operation.controller.ClientOperationController;
import io.github.fastformer.client.interaction.SelectionDragCapture;
import net.minecraft.client.Minecraft;

final class SelectionInputDispatcher {
   private SelectionInputDispatcher() { }

   static void dispatch(Minecraft minecraft, ClientInputSession session, SelectionPointerEvent event) {
      switch (event) {
         case SelectionPointerEvent.Press press -> {
            SelectionDragCapture capture = null;
            long routingToken = 0L;
            if (session.routing.dispatch(ClientInputStateMachine.InputKind.POINTER) == ClientInputStateMachine.Dispatch.OPERATION
               && !ClientOperationController.selectionGestures().active()) {
               SelectionGestureController.press(session, minecraft, press.snapshot());
               capture = ClientOperationController.selectionGestures().capture();
               if (capture != null) {
                  routingToken = session.routing.beginGesture(press.snapshot().button());
                  session.clickGestureToken = routingToken;
                  session.operationClickCapturedButton = press.snapshot().button();
                  if (press.alt()) session.modifier.consume();
               }
            }
            session.selectionPointer.dispatched(press.identity(), capture, routingToken);
         }
         case SelectionPointerEvent.Release release ->
            settleSelectionPointer(session, session.selectionPointer.take(release.identity()), false);
         case SelectionPointerEvent.Cancel cancel ->
            settleSelectionPointer(session, session.selectionPointer.take(cancel.identity()), true);
         case SelectionPointerEvent.CreatePress press -> {
            handleSelectionDraftPress(session, press.snapshot());
            session.selectionPointer.dispatched(press.identity(), null, 0L);
         }
         case SelectionPointerEvent.PointPress press -> {
            var snapshot = press.snapshot();
            if (session.routing.dispatch(ClientInputStateMachine.InputKind.POINTER) == ClientInputStateMachine.Dispatch.OPERATION
               && snapshot.matches(ClientOperationController.interactionScene().owner(), ClientOperationController.workspace())
               && !ClientOperationController.selectionGestures().active() && snapshot.point() != null) {
               var decision = ClientOperationController.adjustAabbPoint(snapshot.partId(), snapshot.button(), snapshot.point());
               ClientInteractionFeedback.showAabbAdjustFailure(minecraft, decision);
            }
            session.selectionPointer.dispatched(press.identity(), null, 0L);
         }
      }
   }

   private static void handleSelectionDraftPress(ClientInputSession session, SelectionDraftPress press) {
      if (session.routing.dispatch(ClientInputStateMachine.InputKind.CREATE_SELECTION) != ClientInputStateMachine.Dispatch.OPERATION
         || !press.matches(ClientOperationController.interactionScene().owner(), ClientOperationController.draftSelectionMode())
         || ClientOperationController.workspaceSubmissionPending() || ClientOperationController.workspace().locked()
         || ClientOperationController.selectionGestures().active()) return;
      if (press.alt()) {
         ClientOperationController.setAltMode(true);
         session.modifier.consume();
         if (press.point() != null) ClientOperationController.handleAltCreateClick(press.button(), press.point());
      } else {
         ClientOperationController.handleCreateClick(press.button(), press.point());
      }
   }

   private static void settleSelectionPointer(ClientInputSession session, SelectionPointerCapture.Dispatch dispatched, boolean cancelled) {
      if (dispatched == null || dispatched.drag() == null) return;
      SelectionDragCapture capture = dispatched.drag();
      var gestures = ClientOperationController.selectionGestures();
      var drag = gestures.capturedBy(capture);
      if (drag != null) {
         if (cancelled) {
            ClientOperationController.cancelTransformGesture(drag.editToken());
            SelectionGestureController.finishPointer(session, capture.gestureToken());
            gestures.clear(drag);
         } else if (drag instanceof WorkspaceFaceDrag) {
            SelectionGestureController.finishFace(session);
         } else if (drag instanceof WorkspaceGizmoDrag) {
            SelectionGestureController.finishGizmo(session);
         }
      }
      if (session.clickGestureToken == dispatched.routingToken()) {
         session.routing.finishGesture(capture.mouseButton(), dispatched.routingToken());
         session.clickGestureToken = 0L;
         session.operationClickCapturedButton = -1;
      }
   }

}
