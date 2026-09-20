package io.github.fastformer.client.input;

import io.github.fastformer.client.operation.controller.ClientOperationController;
import io.github.fastformer.client.render.FastPlaceClientPreview;
import io.github.fastformer.client.input.math.ClientInputMath;
import io.github.fastformer.client.input.drag.GeometryGizmoDrag;
import io.github.fastformer.client.input.drag.GizmoDragCalculator;
import io.github.fastformer.network.payload.geometry.GeometryGizmoDragPayload;
import io.github.fastformer.network.payload.operation.OperationTransformPayload;
import io.github.fastformer.fastplace.geometry.AxisGizmo;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

/** Updates and finishes geometry handles for the owning input session. */
final class GeometryDragController {
   private static final int MAX_DRAG_STEPS_PER_PACKET = 128;

   private GeometryDragController() { }

   static boolean beginConfirmedOperation(
      Minecraft minecraft, ClientInputSession session, AxisGizmo gizmo, AxisGizmo.Hit hit, int mouseButton
   ) {
      if (!NetworkRegistry.hasChannel(minecraft.getConnection(), OperationTransformPayload.TYPE.id())) {
         return false;
      }
      AxisGizmo.Handle handle = hit.handle();
      double baseValue = FastPlaceClientPreview.operationGizmoValue(handle.axis(), handle.operation());
      if (handle.drawsRing()) {
         Vec3 radial = hit.point().subtract(gizmo.center());
         if (radial.lengthSqr() < 1.0E-7) {
            return false;
         }
           session.geometryGizmoDrag = new GeometryGizmoDrag(
             handle.operation(), handle.axis(), hit.point(), gizmo.axisVector(handle.axis()), 0, baseValue,
            gizmo.center(), radial.normalize(), GizmoDragCalculator.rotationTangent(gizmo.axisVector(handle.axis()), radial.normalize()),
             handle.direction(), mouseButton
          );
          session.pointerGestureToken = session.pointerGesture.begin(PointerGestureState.Kind.OPERATION_GIZMO);
      } else {
         Vec3 axis = gizmo.axisVector(handle.axis());
         if (handle.direction() == AxisGizmo.Direction.NEGATIVE) {
            axis = axis.scale(-1.0);
         }
          session.geometryGizmoDrag = new GeometryGizmoDrag(
             handle.operation(), handle.axis(), hit.point(), axis, 0, baseValue,
             Vec3.ZERO, Vec3.ZERO, Vec3.ZERO, handle.direction(), mouseButton
          );
          session.pointerGestureToken = session.pointerGesture.begin(PointerGestureState.Kind.OPERATION_GIZMO);
      }
      session.geometryGizmoDrag = session.geometryGizmoDrag.withCapture(session.pointerGestureToken);
      FastPlaceClientPreview.noteGizmoFeedback(handle.axis(), handle.operation(), 0, baseValue);
      return true;
   }

   static void update(Minecraft minecraft, ClientInputSession session, boolean controlDown) {
      if (discardInvalidCapture(session)) return;
      Target target = target(session, FastPlaceClientPreview.geometryActive(), ClientOperationController.operationSelectionReady());
      if (target == Target.NONE) {
         clearCapture(session);
         return;
      }
      boolean operationTransform = target == Target.OPERATION;

      Vec3 eye = minecraft.player.getEyePosition();
      Vec3 view = minecraft.player.getViewVector(1.0F);
      int totalSteps = session.geometryGizmoDrag.operation() == AxisGizmo.Operation.ROTATE
         ? GizmoDragCalculator.geometryRotationSteps(session.geometryGizmoDrag, eye, view)
         : operationTransform
            ? GizmoDragCalculator.operationEndpointSteps(session.geometryGizmoDrag, eye, view)
            : GizmoDragCalculator.geometryEndpointSteps(session.geometryGizmoDrag, eye, view);
      if (operationTransform && session.geometryGizmoDrag.operation() == AxisGizmo.Operation.ROTATE) {
         totalSteps = GizmoDragCalculator.rotationSteps(RotationInputAngles.resolve(totalSteps, controlDown, session.modifier));
      }
      int delta = totalSteps - session.geometryGizmoDrag.sentSteps();
      if (delta != 0 && (operationTransform
         ? NetworkRegistry.hasChannel(minecraft.getConnection(), OperationTransformPayload.TYPE.id())
         : NetworkRegistry.hasChannel(minecraft.getConnection(), GeometryGizmoDragPayload.TYPE.id()))) {
         int clippedDelta = ClientInputMath.clampDragSteps(delta, MAX_DRAG_STEPS_PER_PACKET);
         int operationTotal = session.geometryGizmoDrag.operation() == AxisGizmo.Operation.ROTATE
            ? Math.clamp(totalSteps, -128, 128)
            : Math.clamp(totalSteps, 0, 128);
         CustomPacketPayload payload = operationTransform
            ? new OperationTransformPayload(
               ClientInputMath.geometryOperationIndex(session.geometryGizmoDrag.operation()),
               ClientInputMath.geometryAxisIndex(session.geometryGizmoDrag.axis()),
               gizmoDirection(session.geometryGizmoDrag),
               operationTotal,
               false
            )
            : new GeometryGizmoDragPayload(
               ClientInputMath.geometryOperationIndex(session.geometryGizmoDrag.operation()),
               ClientInputMath.geometryAxisIndex(session.geometryGizmoDrag.axis()),
               clippedDelta,
               false
            );
         PacketDistributor.sendToServer(payload, new CustomPacketPayload[0]);
          session.geometryGizmoDrag = session.geometryGizmoDrag.withSentSteps(session.geometryGizmoDrag.sentSteps() + clippedDelta);
          FastPlaceClientPreview.noteGizmoFeedback(
             session.geometryGizmoDrag.axis(), session.geometryGizmoDrag.operation(), session.geometryGizmoDrag.sentSteps(), session.geometryGizmoDrag.baseValue()
          );
      }
   }

   static void finish(Minecraft minecraft, ClientInputSession session) {
      if (discardInvalidCapture(session)) return;
      Target target = target(session, FastPlaceClientPreview.geometryActive(), ClientOperationController.operationSelectionReady());
      if (target == Target.NONE) {
         clearCapture(session);
         return;
      }
      boolean operationTransform = target == Target.OPERATION;
      if (session.geometryGizmoDrag != null && (operationTransform
         ? NetworkRegistry.hasChannel(minecraft.getConnection(), OperationTransformPayload.TYPE.id())
         : NetworkRegistry.hasChannel(minecraft.getConnection(), GeometryGizmoDragPayload.TYPE.id()))) {
         FastPlaceClientPreview.noteGizmoFeedback(
            session.geometryGizmoDrag.axis(), session.geometryGizmoDrag.operation(), session.geometryGizmoDrag.sentSteps(), session.geometryGizmoDrag.baseValue()
         );
         CustomPacketPayload payload = operationTransform
            ? new OperationTransformPayload(
               ClientInputMath.geometryOperationIndex(session.geometryGizmoDrag.operation()),
               ClientInputMath.geometryAxisIndex(session.geometryGizmoDrag.axis()),
               gizmoDirection(session.geometryGizmoDrag),
               session.geometryGizmoDrag.operation() == AxisGizmo.Operation.ROTATE
                  ? Math.clamp(session.geometryGizmoDrag.sentSteps(), -128, 128)
                  : Math.clamp(session.geometryGizmoDrag.sentSteps(), 0, 128),
               true
            )
            : new GeometryGizmoDragPayload(
               ClientInputMath.geometryOperationIndex(session.geometryGizmoDrag.operation()),
               ClientInputMath.geometryAxisIndex(session.geometryGizmoDrag.axis()),
               0,
               true
            );
         PacketDistributor.sendToServer(payload, new CustomPacketPayload[0]);
      }
      clearCapture(session);
   }

   private static int gizmoDirection(GeometryGizmoDrag drag) {
      return drag.operation() == AxisGizmo.Operation.ROTATE
         ? 0
         : drag.direction() == AxisGizmo.Direction.NEGATIVE ? -1 : 1;
   }

   static Target target(ClientInputSession session, boolean geometryActive, boolean operationReady) {
      if (session.geometryGizmoDrag == null) return Target.NONE;
      long token = session.geometryGizmoDrag.captureToken();
      if (session.pointerGesture.owns(token, PointerGestureState.Kind.BUILDING_GEOMETRY)) {
         return geometryActive ? Target.GEOMETRY : Target.NONE;
      }
      if (session.pointerGesture.owns(token, PointerGestureState.Kind.OPERATION_GIZMO)) {
         return operationReady ? Target.OPERATION : Target.NONE;
      }
      return Target.NONE;
   }

   private static boolean discardInvalidCapture(ClientInputSession session) {
      if (session.geometryGizmoDrag == null) return true;
      if (target(session, true, true) != Target.NONE) return false;
      clearCapture(session);
      return true;
   }

   private static void clearCapture(ClientInputSession session) {
      if (session.geometryGizmoDrag == null) return;
      long token = session.geometryGizmoDrag.captureToken();
      session.geometryGizmoDrag = null;
      session.pointerGesture.finish(token);
      if (session.pointerGestureToken == token) session.pointerGestureToken = 0L;
   }

   enum Target { NONE, GEOMETRY, OPERATION }

}
