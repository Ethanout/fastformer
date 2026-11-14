package io.github.fastformer.client.input;

import io.github.fastformer.client.network.outbound.ServerGateway;

import io.github.fastformer.client.session.ClientSessionSystem;

import io.github.fastformer.client.interaction.drag.GeometryGizmoDrag;
import io.github.fastformer.client.interaction.drag.GizmoDragCalculator;
import io.github.fastformer.client.input.math.ClientInputMath;
import io.github.fastformer.client.input.state.PointerGestureState;
import io.github.fastformer.common.geometry.gizmo.AxisGizmo;
import io.github.fastformer.protocol.c2s.geometry.GeometryGizmoDragPayload;
import io.github.fastformer.protocol.c2s.operation.OperationTransformPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.phys.Vec3;

/** Updates and finishes geometry handles for the owning input session. */
final class GeometryDragController {
   private static final int MAX_DRAG_STEPS_PER_PACKET = 128;

   private GeometryDragController() { }

   static boolean beginConfirmedOperation(
      Minecraft minecraft, ClientInputSession session, AxisGizmo gizmo, AxisGizmo.Hit hit, int mouseButton
   ) {
      if (!ServerGateway.supports(minecraft, OperationTransformPayload.TYPE)) {
         return false;
      }
      AxisGizmo.Handle handle = hit.handle();
      double baseValue = ClientSessionSystem.instance().operations().remotePointer().operationGizmoValue(handle.axis(), handle.operation());
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
          session.pointerGesture.begin(PointerGestureState.Kind.OPERATION_GIZMO);
      } else {
         Vec3 axis = gizmo.axisVector(handle.axis());
         if (handle.direction() == AxisGizmo.Direction.NEGATIVE) {
            axis = axis.scale(-1.0);
         }
          session.geometryGizmoDrag = new GeometryGizmoDrag(
             handle.operation(), handle.axis(), hit.point(), axis, 0, baseValue,
             Vec3.ZERO, Vec3.ZERO, Vec3.ZERO, handle.direction(), mouseButton
          );
          session.pointerGesture.begin(PointerGestureState.Kind.OPERATION_GIZMO);
      }
      session.geometryGizmoDrag = session.geometryGizmoDrag.withCapture(session.pointerGesture.activeToken());
      session.operationTransformCapture = new ClientInputSession.OperationTransformCapture(GeometryInputController.nextRequestId(),
         ClientSessionSystem.instance().operations().remoteSelectionRevision(), ClientSessionSystem.instance().operations().remoteSelectionCallbackScope());
      session.feedback().noteGizmo(handle.axis(), handle.operation(), 0, baseValue);
      return true;
   }

   static void update(Minecraft minecraft, ClientInputSession session, boolean controlDown) {
      Target target = validatedTarget(session);
      if (target == Target.NONE) return;
      update(minecraft, session, controlDown, minecraft.player.getEyePosition(), minecraft.player.getViewVector(1.0F), target);
   }

   static void update(Minecraft minecraft, ClientInputSession session, boolean controlDown, Vec3 eye, Vec3 view) {
      Target target = validatedTarget(session);
      if (target != Target.NONE) update(minecraft, session, controlDown, eye, view, target);
   }

   private static Target validatedTarget(ClientInputSession session) {
      if (discardInvalidCapture(session)) return Target.NONE;
      if (session.geometryGizmoCapture.owns(session.geometryGizmoDrag.captureToken())) {
         var preview = ClientSessionSystem.instance().previews().geometry();
         if (!preview.active() || !session.geometryGizmoCapture.matchesActive(preview.draftId(), preview.callbackScope(), preview.mode())) {
            clearCapture(session);
            session.geometryGizmoCapture.cancel();
            return Target.NONE;
         }
      }
      Target target = target(session, ClientSessionSystem.instance().previews().geometry().active(), ClientSessionSystem.instance().operations().operationSelectionReady());
      if (target == Target.NONE) {
         clearCapture(session);
      }
      return target;
   }

   private static void update(Minecraft minecraft, ClientInputSession session, boolean controlDown,
      Vec3 eye, Vec3 view, Target target) {
      boolean operationTransform = target == Target.OPERATION;

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
         ? ServerGateway.supports(minecraft, OperationTransformPayload.TYPE)
         : ServerGateway.supports(minecraft, GeometryGizmoDragPayload.TYPE))) {
         int clippedDelta = ClientInputMath.clampDragSteps(delta, MAX_DRAG_STEPS_PER_PACKET);
         int operationTotal = session.geometryGizmoDrag.operation() == AxisGizmo.Operation.ROTATE
            ? Math.clamp(totalSteps, -128, 128)
            : Math.clamp(totalSteps, 0, 128);
         CustomPacketPayload payload = operationTransform
            ? new OperationTransformPayload(
               GeometryInputController.nextRequestId(), session.operationTransformCapture.gestureId(),
               session.operationTransformCapture.revision(), session.operationTransformCapture.scope(),
               ClientInputMath.geometryOperationIndex(session.geometryGizmoDrag.operation()),
               ClientInputMath.geometryAxisIndex(session.geometryGizmoDrag.axis()),
               gizmoDirection(session.geometryGizmoDrag),
               operationTotal,
               false
            )
            : capturedGeometryPayload(session, clippedDelta, false);
         if (payload == null) {
            clearCapture(session);
            return;
         }
         ServerGateway.send(payload);
          session.geometryGizmoDrag = session.geometryGizmoDrag.withSentSteps(session.geometryGizmoDrag.sentSteps() + clippedDelta);
          session.feedback().noteGizmo(
             session.geometryGizmoDrag.axis(), session.geometryGizmoDrag.operation(), session.geometryGizmoDrag.sentSteps(), session.geometryGizmoDrag.baseValue()
          );
      }
   }

   static void finish(Minecraft minecraft, ClientInputSession session) {
      Target target = validatedTarget(session);
      if (target == Target.NONE) return;
      boolean operationTransform = target == Target.OPERATION;
      if (session.geometryGizmoDrag != null && (operationTransform
         ? ServerGateway.supports(minecraft, OperationTransformPayload.TYPE)
         : ServerGateway.supports(minecraft, GeometryGizmoDragPayload.TYPE))) {
         session.feedback().noteGizmo(
            session.geometryGizmoDrag.axis(), session.geometryGizmoDrag.operation(), session.geometryGizmoDrag.sentSteps(), session.geometryGizmoDrag.baseValue()
         );
         CustomPacketPayload payload = operationTransform
            ? new OperationTransformPayload(
               GeometryInputController.nextRequestId(), session.operationTransformCapture.gestureId(),
               session.operationTransformCapture.revision(), session.operationTransformCapture.scope(),
               ClientInputMath.geometryOperationIndex(session.geometryGizmoDrag.operation()),
               ClientInputMath.geometryAxisIndex(session.geometryGizmoDrag.axis()),
               gizmoDirection(session.geometryGizmoDrag),
               session.geometryGizmoDrag.operation() == AxisGizmo.Operation.ROTATE
                  ? Math.clamp(session.geometryGizmoDrag.sentSteps(), -128, 128)
                  : Math.clamp(session.geometryGizmoDrag.sentSteps(), 0, 128),
               true
            )
            : capturedGeometryPayload(session, 0, true);
         if (payload == null) {
            clearCapture(session);
            return;
         }
         ServerGateway.send(payload);
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

   private static GeometryGizmoDragPayload capturedGeometryPayload(ClientInputSession session, int steps, boolean finish) {
      GeometryGizmoCapture.Event.Press press = session.geometryGizmoCapture.activePress();
      if (press == null || press.draftId() == null || session.geometryGizmoDrag == null) {
         return null;
      }
      return new GeometryGizmoDragPayload(
         GeometryInputController.nextRequestId(),
         press.revision(),
         press.scope(),
         press.draftId(),
         ClientInputMath.geometryOperationIndex(session.geometryGizmoDrag.operation()),
         ClientInputMath.geometryAxisIndex(session.geometryGizmoDrag.axis()),
         steps,
         finish
      );
   }

   private static boolean discardInvalidCapture(ClientInputSession session) {
      if (session.geometryGizmoDrag == null) return true;
      if (target(session, true, true) != Target.NONE) return false;
      clearCapture(session);
      return true;
   }

   static void clearCapture(ClientInputSession session) {
      if (session.geometryGizmoDrag == null) return;
      long token = session.geometryGizmoDrag.captureToken();
      session.geometryGizmoDrag = null;
      session.operationTransformCapture = null;
      session.pointerGesture.finish(token);
   }

   enum Target { NONE, GEOMETRY, OPERATION }

}
