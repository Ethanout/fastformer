package io.github.fastformer.server.session;

import io.github.fastformer.common.geometry.session.GeometrySession;

import io.github.fastformer.core.math.Axis;

import io.github.fastformer.server.session.slot.PlayerSessions;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import io.github.fastformer.common.geometry.gizmo.AxisGizmo;
import io.github.fastformer.common.geometry.model.FillMode;
import io.github.fastformer.common.geometry.GeometryAction;
import io.github.fastformer.server.geometry.workflow.GeometryActionContext;
import io.github.fastformer.common.geometry.raster.GeometryBuildResult;
import io.github.fastformer.common.geometry.GeometryHit;
import io.github.fastformer.common.geometry.GeometryMode;
import io.github.fastformer.common.geometry.ConePlaneMode;
import io.github.fastformer.common.geometry.interaction.GeometryInteractionAction;
import io.github.fastformer.common.geometry.interaction.GeometryInteractionHit;
import io.github.fastformer.common.geometry.interaction.GeometryInteractionTarget;
import io.github.fastformer.common.geometry.interaction.PointerGesture;
import io.github.fastformer.common.geometry.PolyhedronSizeMode;
import io.github.fastformer.common.placement.context.PlaceableItems;
import io.github.fastformer.common.selection.session.*;
import io.github.fastformer.server.settings.FastPlaceSettings;
import io.github.fastformer.server.feedback.PlayerMessages;
import io.github.fastformer.server.geometry.workflow.GeometryWorkflowEditor;
import io.github.fastformer.server.geometry.workflow.GeometryWorkflowEditors;
import io.github.fastformer.server.sync.PreviewSync;
import io.github.fastformer.server.input.ServerInputActions;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

public final class GeometrySystem implements io.github.fastformer.server.mode.ServerEditor<GeometrySession> {
   final PlayerSessions<GeometrySession> sessions;
   private final SessionSystem runtime;

   GeometrySystem(SessionSystem runtime) {
      this.runtime = runtime;
      sessions = new PlayerSessions<>(net.minecraft.resources.ResourceLocation.parse("fastformer:geometry"),
         session -> io.github.fastformer.common.mode.BuiltinModes.geometry(session.mode()), runtime.eventStream()::publish);
   }

   public static GeometrySystem of(ServerPlayer player) { return of(player.getServer()); }
   public static GeometrySystem of(net.minecraft.server.MinecraftServer server) { return SessionSystem.of(server).geometry; }

   public boolean active(ServerPlayer player) {
      return sessions.hasActive(player.getUUID());
   }

   public Optional<GeometrySession> session(ServerPlayer player) {
      return Optional.ofNullable(sessions.active(player.getUUID()));
   }

   @Override public void activate(ServerPlayer player, GeometrySession session) {
      sessions.activate(player.getUUID(), session);
   }

   public void initialize(ServerPlayer player, GeometrySession session) {
      GeometryWorkflowEditors.get(session.mode()).onOpen(session, context(player));
   }

   public boolean allows(ServerPlayer player, GeometryAction action) {
      GeometrySession session = sessions.active(player.getUUID());
      return session != null && GeometryWorkflowEditors.get(session.mode()).allows(session, action);
   }

   public boolean confirmsOnRightClick(ServerPlayer player) {
      GeometrySession session = sessions.active(player.getUUID());
      return session != null && GeometryWorkflowEditors.get(session.mode()).confirmsOnRightClick(session);
   }

   public boolean awaitingFirstPoint(ServerPlayer player) {
      GeometrySession session = sessions.active(player.getUUID());
      return session != null && session.points().isEmpty();
   }

   public void start(ServerPlayer player, BlockPos point) {
      start(player, GeometryHit.point(point));
   }

   public void start(ServerPlayer player, GeometryHit hit) {
      GeometrySession session = new GeometrySession();
      runtime.modes().geometry(session.mode()).open(player, session);
      GeometryWorkflowEditor<?> workflow = GeometryWorkflowEditors.get(session.mode());
      if (hit == null || !workflow.allows(session, GeometryAction.POINT_INPUT)) {
         session.addPoint(hit == null ? BlockPos.ZERO : hit.point());
      } else {
         workflow.onAddPoint(session, context(player, hit), hit);
      }
      PreviewSync.syncGeometry(player, session);
   }

   public void selectMode(ServerPlayer player, GeometryMode mode) {
      GeometrySession session = session(player).orElseGet(GeometrySession::new);
      session.setMode(mode);
      runtime.modes().geometry(session.mode()).open(player, session);
      PreviewSync.syncGeometry(player, session);
   }

   public void addPoint(ServerPlayer player, BlockPos point) {
      addPoint(player, GeometryHit.point(point));
   }

   public void addPoint(ServerPlayer player, GeometryHit hit) {
      addPoint(player, hit, context(player, hit));
   }

   public void addPoint(ServerPlayer player, GeometryHit hit, Vec3 eye, Vec3 view) {
      addPoint(player, hit, context(player, hit, eye, view));
   }

   private void addPoint(ServerPlayer player, GeometryHit hit, GeometryActionContext context) {
      GeometrySession session = sessions.active(player.getUUID());
      if (session != null) {
         GeometryWorkflowEditor<?> workflow = GeometryWorkflowEditors.get(session.mode());
         if (!workflow.allows(session, GeometryAction.POINT_INPUT)) {
            Component message = workflow.pointBlockedMessage(session);
            if (!message.getString().isEmpty()) {
               PlayerMessages.actionBar(player, message);
            }
            return;
         }
         workflow.onAddPoint(session, context, hit);
         PreviewSync.syncGeometry(player, session);
      }
   }

   public void removeOrUndo(ServerPlayer player, BlockPos point) {
      GeometrySession session = sessions.active(player.getUUID());
      if (session != null && !session.removeOrUndo(point)) {
         cancel(player);
      } else if (session != null) {
         PreviewSync.syncGeometry(player, session);
      }
   }

   public boolean closePath(ServerPlayer player) {
      GeometrySession session = sessions.active(player.getUUID());
      if (session == null) {
         return false;
      }
      GeometryWorkflowEditor<?> workflow = GeometryWorkflowEditors.get(session.mode());
      if (!workflow.onClosePath(session, context(player))) {
         return false;
      }
      PreviewSync.syncGeometry(player, session);
      return true;
   }

   public void sync(ServerPlayer player) {
      GeometrySession session = sessions.active(player.getUUID());
      if (session != null) {
         PreviewSync.syncGeometry(player, session);
      } else {
         PreviewSync.clearGeometry(player);
      }
   }

   public void scroll(ServerPlayer player, int steps) {
      GeometrySession session = sessions.active(player.getUUID());
      if (session != null && steps != 0) {
         GeometryWorkflowEditor<?> workflow = GeometryWorkflowEditors.get(session.mode());
         if (!workflow.allows(session, GeometryAction.SCALAR_ADJUST)) {
            return;
         }
         if (!workflow.onScroll(session, context(player), steps)) {
            return;
         }
         PreviewSync.syncGeometry(player, session);
      }
   }

   public boolean gizmoDrag(ServerPlayer player, AxisGizmo.Operation operation, Axis axis, int steps, boolean finish) {
      GeometrySession session = sessions.active(player.getUUID());
      if (session == null) {
         return false;
      }
      if (!GeometryWorkflowEditors.get(session.mode()).allows(session, GeometryAction.GIZMO_DRAG)) {
         return false;
      }
      if (!finish && steps != 0 && !GeometryWorkflowEditors.get(session.mode()).onGizmoDrag(session, context(player), operation, axis, steps)) {
         return false;
      }
      PreviewSync.syncGeometry(player, session);
      return true;
   }

   /** Applies an interaction against the ray that the client captured with its preview revision. */
   public boolean interaction(
      ServerPlayer player,
      GeometryInteractionTarget.TargetType targetType,
      int index,
      GeometryInteractionAction action,
      PointerGesture gesture,
      Vec3 eye,
      Vec3 view
   ) {
      GeometrySession session = sessions.active(player.getUUID());
      if (session == null || targetType == null || action == null || gesture == null || eye == null || view == null) {
         return false;
      }
      if (action == GeometryInteractionAction.CLEAR_SELECTION) {
         if (targetType != GeometryInteractionTarget.TargetType.CONTROL_POINT
            || !GeometryWorkflowEditors.get(session.mode()).clearSelection(session, gesture)) {
            return false;
         }
         PreviewSync.syncGeometry(player, session);
         return true;
      }
      GeometryWorkflowEditor<?> workflow = GeometryWorkflowEditors.get(session.mode());
      GeometryInteractionTarget target = workflow.interactionTargets(session).stream()
         .filter(candidate -> candidate.type() == targetType && candidate.index() == index)
         .findFirst()
         .orElse(null);
      if (target == null || target.action(gesture) != action) {
         return false;
      }
      GeometryInteractionHit hit = GeometryInteractionHit.nearest(
         eye,
         view,
         ServerInputActions.visibleExtendedReach(player, eye, view),
         List.of(target)
      );
      if (hit == null) {
         return false;
      }
      if (!workflow.onInteraction(session, context(player, null, eye, view),
         targetType, index, action, gesture)) {
         return false;
      }
      PreviewSync.syncGeometry(player, session);
      return true;
   }

   public boolean rotateEuler(ServerPlayer player, double xDegrees, double yDegrees, double zDegrees) {
      GeometrySession session = sessions.active(player.getUUID());
      if (session == null || session.mode() != GeometryMode.POLYHEDRON) {
         PlayerMessages.chat(player, PlayerMessages.text("fastformer.message.geometry_polyhedron_only"));
         return false;
      }
      if (!session.polyhedron().setEulerDegrees(xDegrees, yDegrees, zDegrees)) {
         PlayerMessages.chat(player, PlayerMessages.text("fastformer.message.rotate_invalid"));
         return false;
      }
      PreviewSync.syncGeometry(player, session);
      PlayerMessages.chat(player, PlayerMessages.text("fastformer.message.rotate_euler_value", xDegrees, yDegrees, zDegrees));
      return true;
   }

   public boolean rotateQuaternion(ServerPlayer player, double w, double x, double y, double z) {
      GeometrySession session = sessions.active(player.getUUID());
      if (session == null || session.mode() != GeometryMode.POLYHEDRON) {
         PlayerMessages.chat(player, PlayerMessages.text("fastformer.message.geometry_polyhedron_only"));
         return false;
      }
      if (!session.polyhedron().setQuaternion(w, x, y, z)) {
         PlayerMessages.chat(player, PlayerMessages.text("fastformer.message.rotate_invalid"));
         return false;
      }
      PreviewSync.syncGeometry(player, session);
      PlayerMessages.chat(player, PlayerMessages.text("fastformer.message.rotate_quat_value", w, x, y, z));
      return true;
   }

   public boolean rotateMatrix(ServerPlayer player, double[] matrix) {
      GeometrySession session = sessions.active(player.getUUID());
      if (session == null || session.mode() != GeometryMode.POLYHEDRON) {
         PlayerMessages.chat(player, PlayerMessages.text("fastformer.message.geometry_polyhedron_only"));
         return false;
      }
      if (!session.polyhedron().setMatrix(matrix)) {
         PlayerMessages.chat(player, PlayerMessages.text("fastformer.message.rotate_invalid"));
         return false;
      }
      PreviewSync.syncGeometry(player, session);
      PlayerMessages.chat(player, PlayerMessages.text("fastformer.message.rotate_matrix_set"));
      return true;
   }

   public boolean rotateSnap(ServerPlayer player, int axis, int steps, boolean fine) {
      GeometrySession session = sessions.active(player.getUUID());
      if (session == null || session.mode() != GeometryMode.POLYHEDRON) {
         PlayerMessages.chat(player, PlayerMessages.text("fastformer.message.geometry_polyhedron_only"));
         return false;
      }
      session.polyhedron().rotateSnap(axis, steps, fine ? 1024 : 16);
      PreviewSync.syncGeometry(player, session);
      PlayerMessages.chat(player, PlayerMessages.text("fastformer.message.rotate_snap_value", axisName(axis), steps, fine ? 1024 : 16));
      return true;
   }

   public boolean setConePlaneMode(ServerPlayer player, ConePlaneMode mode) {
      GeometrySession session = sessions.active(player.getUUID());
      if (session == null || session.mode() != GeometryMode.CONE_PRISM) {
         PlayerMessages.chat(player, PlayerMessages.text("fastformer.message.cone_session_only"));
         return false;
      }
      session.cone().setConePlaneMode(mode);
      FastPlaceSettings.load(player).setConePlaneMode(player, mode);
      PreviewSync.syncGeometry(player, session);
      PlayerMessages.chat(player, PlayerMessages.text("fastformer.message.cone_plane_value", PlayerMessages.text(mode)));
      return true;
   }

   public boolean setPolyhedronSizeMode(ServerPlayer player, PolyhedronSizeMode mode) {
      GeometrySession session = sessions.active(player.getUUID());
      if (session == null || session.mode() != GeometryMode.POLYHEDRON || session.closed()) {
         PlayerMessages.chat(player, PlayerMessages.text("fastformer.message.geometry_polyhedron_only"));
         return false;
      }
      session.polyhedron().setPolyhedronSizeMode(mode);
      PreviewSync.syncGeometry(player, session);
      return true;
   }

   public boolean cycleMode(ServerPlayer player) {
      GeometrySession session = sessions.active(player.getUUID());
      if (session == null) {
         return false;
      }
      GeometryWorkflowEditor<?> workflow = GeometryWorkflowEditors.get(session.mode());
      if (!workflow.allows(session, GeometryAction.MODE_CYCLE) || !workflow.onCycleMode(session, context(player))) {
         return false;
      }
      PreviewSync.syncGeometry(player, session);
      return true;
   }

   public boolean setConeEllipse(ServerPlayer player, double scaleX, double scaleZ) {
      GeometrySession session = sessions.active(player.getUUID());
      if (session == null || session.mode() != GeometryMode.CONE_PRISM) {
         PlayerMessages.chat(player, PlayerMessages.text("fastformer.message.cone_session_only"));
         return false;
      }
      if (!session.cone().setConeScale(scaleX, scaleZ)) {
         PlayerMessages.chat(player, PlayerMessages.text("fastformer.message.number_invalid"));
         return false;
      }
      PreviewSync.syncGeometry(player, session);
      PlayerMessages.chat(player, PlayerMessages.text("fastformer.message.cone_scale_value", session.cone().coneScaleX(), session.cone().coneScaleZ()));
      return true;
   }

   public boolean fill(ServerPlayer player) {
      GeometrySession session = sessions.active(player.getUUID());
      GeometryWorkflowEditor<?> workflow = session == null ? null : GeometryWorkflowEditors.get(session.mode());
      if (session == null
         || !workflow.allows(session, GeometryAction.CONFIRM)
         || !workflow.canFill(session)
         || PlaceableItems.defaultBlockState(player.getMainHandItem()).isEmpty()) {
         PlayerMessages.actionBar(player, fillBlockedMessage(session, player));
         return false;
      }
      BlockState placeState = PlaceableItems.defaultBlockState(player.getMainHandItem()).orElseThrow();
      int max = FastPlaceSettings.load(player).maxPlacement();
      FillMode fillMode = FastPlaceSettings.load(player).fillMode();
      GeometryBuildResult build = workflow.build(session, fillMode, max + 1);
      if (!build.ready()) {
         PlayerMessages.actionBar(player, build.blockedReason());
         return false;
      }
      if (!runtime.quickShape.queueGeneratedPlacement(
         player,
         build.generation(),
         placeState,
         build.scanCells(),
         build.targetCapacity()
      )) {
         return false;
      }
      cancel(player);
      return true;
   }

   public void cancel(ServerPlayer player) {
      if (sessions.end(player.getUUID()) != null) {
         PreviewSync.clearGeometry(player);
      }
   }

   public void remove(ServerPlayer player) {
      sessions.discard(player.getUUID());
   }

   /** Drops server-bound geometry sessions before a world instance is replaced. */
   @Override public void onDiscard() {
      sessions.clear();
   }

   /**
    * Moves the geometry session of a dimension aside. A shape is dimension-bound
    * because its points are absolute world coordinates.
    */
   @Override public void parkDimensionSession(UUID owner, ResourceKey<Level> dimension) {
      sessions.park(owner, dimension);
   }

   /** Restores the geometry session that belongs to the target dimension. */
   @Override public void restoreDimensionSession(UUID owner, ResourceKey<Level> dimension) {
      sessions.restore(owner, dimension);
   }

   private Component fillBlockedMessage(GeometrySession session, ServerPlayer player) {
      if (PlaceableItems.defaultBlockState(player.getMainHandItem()).isEmpty()) {
         return Component.translatable("fastformer.geometry.message.fill_blocked_hold_placeable");
      }
      if (session == null) {
         return Component.translatable("fastformer.geometry.message.fill_blocked_no_session");
      }
      return GeometryWorkflowEditors.get(session.mode()).blockedMessage(session);
   }

   private String axisName(int axis) {
      return switch (Math.floorMod(axis, 3)) {
         case 0 -> "x";
         case 1 -> "y";
         default -> "z";
      };
   }

   private GeometryActionContext context(ServerPlayer player) {
      return context(player, null);
   }

   private GeometryActionContext context(ServerPlayer player, GeometryHit hit) {
      return context(player, hit, player.getEyePosition(), player.getViewVector(1.0F));
   }

   private GeometryActionContext context(ServerPlayer player, GeometryHit hit, Vec3 eye, Vec3 view) {
      var preferences = new GeometryActionContext.Preferences(FastPlaceSettings.load(player).conePlaneMode(),
         mode -> FastPlaceSettings.load(player).setConePlaneMode(player, mode));
      return new GeometryActionContext(player, runtime.modifierHeld(player), hit, eye, view, preferences);
   }
}
