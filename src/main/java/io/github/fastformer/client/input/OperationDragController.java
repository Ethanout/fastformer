package io.github.fastformer.client.input;

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
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.phys.Vec3;

/** Owns the transient face and unconfirmed operation-gizmo drag lifecycle. */
final class OperationDragController {
   private static final int MAX_DRAG_STEPS_PER_PACKET = 128;
   private static final long FACE_SHORT_PRESS_NANOS = FastPlaceClientInput.OPERATION_FACE_SHORT_PRESS_NANOS;

   private OperationDragController() { }

   static boolean beginFace(Minecraft minecraft, ClientInputSession session, int mouseButton, int shortPressSteps) {
      OperationSelectionVolume selection = ClientSessionSystem.instance().operations().remotePointer().operationSelection();
      if (selection == null
         || shortPressSteps == 0
         || !ClientSessionSystem.instance().operations().operationSelectionReady()
         || !ServerGateway.supports(minecraft, OperationExtendPayload.TYPE)) {
         return false;
      }

      Vec3 eye = minecraft.player.getEyePosition();
      OperationGeometry.RayHit hit = ClientSessionSystem.instance().operations().operationCuboid()
         ? ClientSessionSystem.instance().operations().remotePointer().operationFaceHit()
         : selection.raycast(eye, minecraft.player.getViewVector(1.0F), LongRangeBlockRaycast.MAX_REACH);
      if (hit == null) {
         return false;
      }

      Vec3 normal = hit.normal();
      int axis = hit.axis();
      boolean positive = normal.dot(selection.axis(axis)) > 0.0;
      if (!ClientSessionSystem.instance().operations().operationCuboid() && (axis != 2 || !positive)) {
         return false;
      }
      boolean reverseInside = ClientSessionSystem.instance().operations().operationCuboid() && selection.contains(eye);
      int deferredSteps = reverseInside ? -shortPressSteps : shortPressSteps;
      session.pointerGesture.begin(PointerGestureState.Kind.OPERATION_FACE);
      session.operationDrag = new OperationDrag(
         axis,
         positive,
         DragAxisFrame.start(hit.point(), reverseInside),
         normal,
         0,
         mouseButton,
         hit,
         null,
         0.0,
         DeferredDragClick.start(System.nanoTime(), deferredSteps),
         session.pointerGesture.activeToken(), ClientSessionSystem.instance().operations().remoteSelectionRevision(),
         ClientSessionSystem.instance().operations().remoteSelectionCallbackScope(), GeometryInputController.nextRequestId()
      );
      return true;
   }

   static boolean beginGizmo(Minecraft minecraft, ClientInputSession session, int mouseButton) {
      AxisGizmo gizmo = ClientSessionSystem.instance().operations().remotePointer().operationGizmo();
      AxisGizmo.Hit hit = gizmoHit(minecraft, gizmo);
      if (gizmo == null || hit == null) {
         return false;
      }
      AxisGizmo.Handle handle = hit.handle();
      if (ClientSessionSystem.instance().operations().operationSelectionReady()) {
         return GeometryDragController.beginConfirmedOperation(minecraft, session, gizmo, hit, mouseButton);
      }
      int axis = ClientInputMath.geometryAxisIndex(handle.axis());
      boolean positive = handle.direction() != AxisGizmo.Direction.NEGATIVE;
      Vec3 vector = gizmo.axisVector(handle.axis()).scale(positive ? 1.0 : -1.0);
      int encodedAxis = handle.operation() == AxisGizmo.Operation.MOVE
         ? axis + (ClientSessionSystem.instance().operations().remotePointer().operationPointSelected() ? 6 : 3)
         : axis;
      session.pointerGesture.begin(PointerGestureState.Kind.OPERATION_GIZMO);
      session.operationDrag = new OperationDrag(
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
         session.pointerGesture.activeToken(), ClientSessionSystem.instance().operations().remoteSelectionRevision(),
         ClientSessionSystem.instance().operations().remoteSelectionCallbackScope(), GeometryInputController.nextRequestId()
      );
      return true;
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
}
