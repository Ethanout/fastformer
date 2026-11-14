package io.github.fastformer.client.input;

import io.github.fastformer.client.session.ClientSessionSystem;

import io.github.fastformer.client.input.drag.WorkspaceFaceDrag;
import io.github.fastformer.client.input.drag.WorkspaceGizmoDrag;
import io.github.fastformer.client.input.state.ClientInputStateMachine;
import io.github.fastformer.client.interaction.SelectionDragCapture;
import io.github.fastformer.client.operation.input.SelectionDraftPress;
import io.github.fastformer.client.operation.input.SelectionPointerCapture;
import io.github.fastformer.client.operation.input.SelectionPointerEvent;
import net.minecraft.client.Minecraft;

final class SelectionInputDispatcher {
   private SelectionInputDispatcher() { }

   static void dispatch(Minecraft minecraft, ClientInputSession session, SelectionPointerEvent event) {
      switch (event) {
         case SelectionPointerEvent.Press press -> {
            SelectionDragCapture capture = null;
            long routingToken = 0L;
            if (session.routing.dispatch(ClientInputStateMachine.InputKind.POINTER) == ClientInputStateMachine.Dispatch.OPERATION
               && !session.selectionGestures().active()) {
               SelectionGestureController.press(session, minecraft, press.snapshot());
               capture = session.selectionGestures().capture();
               if (capture != null) {
                  routingToken = session.routing.beginGesture(press.snapshot().button());
                  session.clickGestureToken = routingToken;
                  session.operationClick.capture(press.snapshot().button());
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
               && snapshot.matches(ClientSessionSystem.instance().operations().interactionScene().owner(), ClientSessionSystem.instance().operations().workspace())
               && !session.selectionGestures().active() && snapshot.point() != null) {
               var decision = ClientSessionSystem.instance().operations().transforms().adjustAabbPoint(snapshot.partId(), snapshot.button(), snapshot.point());
               ClientInteractionFeedback.showAabbAdjustFailure(minecraft, decision);
            }
            session.selectionPointer.dispatched(press.identity(), null, 0L);
         }
      }
   }

   private static void handleSelectionDraftPress(ClientInputSession session, SelectionDraftPress press) {
      if (session.routing.dispatch(ClientInputStateMachine.InputKind.CREATE_SELECTION) != ClientInputStateMachine.Dispatch.OPERATION
         || !press.matches(ClientSessionSystem.instance().operations().interactionScene().owner(), ClientSessionSystem.instance().operations().draftSelectionMode())
         || ClientSessionSystem.instance().operations().workspaceSubmissionPending() || ClientSessionSystem.instance().operations().workspace().locked()
         || session.selectionGestures().active()) return;
      if (press.alt()) {
         ClientSessionSystem.instance().operations().setAltMode(true);
         session.modifier.consume();
         if (press.point() != null || press.button() == 0 && press.mode() == io.github.fastformer.fastplace.selection.OperationSelectionMode.CUBOID)
            ClientSessionSystem.instance().operations().handleAltCreateClick(press.button(), press.point());
      } else {
         ClientSessionSystem.instance().operations().handleCreateClick(press.button(), press.point());
      }
   }

   private static void settleSelectionPointer(ClientInputSession session, SelectionPointerCapture.Dispatch dispatched, boolean cancelled) {
      if (dispatched == null || dispatched.drag() == null) return;
      SelectionDragCapture capture = dispatched.drag();
      var gestures = session.selectionGestures();
      var drag = gestures.capturedBy(capture);
      if (drag != null) {
         if (cancelled) {
            ClientSessionSystem.instance().operations().transforms().cancelTransformGesture(drag.editToken());
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
         session.operationClick.clear();
      }
   }

}
