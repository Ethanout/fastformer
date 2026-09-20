package io.github.fastformer.client.input;

import io.github.fastformer.client.input.drag.OperationDrag;
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
