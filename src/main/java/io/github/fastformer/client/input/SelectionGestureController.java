package io.github.fastformer.client.input;

import io.github.fastformer.client.input.drag.GeometryGizmoDrag;
import io.github.fastformer.client.operation.transform.RepeatDragQuantizer;
import io.github.fastformer.client.interaction.InteractionPressBinding;
import io.github.fastformer.client.operation.controller.ClientOperationController;
import io.github.fastformer.client.input.math.ClientInputMath;
import io.github.fastformer.client.input.drag.DeferredDragClick;
import io.github.fastformer.client.input.drag.DragAxisFrame;
import io.github.fastformer.client.input.drag.GizmoDragCalculator;
import io.github.fastformer.client.input.drag.WorkspaceFaceDrag;
import io.github.fastformer.client.input.drag.WorkspaceGizmoDrag;
import io.github.fastformer.client.operation.model.ClientSelectionPart;
import io.github.fastformer.fastplace.geometry.AxisGizmo;
import io.github.fastformer.fastplace.geometry.OperationGeometry;
import io.github.fastformer.client.interaction.SelectionDragCapture;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

final class SelectionGestureController {
   private SelectionGestureController() { }

   static void cancelActive(ClientInputSession session) {
      var gestures = ClientOperationController.selectionGestures();
      var capture = gestures.capture();
      if (capture == null) return;
      var drag = gestures.capturedBy(capture);
      if (drag == null) return;
      ClientOperationController.cancelTransformGesture(drag.editToken());
      finishPointer(session, capture.gestureToken());
      gestures.clear(drag);
   }

   static void press(ClientInputSession session, Minecraft minecraft, SelectionPointerPress press) {
      if (!press.matches(ClientOperationController.interactionScene(), ClientOperationController.workspace())) return;
      switch (press.action()) {
         case InteractionPressBinding.DragGizmo action ->
            beginWorkspaceGizmoDrag(session, action.target(), press.button(), press.control());
         case InteractionPressBinding.SelectOrDragFace action ->
            selectOrBeginWorkspaceFace(session, minecraft, action.target(), press);
         case InteractionPressBinding.SelectPart action ->
            ClientOperationController.selectWorkspacePart(action.partId(), press.control());
      }
   }

   static boolean beginWorkspaceGizmoDrag(ClientInputSession session,
      OperationInteractionIntent.Gizmo target, int mouseButton, boolean control
   ) {
      if (ClientOperationController.selectionGestures().active()) return false;
      if (ClientOperationController.workspaceSubmissionPending()) {
         return false;
      }
      AxisGizmo.Handle handle = target.hit().handle();
      var workspace = ClientOperationController.workspace();
      if (!target.common()) {
         boolean selected = workspace.selectedIds().contains(target.partId());
         if (!selected) {
            ClientOperationController.selectWorkspacePart(target.partId(), control);
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
         ClientOperationController.cancelTransformGesture(editToken);
         return false;
      }
      session.pointerGestureToken = session.pointerGesture.begin(PointerGestureState.Kind.WORKSPACE_GIZMO);
      var capture = SelectionDragCapture.create(
         ClientOperationController.interactionScene().owner(), workspace, targets, mouseButton, session.pointerGestureToken
      );
      ClientOperationController.selectionGestures().begin(new WorkspaceGizmoDrag(
         target.partId(), target.common(), handle.operation(), handle.axis(),
         target.hit().point(), axis, 0, target.gizmo().center(),
         handle.drawsRing() ? radial.normalize() : Vec3.ZERO,
         handle.drawsRing() ? GizmoDragCalculator.rotationTangent(axis, radial.normalize()) : Vec3.ZERO,
         handle.direction(), capture, targets, editToken
      ));
      return true;
   }

   private static ClientOperationController.AabbAdjustDecision beginWorkspaceFaceDrag(ClientInputSession session,
      OperationInteractionIntent.Face target, SelectionPointerPress press
   ) {
      if (ClientOperationController.selectionGestures().active()) {
         return ClientOperationController.AabbAdjustDecision.EDIT_UNAVAILABLE;
      }
      if (ClientOperationController.workspaceSubmissionPending()) {
         return ClientOperationController.AabbAdjustDecision.SUBMISSION_PENDING;
      }
      if (target == null || !target.adjustable()) {
         return ClientOperationController.AabbAdjustDecision.NO_TARGET;
      }
      var workspace = ClientOperationController.workspace();
      ClientSelectionPart part = workspace.part(target.partId()).orElse(null);
      ClientOperationController.AabbAdjustDecision decision = ClientOperationController.aabbAdjustDecision(part);
      if (!decision.ready()) {
         return decision;
      }
      if (!workspace.selectedIds().contains(part.id())) {
         ClientOperationController.selectWorkspacePart(part.id(), false);
      }
      workspace.activate(part.id());
      if (!workspace.beginEdit()) {
         return ClientOperationController.AabbAdjustDecision.EDIT_UNAVAILABLE;
      }
      var editToken = workspace.activeEditToken();
      OperationGeometry.RayHit hit = target.hit();
      int axis = hit.axis();
      Vec3 worldAxis = ClientInputMath.worldAxis(axis);
      boolean positive = hit.normal().dot(worldAxis) > 0.0;
      session.pointerGestureToken = session.pointerGesture.begin(PointerGestureState.Kind.WORKSPACE_FACE);
      var capture = SelectionDragCapture.create(
         ClientOperationController.interactionScene().owner(), workspace, List.of(part), press.button(), session.pointerGestureToken
      );
      ClientOperationController.selectionGestures().begin(new WorkspaceFaceDrag(
         part, axis, positive, DragAxisFrame.start(hit.point(), false), hit.normal(), 0,
         capture, DeferredDragClick.start(press.pressedAtNanos(), press.shortPressSteps()), hit, editToken
      ));
      return ClientOperationController.AabbAdjustDecision.DRAG_STARTED;
   }

   private static void selectOrBeginWorkspaceFace(ClientInputSession session,
      Minecraft minecraft, OperationInteractionIntent.Face target, SelectionPointerPress press
   ) {
      if (target == null) {
         return;
      }
      var workspace = ClientOperationController.workspace();
      if (!workspace.selectedIds().contains(target.partId())) {
         ClientOperationController.selectWorkspacePart(target.partId(), false);
      }
      workspace.activate(target.partId());
      if (target.adjustable()) {
         ClientInteractionFeedback.showAabbAdjustFailure(
            minecraft, beginWorkspaceFaceDrag(session, target, press)
         );
      }
   }

   static void finishGizmo(ClientInputSession session) {
      WorkspaceGizmoDrag drag = ClientOperationController.selectionGestures().gizmo();
      if (drag != null) {
         if (session.pointerGesture.owns(session.pointerGestureToken, PointerGestureState.Kind.WORKSPACE_GIZMO)
            && drag.capture().matches(ClientOperationController.interactionScene().owner(), ClientOperationController.workspace(), session.pointerGestureToken)) {
            ClientOperationController.finishTransformGesture(drag.editToken());
            if (session.modifier.held()) {
               session.modifier.consume();
            }
         } else {
            ClientOperationController.cancelTransformGesture(drag.editToken());
         }
         finishPointer(session, drag.capture().gestureToken());
      }
      ClientOperationController.selectionGestures().clear(drag);
   }

   static void finishFace(ClientInputSession session) {
      WorkspaceFaceDrag drag = ClientOperationController.selectionGestures().face();
      if (drag != null) {
         if (session.pointerGesture.owns(session.pointerGestureToken, PointerGestureState.Kind.WORKSPACE_FACE) && drag.capture().matches(ClientOperationController.interactionScene().owner(), ClientOperationController.workspace(), session.pointerGestureToken)) {
            ClientOperationController.finishTransformGesture(drag.editToken());
         } else {
            ClientOperationController.cancelTransformGesture(drag.editToken());
         }
         finishPointer(session, drag.capture().gestureToken());
      }
      ClientOperationController.selectionGestures().clear(drag);
   }

   static void finishPointer(ClientInputSession session, long token) {
      session.pointerGesture.finish(token);
      if (session.pointerGestureToken == token) session.pointerGestureToken = 0L;
   }

   static void updateGizmo(ClientInputSession session, Minecraft minecraft, boolean control) {
      WorkspaceGizmoDrag drag = ClientOperationController.selectionGestures().gizmo();
      if (drag == null || minecraft.player == null) {
         return;
      }
      if (!session.pointerGesture.owns(session.pointerGestureToken, PointerGestureState.Kind.WORKSPACE_GIZMO)
         || !drag.capture().matches(ClientOperationController.interactionScene().owner(), ClientOperationController.workspace(), session.pointerGestureToken)
         || !ClientOperationController.workspace().ownsEdit(drag.editToken())) {
         ClientOperationController.cancelTransformGesture(drag.editToken());
         finishPointer(session, drag.capture().gestureToken());
         ClientOperationController.selectionGestures().clear(drag);
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
         rotationRadians = RotationInputAngles.resolve(rawSteps, control, session.modifier);
         totalSteps = GizmoDragCalculator.rotationSteps(rotationRadians);
      } else {
         double rawOffset = GizmoDragCalculator.operationEndpointOffset(geometry, eye, view);
         totalSteps = workspaceScaleUsesRepeat(drag)
            ? RepeatDragQuantizer.copiesForOffset(rawOffset, workspaceRepeatUnit(drag))
            : (int)Math.round(rawOffset);
         totalSteps = Math.clamp(totalSteps, -128, 128);
      }
      if (totalSteps == drag.sentSteps()) {
         return;
      }
      int direction = drag.direction() == AxisGizmo.Direction.NEGATIVE ? -1 : 1;
      String failureKey = ClientOperationController.updateTransformGesture(
         drag.editToken(), drag.baseline(), drag.common(), drag.operation(), drag.axis(), direction, totalSteps, rotationRadians
      );
      ClientInteractionFeedback.show(minecraft, failureKey);
      ClientOperationController.selectionGestures().update(drag, drag.withSentSteps(totalSteps));
      if (session.modifier.held()) {
         session.modifier.consume();
      }
   }

   private static boolean workspaceScaleUsesRepeat(WorkspaceGizmoDrag drag) {
      return RepeatStrideSemantics.repeatsWholeGroup(drag.operation(), drag.baseline());
   }

   /**
    * Travel that adds one repeat group. The whole selection box is the unit, so
    * input quantization matches the writer in ClientOperationController.
    */
   private static int workspaceRepeatUnit(WorkspaceGizmoDrag drag) {
      return RepeatStrideSemantics.stride(
         drag.operation(), drag.common(), drag.partId(), drag.baseline(), drag.axis()
      );
   }

   static void updateFace(ClientInputSession session, Minecraft minecraft) {
      WorkspaceFaceDrag drag = ClientOperationController.selectionGestures().face();
      if (drag == null || minecraft.player == null) {
         return;
      }
      WorkspaceFaceDrag original = drag;
      if (!session.pointerGesture.owns(session.pointerGestureToken, PointerGestureState.Kind.WORKSPACE_FACE)
         || !drag.capture().matches(ClientOperationController.interactionScene().owner(), ClientOperationController.workspace(), session.pointerGestureToken)
         || !ClientOperationController.workspace().ownsEdit(drag.editToken())) {
         ClientOperationController.cancelTransformGesture(drag.editToken());
         finishPointer(session, drag.capture().gestureToken());
         ClientOperationController.selectionGestures().clear(drag);
         return;
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
         ClientOperationController.updateAabbFaceGesture(
            drag.editToken(), drag.baseline(), drag.axis(), drag.positive(), totalSteps
         );
         drag = drag.withSentSteps(totalSteps);
      }
      ClientOperationController.selectionGestures().update(original, drag);
   }

}
