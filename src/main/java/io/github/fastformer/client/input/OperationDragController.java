package io.github.fastformer.client.input;

import io.github.fastformer.client.input.drag.OperationDrag;
import io.github.fastformer.client.input.drag.DeferredDragClick;
import io.github.fastformer.client.input.drag.DragAxisFrame;
import io.github.fastformer.fastplace.LongRangeBlockRaycast;
import io.github.fastformer.client.input.math.ClientInputMath;
import io.github.fastformer.client.operation.controller.ClientOperationController;
import io.github.fastformer.client.render.FastPlaceClientPreview;
import io.github.fastformer.fastplace.geometry.AxisGizmo;
import io.github.fastformer.fastplace.geometry.OperationGeometry;
import io.github.fastformer.fastplace.selection.OperationSelectionVolume;
import io.github.fastformer.network.payload.operation.OperationExtendPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

/** Owns the transient face and unconfirmed operation-gizmo drag lifecycle. */
final class OperationDragController {
   private static final int MAX_DRAG_STEPS_PER_PACKET = 128;
   private static final long FACE_SHORT_PRESS_NANOS = FastPlaceClientInput.OPERATION_FACE_SHORT_PRESS_NANOS;

   private OperationDragController() { }

   static boolean beginFace(Minecraft minecraft, ClientInputSession session, int mouseButton, int shortPressSteps) {
      OperationSelectionVolume selection = FastPlaceClientPreview.operationSelection();
      if (selection == null
         || shortPressSteps == 0
         || !ClientOperationController.operationSelectionReady()
         || !NetworkRegistry.hasChannel(minecraft.getConnection(), OperationExtendPayload.TYPE.id())) {
         return false;
      }

      Vec3 eye = minecraft.player.getEyePosition();
      OperationGeometry.RayHit hit = ClientOperationController.operationCuboid()
         ? FastPlaceClientPreview.operationFaceHit()
         : selection.raycast(eye, minecraft.player.getViewVector(1.0F), LongRangeBlockRaycast.MAX_REACH);
      if (hit == null) {
         return false;
      }

      Vec3 normal = hit.normal();
      int axis = hit.axis();
      boolean positive = normal.dot(selection.axis(axis)) > 0.0;
      if (!ClientOperationController.operationCuboid() && (axis != 2 || !positive)) {
         return false;
      }
      boolean reverseInside = ClientOperationController.operationCuboid() && selection.contains(eye);
      int deferredSteps = reverseInside ? -shortPressSteps : shortPressSteps;
      session.pointerGestureToken = session.pointerGesture.begin(PointerGestureState.Kind.OPERATION_FACE);
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
         session.pointerGestureToken
      );
      return true;
   }

   static boolean beginGizmo(Minecraft minecraft, ClientInputSession session, int mouseButton) {
      AxisGizmo gizmo = FastPlaceClientPreview.operationGizmo();
      AxisGizmo.Hit hit = FastPlaceClientPreview.operationGizmoHit();
      if (gizmo == null || hit == null) {
         return false;
      }
      AxisGizmo.Handle handle = hit.handle();
      if (ClientOperationController.operationSelectionReady()) {
         return GeometryDragController.beginConfirmedOperation(minecraft, session, gizmo, hit, mouseButton);
      }
      int axis = ClientInputMath.geometryAxisIndex(handle.axis());
      boolean positive = handle.direction() != AxisGizmo.Direction.NEGATIVE;
      Vec3 vector = gizmo.axisVector(handle.axis()).scale(positive ? 1.0 : -1.0);
      int encodedAxis = handle.operation() == AxisGizmo.Operation.MOVE
         ? axis + (FastPlaceClientPreview.operationPointSelected() ? 6 : 3)
         : axis;
      session.pointerGestureToken = session.pointerGesture.begin(PointerGestureState.Kind.OPERATION_GIZMO);
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
         session.pointerGestureToken
      );
      return true;
   }

   static void update(Minecraft minecraft, ClientInputSession session) {
      OperationDrag drag = session.operationDrag;
      if (!acceptsCapture(session, drag)) return;
      Vec3 eye = minecraft.player.getEyePosition();
      Vec3 view = minecraft.player.getViewVector(1.0F);
      Vec3 axisPoint = OperationGeometry.closestPointOnAxisToRay(drag.frame().origin(), drag.normal(), eye, view);
      int projectedSteps = drag.frame().project(axisPoint, drag.normal());
      long now = System.nanoTime();
      if (drag.faceHit() != null && drag.deferredClick().awaitingRelease(now, FACE_SHORT_PRESS_NANOS)) return;
      if (drag.deferredClick().steps() != 0) {
         drag = drag.withDeferredClick(drag.deferredClick().cancel());
      }
      if (drag.faceHit() != null && ClientOperationController.operationCuboid()) {
         OperationSelectionVolume selection = FastPlaceClientPreview.operationSelection();
         boolean inside = selection != null
            && selection.bounds().inflate(OperationSelectionVolume.RAYCAST_INFLATE).contains(eye);
         if (inside != drag.frame().reversed()) {
            drag = drag.withFrame(drag.frame().rebase(axisPoint, projectedSteps, inside));
         }
      }
      int totalSteps = drag.frame().project(axisPoint, drag.normal());
      int delta = totalSteps - drag.sentSteps();
      if (delta == 0 || !NetworkRegistry.hasChannel(minecraft.getConnection(), OperationExtendPayload.TYPE.id())) {
         session.operationDrag = drag;
         return;
      }
      int clippedDelta = ClientInputMath.clampDragSteps(delta, MAX_DRAG_STEPS_PER_PACKET);
      PacketDistributor.sendToServer(new OperationExtendPayload(drag.axis(), drag.positive(), clippedDelta, false),
         new CustomPacketPayload[0]);
      drag = drag.withSentSteps(drag.sentSteps() + clippedDelta);
      session.operationDrag = drag;
      if (drag.gizmoKey() != null) {
         FastPlaceClientPreview.noteGizmoFeedback(drag.gizmoKey().axis(), AxisGizmo.Operation.MOVE,
            drag.sentSteps(), drag.gizmoBaseValue());
      }
   }

   static void finish(Minecraft minecraft, ClientInputSession session, long releasedAtNanos) {
      OperationDrag drag = session.operationDrag;
      if (!acceptsCapture(session, drag)) return;
      if (drag.gizmoKey() != null) {
         FastPlaceClientPreview.noteGizmoFeedback(drag.gizmoKey().axis(), drag.gizmoKey().operation(),
            drag.sentSteps(), drag.gizmoBaseValue());
      }
      if (NetworkRegistry.hasChannel(minecraft.getConnection(), OperationExtendPayload.TYPE.id())) {
         int releaseSteps = drag.faceHit() == null
            ? drag.deferredClick().releaseSteps(releasedAtNanos, FACE_SHORT_PRESS_NANOS) : 0;
         PacketDistributor.sendToServer(new OperationExtendPayload(drag.axis(), drag.positive(), releaseSteps, true),
            new CustomPacketPayload[0]);
      }
      clearCapture(session, drag);
   }

   static void cancel(ClientInputSession session) {
      OperationDrag drag = session.operationDrag;
      if (drag != null) clearCapture(session, drag);
   }

   static boolean acceptsCapture(ClientInputSession session, OperationDrag drag) {
      if (drag == null) return false;
      boolean owned = session.pointerGesture.owns(drag.captureToken(), PointerGestureState.Kind.OPERATION_FACE)
         || session.pointerGesture.owns(drag.captureToken(), PointerGestureState.Kind.OPERATION_GIZMO);
      if (owned && FastPlaceClientPreview.operationActive()) return true;
      clearCapture(session, drag);
      return false;
   }

   private static void clearCapture(ClientInputSession session, OperationDrag drag) {
      if (session.operationDrag != drag) return;
      session.operationDrag = null;
      session.pointerGesture.finish(drag.captureToken());
      if (session.pointerGestureToken == drag.captureToken()) session.pointerGestureToken = 0L;
   }
}
