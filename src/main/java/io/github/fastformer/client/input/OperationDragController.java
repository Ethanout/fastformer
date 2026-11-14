package io.github.fastformer.client.input;

import io.github.fastformer.client.input.state.ClientInputStateMachine;
import io.github.fastformer.client.interaction.InteractionPressBinding;
import io.github.fastformer.client.interaction.binding.action.BindingCommands;
import io.github.fastformer.client.interaction.binding.context.SelectionBindingContext;
import io.github.fastformer.client.operation.input.SelectionPointerEvent.RemoteSnapshot;
import io.github.fastformer.client.interaction.intent.OperationInteractionIntent;
import io.github.fastformer.client.interaction.selectiongesture.SelectionFaceHit;
import io.github.fastformer.client.interaction.selectiongesture.SelectionGesturePress;
import io.github.fastformer.client.interaction.selectiongesture.SelectionDragCapture;
import io.github.fastformer.client.operation.controller.OperationContext;
import io.github.fastformer.client.operation.input.OperationPointDragEvent;
import io.github.fastformer.client.operation.input.SelectionDraftPress;
import io.github.fastformer.client.operation.input.SelectionPointerCapture;
import io.github.fastformer.client.operation.input.SelectionPointerEvent;
import io.github.fastformer.client.operation.input.SelectionPointerPress;
import io.github.fastformer.client.operation.selection.tool.SelectionTools;
import io.github.fastformer.protocol.c2s.placement.UndoFastPlacePayload;

import io.github.fastformer.client.network.outbound.ServerGateway;

import io.github.fastformer.client.session.ClientSessionSystem;

import io.github.fastformer.client.interaction.drag.DeferredDragClick;
import io.github.fastformer.client.interaction.drag.DragAxisFrame;
import io.github.fastformer.client.input.drag.OperationDrag;
import io.github.fastformer.client.input.math.ClientInputMath;
import io.github.fastformer.client.input.state.PointerGestureState;
import io.github.fastformer.common.geometry.gizmo.AxisGizmo;
import io.github.fastformer.common.selection.geometry.OperationGeometry;
import io.github.fastformer.common.raycast.LongRangeBlockRaycast;
import io.github.fastformer.common.selection.OperationSelectionVolume;
import io.github.fastformer.protocol.c2s.operation.OperationExtendPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

/** Owns the transient face and unconfirmed operation-gizmo drag lifecycle. */
final class OperationDragController {
   private static final int MAX_DRAG_STEPS_PER_PACKET = 128;
   private static final long FACE_SHORT_PRESS_NANOS = 140_000_000L;

   private OperationDragController() { }

   static OperationDrag captureFace(Minecraft minecraft, int mouseButton, int shortPressSteps, long occurredAtNanos) {
      OperationSelectionVolume selection = ClientSessionSystem.instance().operations().remotePointer().operationSelection();
      if (selection == null
         || shortPressSteps == 0
         || !ClientSessionSystem.instance().operations().operationSelectionReady()
         || !ServerGateway.supports(minecraft, OperationExtendPayload.TYPE)) {
         return null;
      }

      Vec3 eye = minecraft.player.getEyePosition();
      OperationGeometry.RayHit hit = ClientSessionSystem.instance().operations().operationCuboid()
         ? ClientSessionSystem.instance().operations().remotePointer().operationFaceHit()
         : selection.raycast(eye, minecraft.player.getViewVector(1.0F), LongRangeBlockRaycast.MAX_REACH);
      if (hit == null) {
         return null;
      }

      Vec3 normal = hit.normal();
      int axis = hit.axis();
      boolean positive = normal.dot(selection.axis(axis)) > 0.0;
      if (!ClientSessionSystem.instance().operations().operationCuboid() && (axis != 2 || !positive)) {
         return null;
      }
      boolean reverseInside = ClientSessionSystem.instance().operations().operationCuboid() && selection.contains(eye);
      int deferredSteps = reverseInside ? -shortPressSteps : shortPressSteps;
      return new OperationDrag(
         axis,
         positive,
         DragAxisFrame.start(hit.point(), reverseInside),
         normal,
         0,
         mouseButton,
         hit,
         null,
         0.0,
         DeferredDragClick.start(occurredAtNanos, deferredSteps),
         1L, ClientSessionSystem.instance().operations().remoteSelectionRevision(),
         ClientSessionSystem.instance().operations().remoteSelectionCallbackScope(), GeometryInputController.nextRequestId()
      );
   }

   static OperationDrag captureGizmo(Minecraft minecraft, int mouseButton) {
      AxisGizmo gizmo = ClientSessionSystem.instance().operations().remotePointer().operationGizmo();
      AxisGizmo.Hit hit = gizmoHit(minecraft, gizmo);
      if (gizmo == null || hit == null) {
         return null;
      }
      AxisGizmo.Handle handle = hit.handle();
      int axis = ClientInputMath.geometryAxisIndex(handle.axis());
      boolean positive = handle.direction() != AxisGizmo.Direction.NEGATIVE;
      Vec3 vector = gizmo.axisVector(handle.axis()).scale(positive ? 1.0 : -1.0);
      int encodedAxis = handle.operation() == AxisGizmo.Operation.MOVE
         ? axis + (ClientSessionSystem.instance().operations().remotePointer().operationPointSelected() ? 6 : 3)
         : axis;
      return new OperationDrag(
         encodedAxis,
         positive,
         DragAxisFrame.start(hit.point(), !positive),
         vector,
         0,
         mouseButton,
         null,
         handle.key(),
         ClientInputMath.axisComponent(gizmo.center(), handle.axis()),
         DeferredDragClick.none(),
         1L, ClientSessionSystem.instance().operations().remoteSelectionRevision(),
         ClientSessionSystem.instance().operations().remoteSelectionCallbackScope(), GeometryInputController.nextRequestId()
      );
   }

   static AxisGizmo.Hit gizmoHit(Minecraft minecraft) {
      return gizmoHit(minecraft, ClientSessionSystem.instance().operations().remotePointer().operationGizmo());
   }

   private static AxisGizmo.Hit gizmoHit(Minecraft minecraft, AxisGizmo gizmo) {
      return gizmo == null || minecraft.player == null ? null
         : gizmo.hitTest(minecraft.player.getEyePosition(), minecraft.player.getViewVector(1.0F),
            io.github.fastformer.common.raycast.LongRangeBlockRaycast.MAX_REACH);
   }

   static void update(Minecraft minecraft, ClientInputSession session) {
      OperationDrag drag = session.operationDrag;
      if (!acceptsCapture(session, drag)) return;
      Vec3 eye = minecraft.player.getEyePosition();
      Vec3 view = minecraft.player.getViewVector(1.0F);
      Vec3 axisPoint = io.github.fastformer.common.math.RayAxisProjection.closestPoint(drag.frame().origin(), drag.normal(), eye, view);
      int projectedSteps = drag.frame().project(axisPoint, drag.normal());
      long now = System.nanoTime();
      if (drag.faceHit() != null && drag.deferredClick().awaitingRelease(now, FACE_SHORT_PRESS_NANOS)) return;
      if (drag.deferredClick().steps() != 0) {
         drag = drag.withDeferredClick(drag.deferredClick().cancel());
      }
      if (drag.faceHit() != null && ClientSessionSystem.instance().operations().operationCuboid()) {
         OperationSelectionVolume selection = ClientSessionSystem.instance().operations().remotePointer().operationSelection();
         boolean inside = selection != null
            && selection.bounds().inflate(OperationSelectionVolume.RAYCAST_INFLATE).contains(eye);
         if (inside != drag.frame().reversed()) {
            drag = drag.withFrame(drag.frame().rebase(axisPoint, projectedSteps, inside));
         }
      }
      int totalSteps = drag.frame().project(axisPoint, drag.normal());
      int delta = totalSteps - drag.sentSteps();
      if (delta == 0 || !ServerGateway.supports(minecraft, OperationExtendPayload.TYPE)) {
         session.operationDrag = drag;
         return;
      }
      int clippedDelta = ClientInputMath.clampDragSteps(delta, MAX_DRAG_STEPS_PER_PACKET);
         ServerGateway.send(new OperationExtendPayload(drag.requestId(), drag.revision(), drag.callbackScope(), drag.axis(), drag.positive(), clippedDelta, false));
      drag = drag.withSentSteps(drag.sentSteps() + clippedDelta);
      session.operationDrag = drag;
      if (drag.gizmoKey() != null) {
         session.feedback().noteGizmo(drag.gizmoKey().axis(), AxisGizmo.Operation.MOVE,
            drag.sentSteps(), drag.gizmoBaseValue());
      }
   }

   static void finish(Minecraft minecraft, ClientInputSession session, long releasedAtNanos) {
      OperationDrag drag = session.operationDrag;
      if (!acceptsCapture(session, drag)) return;
      if (drag.gizmoKey() != null) {
         session.feedback().noteGizmo(drag.gizmoKey().axis(), drag.gizmoKey().operation(),
            drag.sentSteps(), drag.gizmoBaseValue());
      }
      if (ServerGateway.supports(minecraft, OperationExtendPayload.TYPE)) {
         int releaseSteps = drag.faceHit() == null
            ? drag.deferredClick().releaseSteps(releasedAtNanos, FACE_SHORT_PRESS_NANOS) : 0;
         ServerGateway.send(new OperationExtendPayload(drag.requestId(), drag.revision(), drag.callbackScope(), drag.axis(), drag.positive(), releaseSteps, true));
      }
      clearCapture(session, drag);
   }

   static void cancel(ClientInputSession session) {
      OperationDrag drag = session.operationDrag;
      if (drag == null) return;
      Minecraft minecraft = Minecraft.getInstance();
      if (minecraft != null && minecraft.getConnection() != null
         && ServerGateway.supports(minecraft, OperationExtendPayload.TYPE)) {
         ServerGateway.send(new OperationExtendPayload(drag.requestId(), drag.revision(),
            drag.callbackScope(), drag.axis(), drag.positive(), 0, true));
      }
      clearCapture(session, drag);
   }

   static boolean acceptsCapture(ClientInputSession session, OperationDrag drag) {
      if (drag == null) return false;
      boolean owned = session.pointerGesture.owns(drag.captureToken(), PointerGestureState.Kind.OPERATION_FACE)
         || session.pointerGesture.owns(drag.captureToken(), PointerGestureState.Kind.OPERATION_GIZMO);
      if (owned && ClientSessionSystem.instance().operationActive()) return true;
      clearCapture(session, drag);
      return false;
   }

   private static void clearCapture(ClientInputSession session, OperationDrag drag) {
      if (session.operationDrag != drag) return;
      session.operationDrag = null;
      session.pointerGesture.finish(drag.captureToken());
   }

   static void clearSelectionUndo(ClientInputSession session) {
      session.selectionUndo.cancel(); session.selectionUndoButton = -1; session.selectionUndoPress = null;
   }

   static boolean releaseSelection(Minecraft minecraft, ClientInputSession session, int button, long time, boolean cancelled) {
      return releaseSelection(session, button, time, cancelled, minecraft.player.getEyePosition(), minecraft.player.getViewVector(1));
   }

   static void finishSelectionPhysicalRelease(ClientInputSession session, int button, long time, boolean releaseHandled,
      Vec3 eye, Vec3 view) {
      if (!releaseHandled) releaseSelection(session, button, time, true, eye, view);
      session.selectionInput.release(button);
      if (session.selectionUndoButton == button) clearSelectionUndo(session);
   }

   static boolean releaseSelection(ClientInputSession session, int button, long time, boolean cancelled,
      Vec3 eye, Vec3 view) {
      var pointer = session.selectionPointer.release(button, time);
      if (pointer != null) session.postSelectionPointer(cancelled ? new SelectionPointerEvent.Cancel(pointer.identity()) : pointer);
      var point = session.operationPointPointer.release(button, time, eye, view);
      if (point != null) session.postOperationPointDrag(cancelled ? new OperationPointDragEvent.Cancel(point.identity()) : point);
      return session.selectionInput.owns(button) || pointer != null || point != null;
   }

   static void dispatchSelection(Minecraft minecraft, ClientInputSession session, SelectionPointerEvent event) {
      switch (event) {
         case SelectionPointerEvent.Press press -> {
            if (session.selectionGestures().active()) return;
            long routing = 0L;
            SelectionDragCapture capture = null;
            if (session.routing.dispatch(ClientInputStateMachine.InputKind.POINTER) == ClientInputStateMachine.Dispatch.OPERATION
               && !session.selectionGestures().active()) {
               dispatchSelectionGesture(session, ClientSessionSystem.instance().operations(), press.snapshot());
               capture = session.selectionGestures().capture();
               if (capture != null) {
                  routing = session.routing.beginGesture(press.snapshot().button());
                  session.clickGestureToken = routing;
                  session.operationClick.capture(press.snapshot().button());
                  if (press.alt()) session.modifier.consume();
               }
            }
            session.selectionPointer.dispatched(press.identity(), capture, routing);
         }
         case SelectionPointerEvent.Release release -> settle(minecraft, session, session.selectionPointer.take(release.identity()), false, release.occurredAtNanos());
         case SelectionPointerEvent.Cancel cancel -> settle(minecraft, session, session.selectionPointer.take(cancel.identity()), true, 0L);
         case SelectionPointerEvent.CreatePress press -> {
            if (session.selectionGestures().active()) return;
            handleDraft(session, press.snapshot()); session.selectionPointer.dispatched(press.identity(), null, 0L);
         }
         case SelectionPointerEvent.PointPress press -> {
            if (session.selectionGestures().active()) return;
            var value = press.snapshot(); var operations = ClientSessionSystem.instance().operations();
            if (session.routing.dispatch(ClientInputStateMachine.InputKind.POINTER) == ClientInputStateMachine.Dispatch.OPERATION
               && value.matches(operations.interactionScene().owner(), operations.workspace()) && !session.selectionGestures().active() && value.point() != null)
               ClientInteractionFeedback.showAabbAdjustFailure(minecraft, operations.transforms().adjustAabbPoint(value.partId(), value.button(), value.point()));
            session.selectionPointer.dispatched(press.identity(), null, 0L);
         }
         case SelectionPointerEvent.RemotePress press -> dispatchRemote(minecraft, session, press);
      }
   }

   private static void handleDraft(ClientInputSession session, SelectionDraftPress press) {
      var operations = ClientSessionSystem.instance().operations();
      if (session.routing.dispatch(ClientInputStateMachine.InputKind.CREATE_SELECTION) != ClientInputStateMachine.Dispatch.OPERATION
         || !press.matches(operations.interactionScene().owner(), ClientSessionSystem.instance().selectionSession().selectionToolId())
         || operations.workspaceSubmissionPending() || operations.workspace().locked() || session.selectionGestures().active()) return;
      if (press.alt()) {
         operations.setAltMode(true); session.modifier.consume();
         if (press.point() != null || press.button() == 0 && SelectionTools.require(press.toolId()).undoPreviousPoint())
            operations.selectionEdits().handleAltCreateClick(press.button(), press.point());
      } else operations.selectionEdits().handleCreateClick(press.button(), press.point());
   }

   private static boolean matches(RemoteSnapshot press) {
      var operations = ClientSessionSystem.instance().operations();
      return press.matches(operations.interactionScene().owner(), operations.remoteSelectionIdentity(),
         operations.remoteSelectionRevision(), operations.remoteSelectionCallbackScope());
   }

   private static void dispatchRemote(Minecraft minecraft, ClientInputSession session, SelectionPointerEvent.RemotePress event) {
      var press = event.snapshot();
      if (session.routing.dispatch(ClientInputStateMachine.InputKind.POINTER) != ClientInputStateMachine.Dispatch.OPERATION || !matches(press)) return;
      if (press.undo()) {
         if (ServerGateway.supports(minecraft, UndoFastPlacePayload.TYPE)) ServerGateway.send(UndoFastPlacePayload.INSTANCE);
         return;
      }
      if (press.path() != null) { PathCloseInputDispatcher.dispatch(minecraft, session, press.path()); return; }
      if (press.drag() == null && press.gizmo() == null) return;
      if (session.pointerGesture.kind() != PointerGestureState.Kind.NONE) return;
      long routing = session.routing.beginGesture(press.button());
      session.clickGestureToken = routing;
      session.operationClick.capture(press.button());
      var kind = press.drag() != null && press.drag().faceHit() != null ? PointerGestureState.Kind.OPERATION_FACE : PointerGestureState.Kind.OPERATION_GIZMO;
      session.pointerGesture.begin(kind);
      long token = session.pointerGesture.activeToken();
      if (press.drag() != null) session.operationDrag = press.drag().withCapture(token);
      else {
         session.geometryGizmoDrag = press.gizmo().withCapture(token);
         session.operationTransformCapture = new ClientInputSession.OperationTransformCapture(GeometryInputController.nextRequestId(), press.revision(), press.scope());
         session.feedback().noteGizmo(press.gizmo().axis(), press.gizmo().operation(), 0, press.gizmo().baseValue());
      }
      session.modifier.consume();
      session.selectionPointer.dispatchedRemote(event.identity(), routing, press, token);
   }

   private static void settle(Minecraft minecraft, ClientInputSession session, SelectionPointerCapture.Dispatch dispatched,
      boolean cancelled, long time) {
      if (dispatched == null) return;
      if (dispatched.drag() != null) {
         if (cancelled) session.selectionGestures().cancel(dispatched.drag()); else session.selectionGestures().finish(dispatched.drag());
         if (session.clickGestureToken == dispatched.routingToken()) {
            session.routing.finishGesture(dispatched.drag().mouseButton(), dispatched.routingToken());
            session.clickGestureToken = 0L; session.operationClick.clear();
         }
      } else if (dispatched.remote() != null && session.pointerGesture.activeToken() == dispatched.pointerToken()) {
         var operations = ClientSessionSystem.instance().operations();
         boolean valid = !cancelled && ClientSessionSystem.instance().operationActive()
            && dispatched.remote().ownsActive(operations.interactionScene().owner(), operations.remoteSelectionCallbackScope());
         if (session.operationDrag != null) {
            if (valid) OperationDragController.finish(minecraft, session, time); else OperationDragController.cancel(session);
         } else if (session.geometryGizmoDrag != null) {
            if (valid) GeometryDragController.finish(minecraft, session); else GeometryDragController.clearCapture(session);
         }
         session.routing.finishGesture(dispatched.remote().button(), dispatched.routingToken());
         if (session.clickGestureToken == dispatched.routingToken()) { session.clickGestureToken = 0L; session.operationClick.clear(); }
      }
   }


   static void dispatchSelectionGesture(ClientInputSession input, OperationContext operations, SelectionPointerPress press) {
      if (!press.matches(operations.interactionScene(), operations.workspace())) return;
      var command = switch (press.action()) {
         case InteractionPressBinding.DragGizmo action -> selectionGizmoPress(action.target(), press.button(), press.control());
         case InteractionPressBinding.SelectPart action -> new SelectionGesturePress.Part(action.partId(), press.control());
         case InteractionPressBinding.SelectOrDragFace action -> {
            var target = action.target(); var hit = target.hit();
            yield new SelectionGesturePress.Face(target.partId(), hit == null ? null : new SelectionFaceHit(hit.point(), hit.normal(), hit.distance(), hit.axis()),
               target.adjustable(), press.button(), press.shortPressSteps(), press.pressedAtNanos());
         }
      };
      input.selectionGestures().press(command);
   }

   static SelectionGesturePress.Gizmo selectionGizmoPress(OperationInteractionIntent.Gizmo target, int button, boolean control) {
      return new SelectionGesturePress.Gizmo(target.partId(), target.common(), target.gizmo(), target.hit(), button, control);
   }

}
