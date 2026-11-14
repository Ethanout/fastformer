package io.github.fastformer.client.input;

import io.github.fastformer.client.session.ClientSessionSystem;

import io.github.fastformer.client.input.drag.DeferredDragClick;
import io.github.fastformer.client.input.drag.DragAxisFrame;
import io.github.fastformer.client.input.drag.GeometryGizmoDrag;
import io.github.fastformer.client.input.drag.GizmoDragCalculator;
import io.github.fastformer.client.input.drag.WorkspaceFaceDrag;
import io.github.fastformer.client.input.drag.WorkspaceGizmoDrag;
import io.github.fastformer.client.input.math.ClientInputMath;
import io.github.fastformer.client.input.state.PointerGestureState;
import io.github.fastformer.client.interaction.InteractionPressBinding;
import io.github.fastformer.client.interaction.SelectionDragCapture;
import io.github.fastformer.client.interaction.intent.OperationInteractionIntent;
import io.github.fastformer.client.operation.controller.OperationContext;
import io.github.fastformer.client.operation.input.SelectionPointerPress;
import io.github.fastformer.fastplace.geometry.AxisGizmo;
import io.github.fastformer.fastplace.geometry.OperationGeometry;
import io.github.fastformer.workspace.model.ClientSelectionPart;
import io.github.fastformer.workspace.transform.RepeatDragQuantizer;
import io.github.fastformer.workspace.transform.RepeatStrideSemantics;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

final class SelectionGestureController {
   private SelectionGestureController() { }

   static void cancelActive(ClientInputSession session) {
      var gestures = session.selectionGestures();
      var capture = gestures.capture();
      if (capture == null) return;
      var drag = gestures.capturedBy(capture);
      if (drag == null) return;
      ClientSessionSystem.instance().operations().cancelTransformGesture(drag.editToken());
      finishPointer(session, capture.gestureToken());
      gestures.clear(drag);
   }

   static void press(ClientInputSession session, Minecraft minecraft, SelectionPointerPress press) {
      if (!press.matches(ClientSessionSystem.instance().operations().interactionScene(), ClientSessionSystem.instance().operations().workspace())) return;
      switch (press.action()) {
         case InteractionPressBinding.DragGizmo action ->
            beginWorkspaceGizmoDrag(session, action.target(), press.button(), press.control());
         case InteractionPressBinding.SelectOrDragFace action ->
            selectOrBeginWorkspaceFace(session, minecraft, action.target(), press);
         case InteractionPressBinding.SelectPart action ->
            ClientSessionSystem.instance().operations().selectWorkspacePart(action.partId(), press.control());
      }
   }

   static boolean beginWorkspaceGizmoDrag(ClientInputSession session,
      OperationInteractionIntent.Gizmo target, int mouseButton, boolean control
   ) {
      if (session.selectionGestures().active()) return false;
      if (ClientSessionSystem.instance().operations().workspaceSubmissionPending()) {
         return false;
      }
      AxisGizmo.Handle handle = target.hit().handle();
      var workspace = ClientSessionSystem.instance().operations().workspace();
      if (!target.common()) {
         boolean selected = workspace.selectedIds().contains(target.partId());
         if (!selected) {
            ClientSessionSystem.instance().operations().selectWorkspacePart(target.partId(), control);
         }
         workspace.activate(target.partId());
      }
      var targets = WorkspaceGizmoDrag.targets(workspace, target.partId(), target.common());
      if (targets.isEmpty() || !workspace.beginEdit()) {
         return false;
      }
      var editToken = workspace.activeEditToken();
      Vec3 axis = target.gizmo().axisVector(handle.axis());
      if (handle.direction() == AxisGizmo.Direction.NEGATIVE) {
         axis = axis.scale(-1.0);
      }
      Vec3 radial = handle.drawsRing() ? target.hit().point().subtract(target.gizmo().center()) : Vec3.ZERO;
      if (handle.drawsRing() && radial.lengthSqr() < 1.0E-7) {
         ClientSessionSystem.instance().operations().cancelTransformGesture(editToken);
         return false;
      }
      session.pointerGestureToken = session.pointerGesture.begin(PointerGestureState.Kind.WORKSPACE_GIZMO);
      var capture = SelectionDragCapture.create(
         ClientSessionSystem.instance().operations().interactionScene().owner(), workspace, targets, mouseButton, session.pointerGestureToken
      );
      session.selectionGestures().begin(new WorkspaceGizmoDrag(
         target.partId(), target.common(), handle.operation(), handle.axis(),
         target.hit().point(), axis, 0, target.gizmo().center(),
         handle.drawsRing() ? radial.normalize() : Vec3.ZERO,
         handle.drawsRing() ? GizmoDragCalculator.rotationTangent(axis, radial.normalize()) : Vec3.ZERO,
         handle.direction(), capture, targets, editToken, 0.0, control
      ));
      return true;
   }

   private static OperationContext.AabbAdjustDecision beginWorkspaceFaceDrag(ClientInputSession session,
      OperationInteractionIntent.Face target, SelectionPointerPress press
   ) {
      if (session.selectionGestures().active()) {
         return OperationContext.AabbAdjustDecision.EDIT_UNAVAILABLE;
      }
      if (ClientSessionSystem.instance().operations().workspaceSubmissionPending()) {
         return OperationContext.AabbAdjustDecision.SUBMISSION_PENDING;
      }
      if (target == null || !target.adjustable()) {
         return OperationContext.AabbAdjustDecision.NO_TARGET;
      }
      var workspace = ClientSessionSystem.instance().operations().workspace();
      ClientSelectionPart part = workspace.part(target.partId()).orElse(null);
      OperationContext.AabbAdjustDecision decision = ClientSessionSystem.instance().operations().aabbAdjustDecision(part);
      if (!decision.ready() && decision != OperationContext.AabbAdjustDecision.CAPTURE_PENDING) {
         return decision;
      }
      return captureWorkspaceFaceDrag(session, target, press, part, !decision.ready());
   }

   static OperationContext.AabbAdjustDecision captureWorkspaceFaceDrag(ClientInputSession session,
      OperationInteractionIntent.Face target, SelectionPointerPress press, ClientSelectionPart part, boolean awaitingSource
   ) {
      var workspace = ClientSessionSystem.instance().operations().workspace();
      if (!workspace.selectedIds().contains(part.id())) {
         ClientSessionSystem.instance().operations().selectWorkspacePart(part.id(), false);
      }
      workspace.activate(part.id());
      if (!workspace.beginEdit()) {
         return OperationContext.AabbAdjustDecision.EDIT_UNAVAILABLE;
      }
      var editToken = workspace.activeEditToken();
      OperationGeometry.RayHit hit = target.hit();
      int axis = hit.axis();
      Vec3 worldAxis = ClientInputMath.worldAxis(axis);
      boolean positive = hit.normal().dot(worldAxis) > 0.0;
      session.pointerGestureToken = session.pointerGesture.begin(PointerGestureState.Kind.WORKSPACE_FACE);
      var capture = SelectionDragCapture.create(
         ClientSessionSystem.instance().operations().interactionScene().owner(), workspace, List.of(part), press.button(), session.pointerGestureToken
      );
      session.selectionGestures().begin(new WorkspaceFaceDrag(
         part, axis, positive, DragAxisFrame.start(hit.point(), false), hit.normal(), 0,
         capture, DeferredDragClick.start(press.pressedAtNanos(), press.shortPressSteps()), hit, editToken, awaitingSource
      ));
      return OperationContext.AabbAdjustDecision.DRAG_STARTED;
   }

   private static void selectOrBeginWorkspaceFace(ClientInputSession session,
      Minecraft minecraft, OperationInteractionIntent.Face target, SelectionPointerPress press
   ) {
      if (target == null) {
         return;
      }
      var workspace = ClientSessionSystem.instance().operations().workspace();
      if (!workspace.selectedIds().contains(target.partId())) {
         ClientSessionSystem.instance().operations().selectWorkspacePart(target.partId(), false);
      }
      workspace.activate(target.partId());
      if (target.adjustable()) {
         ClientInteractionFeedback.showAabbAdjustFailure(
            minecraft, beginWorkspaceFaceDrag(session, target, press)
         );
      }
   }

   static void finishGizmo(ClientInputSession session) {
      WorkspaceGizmoDrag drag = session.selectionGestures().gizmo();
      if (drag != null) {
         if (session.pointerGesture.owns(session.pointerGestureToken, PointerGestureState.Kind.WORKSPACE_GIZMO)
            && drag.capture().matches(ClientSessionSystem.instance().operations().interactionScene().owner(), ClientSessionSystem.instance().operations().workspace(), session.pointerGestureToken)) {
            if (drag.operation() == AxisGizmo.Operation.ROTATE) {
               double radians = RotationInputAngles.selectionFinish(drag.rotationRadians(), drag.freeRotation());
               String failure = ClientSessionSystem.instance().operations().updateTransformGesture(drag.editToken(), drag.baseline(),
                  drag.common(), drag.operation(), drag.axis(), 1, GizmoDragCalculator.rotationSteps(radians), radians,
                  drag.axisVector(), drag.center());
               ClientInteractionFeedback.show(Minecraft.getInstance(), failure);
            }
            ClientSessionSystem.instance().operations().finishTransformGesture(drag.editToken());
            if (session.modifier.held()) {
               session.modifier.consume();
            }
         } else {
            ClientSessionSystem.instance().operations().cancelTransformGesture(drag.editToken());
         }
         finishPointer(session, drag.capture().gestureToken());
      }
      session.selectionGestures().clear(drag);
   }

   static void finishFace(ClientInputSession session) {
      WorkspaceFaceDrag drag = session.selectionGestures().face();
      if (drag != null) {
         if (session.pointerGesture.owns(session.pointerGestureToken, PointerGestureState.Kind.WORKSPACE_FACE) && drag.capture().matches(ClientSessionSystem.instance().operations().interactionScene().owner(), ClientSessionSystem.instance().operations().workspace(), session.pointerGestureToken)) {
            ClientSessionSystem.instance().operations().finishTransformGesture(drag.editToken());
         } else {
            ClientSessionSystem.instance().operations().cancelTransformGesture(drag.editToken());
         }
         finishPointer(session, drag.capture().gestureToken());
      }
      session.selectionGestures().clear(drag);
   }

   static void finishPointer(ClientInputSession session, long token) {
      session.pointerGesture.finish(token);
      if (session.pointerGestureToken == token) session.pointerGestureToken = 0L;
   }

   static void updateGizmo(ClientInputSession session, Minecraft minecraft, boolean control) {
      WorkspaceGizmoDrag drag = session.selectionGestures().gizmo();
      if (drag == null || minecraft.player == null) {
         return;
      }
      if (!session.pointerGesture.owns(session.pointerGestureToken, PointerGestureState.Kind.WORKSPACE_GIZMO)
         || !drag.capture().matches(ClientSessionSystem.instance().operations().interactionScene().owner(), ClientSessionSystem.instance().operations().workspace(), session.pointerGestureToken)
         || !ClientSessionSystem.instance().operations().workspace().ownsEdit(drag.editToken())) {
         ClientSessionSystem.instance().operations().cancelTransformGesture(drag.editToken());
         finishPointer(session, drag.capture().gestureToken());
         session.selectionGestures().clear(drag);
         return;
      }
      Vec3 eye = minecraft.player.getEyePosition();
      Vec3 view = minecraft.player.getViewVector(1.0F);
      GeometryGizmoDrag geometry = new GeometryGizmoDrag(
         drag.operation(), drag.axis(), drag.origin(), drag.axisVector(), drag.sentSteps(), 0.0,
         drag.center(), drag.startRadial(), drag.startTangent(), drag.direction(), drag.mouseButton()
      );
      int totalSteps;
      double rotationRadians = Double.NaN;
      if (drag.operation() == AxisGizmo.Operation.ROTATE) {
         int rawSteps = GizmoDragCalculator.geometryRotationSteps(geometry, eye, view);
         rotationRadians = RotationInputAngles.selectionPreview(rawSteps, control);
         totalSteps = GizmoDragCalculator.rotationSteps(rotationRadians);
      } else {
         double rawOffset = GizmoDragCalculator.operationEndpointOffset(geometry, eye, view);
         totalSteps = workspaceScaleUsesRepeat(drag)
            ? RepeatDragQuantizer.copiesForOffset(rawOffset, workspaceRepeatUnit(drag))
            : (int)Math.round(rawOffset);
         totalSteps = Math.clamp(totalSteps, -128, 128);
      }
      if (totalSteps == drag.sentSteps()
         && (drag.operation() != AxisGizmo.Operation.ROTATE || control == drag.freeRotation())) {
         return;
      }
      int direction = drag.direction() == AxisGizmo.Direction.NEGATIVE ? -1 : 1;
      String failureKey = ClientSessionSystem.instance().operations().updateTransformGesture(
         drag.editToken(), drag.baseline(), drag.common(), drag.operation(), drag.axis(), direction, totalSteps, rotationRadians,
         drag.axisVector(), drag.center()
      );
      ClientInteractionFeedback.show(minecraft, failureKey);
      session.selectionGestures().update(drag, drag.operation() == AxisGizmo.Operation.ROTATE
         ? drag.withRotation(totalSteps, rotationRadians, control) : drag.withSentSteps(totalSteps));
      if (session.modifier.held()) {
         session.modifier.consume();
      }
   }

   private static boolean workspaceScaleUsesRepeat(WorkspaceGizmoDrag drag) {
      return RepeatStrideSemantics.repeatsWholeGroup(drag.operation(), drag.baseline());
   }

   /**
    * Travel that adjusts one saved repeat cell. Release does not change the unit.
    */
   private static int workspaceRepeatUnit(WorkspaceGizmoDrag drag) {
      return RepeatStrideSemantics.stride(
         drag.operation(), drag.common(), drag.partId(), drag.baseline(), drag.axis()
      );
   }

   static void updateFace(ClientInputSession session, Minecraft minecraft) {
      WorkspaceFaceDrag drag = session.selectionGestures().face();
      if (drag == null || minecraft.player == null) {
         return;
      }
      WorkspaceFaceDrag original = drag;
      if (!session.pointerGesture.owns(session.pointerGestureToken, PointerGestureState.Kind.WORKSPACE_FACE)
         || !drag.capture().matches(ClientSessionSystem.instance().operations().interactionScene().owner(), ClientSessionSystem.instance().operations().workspace(), session.pointerGestureToken)
         || !ClientSessionSystem.instance().operations().workspace().ownsEdit(drag.editToken())) {
         ClientSessionSystem.instance().operations().cancelTransformGesture(drag.editToken());
         finishPointer(session, drag.capture().gestureToken());
         session.selectionGestures().clear(drag);
         return;
      }
      if (drag.awaitingSource()) {
         var decision = ClientSessionSystem.instance().operations().aabbAdjustDecision(drag.baseline());
         drag = resolveFaceSource(session, drag, decision);
         if (drag == null || drag.awaitingSource()) return;
         original = drag;
      }
      long now = System.nanoTime();
      Vec3 axisPoint = OperationGeometry.closestPointOnAxisToRay(
         drag.frame().origin(), drag.normal(), minecraft.player.getEyePosition(), minecraft.player.getViewVector(1.0F)
      );
      if (drag.deferredClick().awaitingRelease(now, FastPlaceClientInput.OPERATION_FACE_SHORT_PRESS_NANOS)) {
         return;
      }
      if (drag.deferredClick().steps() != 0) {
         drag = drag.withDeferredClick(drag.deferredClick().cancel());
      }
      int totalSteps = drag.frame().project(axisPoint, drag.normal());
      if (totalSteps != drag.sentSteps()) {
         ClientSessionSystem.instance().operations().updateAabbFaceGesture(
            drag.editToken(), drag.baseline(), drag.axis(), drag.positive(), totalSteps
         );
         drag = drag.withSentSteps(totalSteps);
      }
      session.selectionGestures().update(original, drag);
   }

   static WorkspaceFaceDrag resolveFaceSource(ClientInputSession session, WorkspaceFaceDrag drag,
      OperationContext.AabbAdjustDecision decision
   ) {
      if (session.selectionGestures().face() != drag) return null;
      if (decision == OperationContext.AabbAdjustDecision.CAPTURE_PENDING) return drag;
      if (!decision.ready()) {
         cancelActive(session);
         ClientInteractionFeedback.showAabbAdjustFailure(Minecraft.getInstance(), decision);
         return null;
      }
      WorkspaceFaceDrag ready = drag.sourceReady();
      return session.selectionGestures().update(drag, ready) ? ready : null;
   }

}
