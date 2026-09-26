package io.github.fastformer.client.input;

import io.github.fastformer.client.input.drag.GeometryGizmoDrag;
import io.github.fastformer.client.input.drag.GizmoDragCalculator;
import io.github.fastformer.client.input.gesture.PathCloseGesture;
import io.github.fastformer.client.input.gesture.PathClosePress;
import io.github.fastformer.client.input.mouse.MouseButtonInputSemantics;
import io.github.fastformer.client.input.state.ClientInputStateMachine;
import io.github.fastformer.client.input.state.PointerGestureState;
import io.github.fastformer.client.interaction.intent.InteractionContext;
import io.github.fastformer.client.render.FastPlaceClientPreview;
import io.github.fastformer.fastplace.geometry.AxisGizmo;
import io.github.fastformer.fastplace.geometry.GeometryAction;
import io.github.fastformer.fastplace.geometry.GeometryMode;
import io.github.fastformer.fastplace.geometry.interaction.GeometryInteractionAction;
import io.github.fastformer.fastplace.geometry.interaction.GeometryInteractionHit;
import io.github.fastformer.fastplace.geometry.interaction.GeometryInteractionTarget;
import io.github.fastformer.fastplace.geometry.interaction.PointerGesture;
import io.github.fastformer.network.payload.geometry.GeometryGizmoDragPayload;
import io.github.fastformer.network.payload.geometry.GeometryInteractionPayload;
import io.github.fastformer.network.payload.geometry.GeometryPointPayload;
import io.github.fastformer.network.payload.geometry.GeometryPreviewPayload;
import io.github.fastformer.network.payload.geometry.GeometryUndoPayload;
import io.github.fastformer.network.payload.operation.OperationCallbackScope;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

/** Routes special-shape presses through the owning input session. */
final class GeometryInputController {
   private static final java.util.concurrent.atomic.AtomicLong NEXT_REQUEST_ID = new java.util.concurrent.atomic.AtomicLong();
   private GeometryInputController() { }

   static long nextRequestId() {
      return NEXT_REQUEST_ID.incrementAndGet();
   }

   sealed interface PointerPress {
      boolean matches(GeometryPreviewPayload preview);
   }

   record InteractionPress(GeometryMode mode, GeometryInteractionPayload payload) implements PointerPress {
      public boolean matches(GeometryPreviewPayload preview) {
         return preview.active() && payload.revision() == preview.revision()
            && payload.callbackScope().equals(preview.callbackScope()) && mode == preview.mode();
      }
   }

   record UndoPress(GeometryMode mode, GeometryUndoPayload payload) implements PointerPress {
      public boolean matches(GeometryPreviewPayload preview) {
         return preview.active() && payload.revision() == preview.revision()
            && payload.callbackScope().equals(preview.callbackScope())
            && payload.draftId().equals(preview.draftId()) && mode == preview.mode();
      }
   }

   record PathPress(GeometryMode mode, GeometryPointPayload point, PathClosePress close,
      boolean pointAllowed, boolean closeAllowed) implements PointerPress {
      public boolean matches(GeometryPreviewPayload preview) {
         return preview.active() && point.revision() == preview.revision()
            && point.callbackScope().equals(preview.callbackScope()) && mode == preview.mode();
      }

      net.minecraft.network.protocol.common.custom.CustomPacketPayload resolve(PathCloseGesture gesture,
         boolean pointChannel, boolean closeChannel) {
         boolean canClose = closeAllowed && closeChannel;
         if (!canClose) gesture.reset();
         if (canClose && close.closes(gesture)) {
            return new GeometryInteractionPayload(point.requestId(), point.revision(), point.callbackScope(),
               GeometryInteractionTarget.TargetType.CLOSE_PATH, 0, GeometryInteractionAction.CLOSE_PATH,
               close.hoveredStart() ? PointerGesture.RIGHT_CLICK : PointerGesture.RIGHT_DOUBLE_CLICK, point.eye(), point.view());
         }
         return pointAllowed && pointChannel ? point : null;
      }
   }

   static boolean queueInteraction(Minecraft minecraft, ClientInputSession session, int action, int button, boolean alt) {
      if (button < 0 || button > 1) return false;
      if (action == MouseButtonInputSemantics.RELEASE) {
         return session.releaseGeometryPointerButton(button);
      }
      if (action != MouseButtonInputSemantics.PRESS) return false;
      if (session.ownsGeometryPointerButton(button)) return true;
      if (session.geometryGizmoCapture.captured() || alt || InteractionContext.nearVanillaBlock(minecraft)
         || session.routing.state() != ClientInputStateMachine.State.GEOMETRY
         || session.pointerGesture.kind() != PointerGestureState.Kind.NONE
         || !NetworkRegistry.hasChannel(minecraft.getConnection(), GeometryInteractionPayload.TYPE.id())
            && !NetworkRegistry.hasChannel(minecraft.getConnection(), GeometryUndoPayload.TYPE.id())) return false;
      PointerGesture gesture = button == 0 ? PointerGesture.LEFT_CLICK : PointerGesture.RIGHT_CLICK;
      GeometryInteractionHit hit = FastPlaceClientPreview.geometryInteractionHit();
      GeometryInteractionAction interaction = hit == null ? null : hit.target().action(gesture);
      var preview = FastPlaceClientPreview.geometrySnapshot();
      session.captureGeometryPointerButton(button);
      if (!preview.active() || preview.revision() <= 0 || preview.callbackScope().equals(OperationCallbackScope.unscoped())) return true;
      GeometryInteractionPayload payload;
      long requestId = nextRequestId();
      Vec3 eye = minecraft.player.getEyePosition();
      Vec3 view = minecraft.player.getViewVector(1.0F);
      if (interaction == null && !FastPlaceClientPreview.geometryPointSelected()) {
         if (button != 0 || !NetworkRegistry.hasChannel(minecraft.getConnection(), GeometryUndoPayload.TYPE.id())
            || preview.draftId() == null) return true;
         session.postGeometryPointer(new UndoPress(preview.mode(), new GeometryUndoPayload(requestId, preview.revision(),
            preview.callbackScope(), preview.draftId(), eye, view)));
         return true;
      }
      if (interaction != null) {
         payload = new GeometryInteractionPayload(requestId, preview.revision(), preview.callbackScope(),
            hit.target().type(), hit.target().index(), interaction, gesture, eye, view);
      } else {
         payload = GeometryInteractionPayload.clearSelection(requestId, preview.revision(), preview.callbackScope(), gesture, eye, view);
      }
      session.postGeometryPointer(new InteractionPress(preview.mode(), payload));
      return true;
   }

   static void dispatchPointer(Minecraft minecraft, ClientInputSession session, PointerPress press) {
      if (session.routing.state() != ClientInputStateMachine.State.GEOMETRY
         || !press.matches(FastPlaceClientPreview.geometrySnapshot())) return;
      switch (press) {
         case InteractionPress interaction -> {
            if (NetworkRegistry.hasChannel(minecraft.getConnection(), GeometryInteractionPayload.TYPE.id())) {
               net.neoforged.neoforge.network.PacketDistributor.sendToServer(interaction.payload());
            }
         }
         case UndoPress undo -> {
            if (NetworkRegistry.hasChannel(minecraft.getConnection(), GeometryUndoPayload.TYPE.id())) {
               net.neoforged.neoforge.network.PacketDistributor.sendToServer(undo.payload());
            }
         }
         case PathPress path -> dispatchPath(minecraft, session, path);
      }
   }

   static boolean queuePath(Minecraft minecraft, ClientInputSession session, int action, int button,
      boolean alt, long occurredAtNanos) {
      if (action != MouseButtonInputSemantics.PRESS || button != MouseButtonInputSemantics.RIGHT_BUTTON
         || alt || InteractionContext.nearVanillaBlock(minecraft)
         || session.routing.state() != ClientInputStateMachine.State.GEOMETRY
         || session.pointerGesture.kind() != PointerGestureState.Kind.NONE || session.geometryGizmoCapture.captured()) return false;
      boolean pointAllowed = minecraft.hitResult instanceof BlockHitResult
         && NetworkRegistry.hasChannel(minecraft.getConnection(), GeometryPointPayload.TYPE.id());
      boolean closeAllowed = NetworkRegistry.hasChannel(minecraft.getConnection(), GeometryInteractionPayload.TYPE.id());
      PathClosePress close = PathCloseInputDispatcher.capture(true, occurredAtNanos);
      boolean closeTarget = close.hoveredStart() || close.doubleClickEnabled() && close.candidate() != null;
      if (!pointAllowed && !(closeAllowed && closeTarget)) return false;
      var preview = FastPlaceClientPreview.geometrySnapshot();
      session.captureGeometryPointerButton(button);
      if (!preview.active() || preview.revision() <= 0 || preview.callbackScope().equals(OperationCallbackScope.unscoped())) return true;
      session.postGeometryPointer(new PathPress(preview.mode(), new GeometryPointPayload(nextRequestId(), preview.revision(),
         preview.callbackScope(), minecraft.player.getEyePosition(), minecraft.player.getViewVector(1.0F)),
         close, pointAllowed, closeAllowed));
      return true;
   }

   private static void dispatchPath(Minecraft minecraft, ClientInputSession session, PathPress press) {
      var payload = press.resolve(session.pathClose,
         NetworkRegistry.hasChannel(minecraft.getConnection(), GeometryPointPayload.TYPE.id()),
         NetworkRegistry.hasChannel(minecraft.getConnection(), GeometryInteractionPayload.TYPE.id()));
      if (payload != null) net.neoforged.neoforge.network.PacketDistributor.sendToServer(payload);
   }

   static boolean queueGizmo(Minecraft minecraft, ClientInputSession session, int action, int button, boolean alt) {
      if (action == MouseButtonInputSemantics.RELEASE) {
         var release = session.geometryGizmoCapture.release(button, minecraft.player.getEyePosition(),
            minecraft.player.getViewVector(1.0F), net.minecraft.client.gui.screens.Screen.hasControlDown());
         if (release == null) return false;
         session.postGeometryGizmo(release);
         return true;
      }
      if (action != MouseButtonInputSemantics.PRESS) return false;
      if (session.geometryGizmoCapture.hasPhysicalPress()) return session.geometryGizmoCapture.ownsPhysicalButton(button);
      if (button < 0 || button > 1 || alt || InteractionContext.nearVanillaBlock(minecraft)
         || session.routing.state() != ClientInputStateMachine.State.GEOMETRY
         || session.pointerGesture.kind() != PointerGestureState.Kind.NONE && !session.geometryGizmoCapture.captured()) return false;
      var preview = FastPlaceClientPreview.geometrySnapshot();
      var drag = captureGeometryGizmoDrag(minecraft, button);
      if (drag == null) return false;
      var press = session.geometryGizmoCapture.press(preview.revision(), preview.draftId(), preview.callbackScope(), preview.mode(), drag);
      if (press != null) session.postGeometryGizmo(press);
      return true;
   }

   static void dispatchGizmo(Minecraft minecraft, ClientInputSession session, GeometryGizmoCapture.Event event) {
      var preview = FastPlaceClientPreview.geometrySnapshot();
      switch (event) {
         case GeometryGizmoCapture.Event.Press press -> {
            if (!session.geometryGizmoCapture.accepts(press) || !preview.active()
               || press.revision() <= 0 || press.draftId() == null || press.scope().equals(OperationCallbackScope.unscoped())
               || session.routing.state() != ClientInputStateMachine.State.GEOMETRY
               || preview.revision() != press.revision() || !preview.callbackScope().equals(press.scope())
               || !press.draftId().equals(preview.draftId()) || preview.mode() != press.mode()
               || session.pointerGesture.kind() != PointerGestureState.Kind.NONE) return;
            beginGeometryGizmoDrag(session, press.drag());
            session.geometryGizmoCapture.activate(press, session.geometryGizmoDrag.captureToken());
         }
         case GeometryGizmoCapture.Event.Release release -> {
            if (session.geometryGizmoDrag == null
               || !session.geometryGizmoCapture.owns(release, session.geometryGizmoDrag.captureToken())) {
               session.geometryGizmoCapture.finish(release);
               return;
            }
            if (preview.active() && session.routing.state() == ClientInputStateMachine.State.GEOMETRY
               && release.press().draftId() != null && release.press().draftId().equals(preview.draftId())
               && preview.callbackScope().equals(release.press().scope()) && preview.mode() == release.press().mode()) {
               GeometryDragController.update(minecraft, session, release.controlDown(), release.eye(), release.view());
               GeometryDragController.finish(minecraft, session);
            } else {
               GeometryDragController.clearCapture(session);
            }
            session.geometryGizmoCapture.finish(release);
         }
      }
   }

   static GeometryGizmoDrag captureGeometryGizmoDrag(Minecraft minecraft, int mouseButton) {
      if (!FastPlaceClientPreview.geometryAllows(GeometryAction.GIZMO_DRAG)
         || !NetworkRegistry.hasChannel(minecraft.getConnection(), GeometryGizmoDragPayload.TYPE.id())) {
         return null;
      }
      AxisGizmo.Hit hit = FastPlaceClientPreview.geometryGizmoHit();
      if (hit == null) {
         return null;
      }
      AxisGizmo.Handle handle = hit.handle();
      AxisGizmo gizmo = FastPlaceClientPreview.geometryGizmo();
      if (gizmo == null) {
         return null;
      }
      double baseValue = FastPlaceClientPreview.geometryGizmoValue(handle.axis(), handle.operation());
      if (handle.drawsRing()) {
         Vec3 radial = hit.point().subtract(gizmo.center());
         if (radial.lengthSqr() < 1.0E-7) {
            return null;
         }
         return new GeometryGizmoDrag(
            handle.operation(), handle.axis(), hit.point(), gizmo.axisVector(handle.axis()),
            0, baseValue, gizmo.center(), radial.normalize(),
            GizmoDragCalculator.rotationTangent(gizmo.axisVector(handle.axis()), radial.normalize()),
            handle.direction(), mouseButton
         );
      }
      if (!handle.drawsEndpoint()) {
         return null;
      }
      Vec3 axis = gizmo.axisVector(handle.axis());
      if (handle.operation() == AxisGizmo.Operation.SCALE && handle.direction() == AxisGizmo.Direction.NEGATIVE) {
         axis = axis.scale(-1.0);
      }
      return new GeometryGizmoDrag(
         handle.operation(), handle.axis(), hit.point(), axis, 0, baseValue, Vec3.ZERO, Vec3.ZERO, Vec3.ZERO,
         handle.direction(), mouseButton
      );
   }

   static void beginGeometryGizmoDrag(ClientInputSession session, GeometryGizmoDrag captured) {
      session.pointerGestureToken = session.pointerGesture.begin(PointerGestureState.Kind.BUILDING_GEOMETRY);
      session.geometryGizmoDrag = captured.withCapture(session.pointerGestureToken);
      FastPlaceClientPreview.noteGizmoFeedback(captured.axis(), captured.operation(), 0, captured.baseValue());
   }

}
