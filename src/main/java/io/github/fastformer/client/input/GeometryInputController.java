package io.github.fastformer.client.input;

import io.github.fastformer.client.network.outbound.ServerGateway;

import io.github.fastformer.client.interaction.drag.GeometryGizmoDrag;
import io.github.fastformer.client.session.ClientSessionSystem;
import io.github.fastformer.client.interaction.drag.GizmoDragCalculator;
import io.github.fastformer.client.input.gesture.PathCloseGesture;
import io.github.fastformer.client.input.gesture.PathClosePress;
import io.github.fastformer.client.input.mouse.MouseButtonInputSemantics;
import io.github.fastformer.client.input.state.ClientInputStateMachine;
import io.github.fastformer.client.input.state.PointerGestureState;
import io.github.fastformer.client.interaction.binding.action.BindingCommands;
import io.github.fastformer.client.interaction.binding.action.GeometryBindingCommand;
import io.github.fastformer.client.interaction.binding.context.GeometryBindingContext;
import io.github.fastformer.client.interaction.binding.context.GeometryBindingContext.Fact;
import io.github.fastformer.client.interaction.binding.tool.GeometryBindingInput;
import io.github.fastformer.client.interaction.intent.InteractionContext;
import io.github.fastformer.common.geometry.gizmo.AxisGizmo;
import io.github.fastformer.common.geometry.GeometryAction;
import io.github.fastformer.common.geometry.GeometryMode;
import io.github.fastformer.common.geometry.interaction.GeometryInteractionAction;
import io.github.fastformer.common.geometry.interaction.GeometryInteractionHit;
import io.github.fastformer.common.geometry.interaction.GeometryInteractionTarget;
import io.github.fastformer.common.geometry.interaction.PointerGesture;
import io.github.fastformer.protocol.c2s.geometry.GeometryGizmoDragPayload;
import io.github.fastformer.protocol.c2s.geometry.GeometryInteractionPayload;
import io.github.fastformer.protocol.c2s.geometry.GeometryPointPayload;
import io.github.fastformer.protocol.s2c.geometry.GeometryPreviewPayload;
import io.github.fastformer.protocol.c2s.geometry.GeometryUndoPayload;
import io.github.fastformer.protocol.callback.OperationCallbackScope;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

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

   record FallbackUndoPress(GeometryMode mode, long revision, OperationCallbackScope scope) implements PointerPress {
      public boolean matches(GeometryPreviewPayload preview) {
         return preview.active() && preview.mode() == mode && preview.revision() == revision && preview.callbackScope().equals(scope);
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

   private record Snapshot(GeometryPreviewPayload preview, GeometryInteractionHit hit,
      GeometryGizmoDrag gizmo, PathClosePress close) {}

   private static Snapshot snapshot(Minecraft minecraft, int button, long time, boolean press) {
      return new Snapshot(ClientSessionSystem.instance().previews().geometry(),
         ClientSessionSystem.instance().geometryPointer().interactionHit(),
         press ? captureGeometryGizmoDrag(minecraft, button) : null,
         press ? closeSnapshot(time) : null);
   }

   private static PathClosePress closeSnapshot(long time) {
      var target = ClientSessionSystem.instance().pathCloseTarget();
      return new PathClosePress(target.hoveredStart(), target.doubleClickEnabled(), target.candidate(), true, time);
   }

   static GeometryBindingInput.Result captureBindings(Minecraft minecraft, ClientInputSession session,
      int action, int button, long time) {
      session.bindings.synchronizeShapeDefinitions(minecraft, session);
      boolean owns = session.routing.state() == ClientInputStateMachine.State.GEOMETRY
         || session.ownsGeometryPointerButton(button) || session.geometryGizmoCapture.ownsPhysicalButton(button);
      if (!owns) return new GeometryBindingInput.Result(false, false);
      var snapshot = snapshot(minecraft, button, time, action == MouseButtonInputSemantics.PRESS);
      try {
         var result = GeometryBindingInput.mouse(true, context(minecraft, session, button, snapshot), action, button, time,
            (name, gesture, facts, now) -> session.bindings.onShapeSignal(minecraft, session, name, gesture, facts,
               commands(minecraft, session, button, time, snapshot)));
         if (action == MouseButtonInputSemantics.PRESS) session.geometryInput.pressed(button, result);
         return result;
      } finally {
         if (action == MouseButtonInputSemantics.RELEASE) session.finishGeometryPhysicalRelease(button);
      }
   }

   /** The vanilla callback observes the press policy and never repeats a geometry command. */
   static GeometryBindingInput.Result interactionBindings(Minecraft minecraft, ClientInputSession session, int logicalButton) {
      if (logicalButton < 0 || logicalButton > 2 || minecraft.player == null || minecraft.screen != null
         || session.routing.state() != ClientInputStateMachine.State.GEOMETRY) return new GeometryBindingInput.Result(false, false);
      session.bindings.synchronizeShapeDefinitions(minecraft, session);
      var mapping = switch (logicalButton) {
         case 0 -> minecraft.options.keyAttack;
         case 1 -> minecraft.options.keyUse;
         default -> minecraft.options.keyPickItem;
      };
      int button = mapping.getKey().getType() == com.mojang.blaze3d.platform.InputConstants.Type.MOUSE
         ? mapping.getKey().getValue() : logicalButton;
      var captured = mapping.matchesMouse(button) ? session.geometryInput.interaction(button) : null;
      if (captured != null) return captured;
      var snapshot = snapshot(minecraft, button, 0, true);
      var probe = new BindingCommands() {
         public boolean geometry(GeometryBindingCommand command, int durationMillis) { return true; }
         public void replayPress(long time) { throw new IllegalStateException("Geometry probe cannot replay pointer input"); }
      };
      return GeometryBindingInput.mouse(true, context(minecraft, session, button, snapshot), MouseButtonInputSemantics.PRESS, button, 0,
         (name, gesture, facts, now) -> session.bindings.onShapeSignal(minecraft, session, name, gesture, facts, probe));
   }

   private static GeometryBindingContext context(Minecraft minecraft, ClientInputSession session, int button, Snapshot snapshot) {
      var facts = java.util.EnumSet.noneOf(Fact.class);
      put(facts, Fact.VANILLA_POINTER, InteractionContext.vanillaOwnsPointer(minecraft));
      put(facts, Fact.POINTER_CAPTURED, session.ownsGeometryPointerButton(button));
      put(facts, Fact.GIZMO_PHYSICAL, session.geometryGizmoCapture.hasPhysicalPress());
      put(facts, Fact.GIZMO_OWNED, session.geometryGizmoCapture.ownsPhysicalButton(button));
      put(facts, Fact.GIZMO_CAPTURED, session.geometryGizmoCapture.captured());
      put(facts, Fact.GESTURE_BUSY, session.pointerGesture.kind() != PointerGestureState.Kind.NONE);
      put(facts, Fact.GIZMO_READY, snapshot.gizmo() != null);
      put(facts, Fact.INTERACTION_CHANNEL, ServerGateway.supports(minecraft, GeometryInteractionPayload.TYPE));
      put(facts, Fact.UNDO_CHANNEL, ServerGateway.supports(minecraft, GeometryUndoPayload.TYPE));
      put(facts, Fact.POINT_CHANNEL, ServerGateway.supports(minecraft, GeometryPointPayload.TYPE));
      put(facts, Fact.LEFT_TARGET, snapshot.hit() != null && snapshot.hit().target().action(PointerGesture.LEFT_CLICK) != null);
      put(facts, Fact.RIGHT_TARGET, snapshot.hit() != null && snapshot.hit().target().action(PointerGesture.RIGHT_CLICK) != null);
      put(facts, Fact.POINT_SELECTED, ClientSessionSystem.instance().geometryPointer().pointSelected());
      put(facts, Fact.DRAFT_PRESENT, snapshot.preview().draftId() != null);
      boolean close = snapshot.close() != null && (snapshot.close().hoveredStart()
         || snapshot.close().doubleClickEnabled() && snapshot.close().candidate() != null);
      put(facts, Fact.PATH_AVAILABLE, minecraft.hitResult instanceof BlockHitResult && facts.contains(Fact.POINT_CHANNEL)
         || close && facts.contains(Fact.INTERACTION_CHANNEL));
      put(facts, Fact.FALLBACK_UNDO_CHANNEL, ServerGateway.supports(minecraft, io.github.fastformer.protocol.c2s.placement.UndoFastPlacePayload.TYPE));
      put(facts, Fact.FALLBACK_UNDO_CAPTURED, session.geometryUndoButton == button);
      return new GeometryBindingContext("fastformer:" + snapshot.preview().mode().name().toLowerCase(java.util.Locale.ROOT), facts);
   }

   private static void put(java.util.EnumSet<Fact> facts, Fact fact, boolean value) { if (value) facts.add(fact); }

   private static BindingCommands commands(Minecraft minecraft, ClientInputSession session, int button, long time, Snapshot snapshot) {
      return new BindingCommands() {
         public boolean geometry(GeometryBindingCommand command, int durationMillis) {
            return switch (command) {
               case GIZMO -> queueGizmo(session, snapshot);
               case TARGET_LEFT -> queueTarget(minecraft, session, button, PointerGesture.LEFT_CLICK, snapshot, false);
               case TARGET_RIGHT -> queueTarget(minecraft, session, button, PointerGesture.RIGHT_CLICK, snapshot, false);
               case CLEAR_LEFT -> queueTarget(minecraft, session, button, PointerGesture.LEFT_CLICK, snapshot, true);
               case CLEAR_RIGHT -> queueTarget(minecraft, session, button, PointerGesture.RIGHT_CLICK, snapshot, true);
               case UNDO_POINT -> queueUndo(minecraft, session, button, snapshot);
               case PATH -> queuePath(minecraft, session, button, snapshot);
               case CAPTURE -> { session.captureGeometryPointerButton(button); yield true; }
               case RELEASE_POINTER -> session.releaseGeometryPointerButton(button);
               case RELEASE_GIZMO -> releaseGizmo(minecraft, session, button);
               case BEGIN_UNDO -> {
                  session.geometryFallbackUndo.press(time);
                  session.geometryUndoButton = button;
                  yield true;
               }
               case END_UNDO -> {
                  session.geometryUndoButton = -1;
                  if (session.geometryFallbackUndo.release(time, durationMillis * 1_000_000L)) {
                     var preview = snapshot.preview();
                     session.postGeometryPointer(new FallbackUndoPress(preview.mode(), preview.revision(), preview.callbackScope()));
                  }
                  yield true;
               }
            };
         }
         public void replayPress(long time) { throw new IllegalStateException("Geometry input cannot replay pointer input"); }
      };
   }

   private static boolean queueTarget(Minecraft minecraft, ClientInputSession session, int button,
      PointerGesture gesture, Snapshot snapshot, boolean clear) {
      var preview = snapshot.preview();
      var hit = snapshot.hit();
      GeometryInteractionAction interaction = clear || hit == null ? null : hit.target().action(gesture);
      if (!clear && interaction == null) return false;
      session.captureGeometryPointerButton(button);
      if (!preview.active() || preview.revision() <= 0 || preview.callbackScope().equals(OperationCallbackScope.unscoped())) return true;
      long requestId = nextRequestId();
      Vec3 eye = minecraft.player.getEyePosition();
      Vec3 view = minecraft.player.getViewVector(1.0F);
      var payload = clear ? GeometryInteractionPayload.clearSelection(requestId, preview.revision(), preview.callbackScope(), gesture, eye, view)
         : new GeometryInteractionPayload(requestId, preview.revision(), preview.callbackScope(),
            hit.target().type(), hit.target().index(), interaction, gesture, eye, view);
      session.postGeometryPointer(new InteractionPress(preview.mode(), payload));
      return true;
   }

   private static boolean queueUndo(Minecraft minecraft, ClientInputSession session, int button, Snapshot snapshot) {
      var preview = snapshot.preview();
      session.captureGeometryPointerButton(button);
      if (!preview.active() || preview.revision() <= 0 || preview.draftId() == null
         || preview.callbackScope().equals(OperationCallbackScope.unscoped())) return true;
      session.postGeometryPointer(new UndoPress(preview.mode(), new GeometryUndoPayload(nextRequestId(), preview.revision(),
         preview.callbackScope(), preview.draftId(), minecraft.player.getEyePosition(), minecraft.player.getViewVector(1))));
      return true;
   }

   static void dispatchPointer(Minecraft minecraft, ClientInputSession session, PointerPress press) {
      if (session.routing.state() != ClientInputStateMachine.State.GEOMETRY
         || !press.matches(ClientSessionSystem.instance().previews().geometry())) return;
      session.modifier.consume();
      switch (press) {
         case InteractionPress interaction -> {
            if (ServerGateway.supports(minecraft, GeometryInteractionPayload.TYPE)) {
               ServerGateway.send(interaction.payload());
            }
         }
         case UndoPress undo -> {
            if (ServerGateway.supports(minecraft, GeometryUndoPayload.TYPE)) {
               ServerGateway.send(undo.payload());
            }
         }
         case PathPress path -> dispatchPath(minecraft, session, path);
         case FallbackUndoPress undo -> {
            if (ServerGateway.supports(minecraft, io.github.fastformer.protocol.c2s.placement.UndoFastPlacePayload.TYPE))
               ServerGateway.send(io.github.fastformer.protocol.c2s.placement.UndoFastPlacePayload.INSTANCE);
         }
      }
   }

   private static boolean queuePath(Minecraft minecraft, ClientInputSession session, int button, Snapshot snapshot) {
      boolean pointAllowed = minecraft.hitResult instanceof BlockHitResult
         && ServerGateway.supports(minecraft, GeometryPointPayload.TYPE);
      boolean closeAllowed = ServerGateway.supports(minecraft, GeometryInteractionPayload.TYPE);
      PathClosePress close = snapshot.close();
      if (close == null) return false;
      boolean closeTarget = close.hoveredStart() || close.doubleClickEnabled() && close.candidate() != null;
      if (!pointAllowed && !(closeAllowed && closeTarget)) return false;
      var preview = snapshot.preview();
      session.captureGeometryPointerButton(button);
      if (!preview.active() || preview.revision() <= 0 || preview.callbackScope().equals(OperationCallbackScope.unscoped())) return true;
      session.postGeometryPointer(new PathPress(preview.mode(), new GeometryPointPayload(nextRequestId(), preview.revision(),
         preview.callbackScope(), minecraft.player.getEyePosition(), minecraft.player.getViewVector(1.0F)),
         close, pointAllowed, closeAllowed));
      return true;
   }

   private static void dispatchPath(Minecraft minecraft, ClientInputSession session, PathPress press) {
      var payload = press.resolve(session.pathClose,
         ServerGateway.supports(minecraft, GeometryPointPayload.TYPE),
         ServerGateway.supports(minecraft, GeometryInteractionPayload.TYPE));
      if (payload != null) ServerGateway.send(payload);
   }

   private static boolean queueGizmo(ClientInputSession session, Snapshot snapshot) {
      var preview = snapshot.preview();
      var drag = snapshot.gizmo();
      if (drag == null) return false;
      var press = session.geometryGizmoCapture.press(preview.revision(), preview.draftId(), preview.callbackScope(), preview.mode(), drag);
      if (press != null) session.postGeometryGizmo(press);
      return true;
   }

   private static boolean releaseGizmo(Minecraft minecraft, ClientInputSession session, int button) {
      var release = session.geometryGizmoCapture.release(button, minecraft.player.getEyePosition(),
         minecraft.player.getViewVector(1), net.minecraft.client.gui.screens.Screen.hasControlDown());
      if (release == null) return false;
      session.postGeometryGizmo(release);
      return true;
   }

   static void dispatchGizmo(Minecraft minecraft, ClientInputSession session, GeometryGizmoCapture.Event event) {
      var preview = ClientSessionSystem.instance().previews().geometry();
      switch (event) {
         case GeometryGizmoCapture.Event.Press press -> {
            if (!session.geometryGizmoCapture.accepts(press) || !preview.active()
               || press.revision() <= 0 || press.draftId() == null || press.scope().equals(OperationCallbackScope.unscoped())
               || session.routing.state() != ClientInputStateMachine.State.GEOMETRY
               || preview.revision() != press.revision() || !preview.callbackScope().equals(press.scope())
               || !press.draftId().equals(preview.draftId()) || preview.mode() != press.mode()
               || session.pointerGesture.kind() != PointerGestureState.Kind.NONE) return;
            session.modifier.consume();
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
      if (!ClientSessionSystem.instance().geometryPointer().allows(GeometryAction.GIZMO_DRAG)
         || !ServerGateway.supports(minecraft, GeometryGizmoDragPayload.TYPE)) {
         return null;
      }
      AxisGizmo.Hit hit = ClientSessionSystem.instance().geometryPointer().gizmoHit();
      if (hit == null) {
         return null;
      }
      AxisGizmo.Handle handle = hit.handle();
      AxisGizmo gizmo = ClientSessionSystem.instance().geometryPointer().gizmo();
      if (gizmo == null) {
         return null;
      }
      double baseValue = ClientSessionSystem.instance().geometryPointer().gizmoValue(handle.axis(), handle.operation());
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
      session.pointerGesture.begin(PointerGestureState.Kind.BUILDING_GEOMETRY);
      session.geometryGizmoDrag = captured.withCapture(session.pointerGesture.activeToken());
      session.feedback().noteGizmo(captured.axis(), captured.operation(), 0, captured.baseValue());
   }

}
