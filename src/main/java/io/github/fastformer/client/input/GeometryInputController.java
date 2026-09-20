package io.github.fastformer.client.input;

import io.github.fastformer.client.render.FastPlaceClientPreview;
import io.github.fastformer.client.input.drag.GeometryGizmoDrag;
import io.github.fastformer.client.input.drag.GizmoDragCalculator;
import io.github.fastformer.network.payload.geometry.GeometryGizmoDragPayload;
import io.github.fastformer.network.payload.geometry.GeometryInteractionPayload;
import io.github.fastformer.network.payload.geometry.GeometryPointPayload;
import io.github.fastformer.fastplace.geometry.AxisGizmo;
import io.github.fastformer.fastplace.geometry.GeometryAction;
import io.github.fastformer.fastplace.geometry.GeometryInteractionAction;
import io.github.fastformer.fastplace.geometry.GeometryInteractionHit;
import io.github.fastformer.fastplace.geometry.GeometryInteractionTarget;
import io.github.fastformer.fastplace.geometry.PointerGesture;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

/** Routes special-shape presses through the owning input session. */
final class GeometryInputController {
   private GeometryInputController() { }

   static boolean beginGeometryGizmoDrag(Minecraft minecraft, ClientInputSession session, int mouseButton) {
      if (!FastPlaceClientPreview.geometryAllows(GeometryAction.GIZMO_DRAG)
         || !NetworkRegistry.hasChannel(minecraft.getConnection(), GeometryGizmoDragPayload.TYPE.id())) {
         return false;
      }
      AxisGizmo.Hit hit = FastPlaceClientPreview.geometryGizmoHit();
      if (hit == null) {
         return false;
      }
      AxisGizmo.Handle handle = hit.handle();
      AxisGizmo gizmo = FastPlaceClientPreview.geometryGizmo();
      if (gizmo == null) {
         return false;
      }
      double baseValue = FastPlaceClientPreview.geometryGizmoValue(handle.axis(), handle.operation());
      if (handle.drawsRing()) {
         Vec3 radial = hit.point().subtract(gizmo.center());
         if (radial.lengthSqr() < 1.0E-7) {
            return false;
         }
         session.geometryGizmoDrag = new GeometryGizmoDrag(
            handle.operation(), handle.axis(), hit.point(), gizmo.axisVector(handle.axis()),
            0, baseValue, gizmo.center(), radial.normalize(),
            GizmoDragCalculator.rotationTangent(gizmo.axisVector(handle.axis()), radial.normalize()),
            handle.direction(), mouseButton
         );
         captureGizmo(session, handle, baseValue);
         return true;
      }
      if (!handle.drawsEndpoint()) {
         return false;
      }
      Vec3 axis = gizmo.axisVector(handle.axis());
      if (handle.operation() == AxisGizmo.Operation.SCALE && handle.direction() == AxisGizmo.Direction.NEGATIVE) {
         axis = axis.scale(-1.0);
      }
      session.geometryGizmoDrag = new GeometryGizmoDrag(
         handle.operation(), handle.axis(), hit.point(), axis, 0, baseValue, Vec3.ZERO, Vec3.ZERO, Vec3.ZERO,
         handle.direction(), mouseButton
      );
      captureGizmo(session, handle, baseValue);
      return true;
   }

   private static void captureGizmo(ClientInputSession session, AxisGizmo.Handle handle, double baseValue) {
      session.pointerGestureToken = session.pointerGesture.begin(PointerGestureState.Kind.BUILDING_GEOMETRY);
      session.geometryGizmoDrag = session.geometryGizmoDrag.withCapture(session.pointerGestureToken);
      FastPlaceClientPreview.noteGizmoFeedback(handle.axis(), handle.operation(), 0, baseValue);
   }

   static boolean beginGeometryInteraction(Minecraft minecraft, ClientInputSession session, int mouseButton) {
      if (!FastPlaceClientPreview.geometryActive()
         || !NetworkRegistry.hasChannel(minecraft.getConnection(), GeometryInteractionPayload.TYPE.id())) {
         return false;
      }

      PointerGesture gesture = mouseButton == 0 ? PointerGesture.LEFT_CLICK : PointerGesture.RIGHT_CLICK;
      GeometryInteractionHit hit = FastPlaceClientPreview.geometryInteractionHit();
      GeometryInteractionAction action = hit == null ? null : hit.target().action(gesture);
      if (action != null) {
         sendGeometryInteraction(session, hit.target(), action, gesture, mouseButton);
         return true;
      }
      if (FastPlaceClientPreview.geometryPointSelected()) {
         sendGeometryClearSelection(session, gesture, mouseButton);
         return true;
      }
      return false;
   }

   private static void sendGeometryInteraction(
      ClientInputSession session,
      GeometryInteractionTarget target,
      GeometryInteractionAction action,
      PointerGesture gesture,
      int mouseButton
   ) {
      PacketDistributor.sendToServer(
         new GeometryInteractionPayload(target.type(), target.index(), action, gesture),
         new CustomPacketPayload[0]
      );
      session.geometryClickCapturedButton = mouseButton;
   }

   private static void sendGeometryClearSelection(ClientInputSession session, PointerGesture gesture, int mouseButton) {
      PacketDistributor.sendToServer(
         GeometryInteractionPayload.clearSelection(gesture),
         new CustomPacketPayload[0]
      );
      session.geometryClickCapturedButton = mouseButton;
   }

   static boolean sendGeometryPointInput(Minecraft minecraft, ClientInputSession session, int mouseButton) {
      if (!(minecraft.hitResult instanceof BlockHitResult)
         || !NetworkRegistry.hasChannel(minecraft.getConnection(), GeometryPointPayload.TYPE.id())) {
         return false;
      }
      PacketDistributor.sendToServer(GeometryPointPayload.INSTANCE, new CustomPacketPayload[0]);
      session.geometryClickCapturedButton = mouseButton;
      return true;
   }

   static boolean handleGeometryRightClick(Minecraft minecraft, ClientInputSession session, long occurredAtNanos) {
      if (session.geometryClickCapturedButton == 1) {
         return true;
      }
      return session.geometryGizmoDrag != null
         || beginGeometryGizmoDrag(minecraft, session, 1)
         || beginGeometryInteraction(minecraft, session, 1)
         || PathCloseInputDispatcher.press(minecraft, session, true, occurredAtNanos)
         || sendGeometryPointInput(minecraft, session, 1);
   }

}
