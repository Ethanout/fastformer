package io.github.fastformer.server.session;

import io.github.fastformer.core.math.Axis;

import io.github.fastformer.server.session.slot.PlayerSessions;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import io.github.fastformer.fastplace.geometry.AxisGizmo;
import io.github.fastformer.fastplace.geometry.FillMode;
import io.github.fastformer.fastplace.geometry.GeometryAction;
import io.github.fastformer.fastplace.geometry.GeometryActionContext;
import io.github.fastformer.fastplace.geometry.GeometryBuildResult;
import io.github.fastformer.fastplace.geometry.GeometryHit;
import io.github.fastformer.fastplace.geometry.GeometryMode;
import io.github.fastformer.fastplace.geometry.cone.ConePlaneMode;
import io.github.fastformer.fastplace.geometry.cone.ConePrismStage;
import io.github.fastformer.fastplace.geometry.interaction.GeometryInteractionAction;
import io.github.fastformer.fastplace.geometry.interaction.GeometryInteractionHit;
import io.github.fastformer.fastplace.geometry.interaction.GeometryInteractionTarget;
import io.github.fastformer.fastplace.geometry.interaction.PointerGesture;
import io.github.fastformer.fastplace.geometry.polyhedron.PolyhedronSizeMode;
import io.github.fastformer.fastplace.placement.context.PlaceableItems;
import io.github.fastformer.fastplace.session.*;
import io.github.fastformer.fastplace.settings.FastPlaceSettings;
import io.github.fastformer.fastplace.text.FastPlaceMessages;
import io.github.fastformer.fastplace.workflow.*;
import io.github.fastformer.fastplace.world.*;
import io.github.fastformer.network.FastPlaceNetwork;
import io.github.fastformer.server.input.ServerInputDispatcher;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

public final class GeometrySystem implements io.github.fastformer.core.lifecycle.Scoped {
   final PlayerSessions<GeometrySession> sessions = new PlayerSessions<>();
   private final SessionSystem runtime;

   GeometrySystem(SessionSystem runtime) {
      this.runtime = runtime;
   }

   public static GeometrySystem of(ServerPlayer player) { return of(player.getServer()); }
   public static GeometrySystem of(net.minecraft.server.MinecraftServer server) { return SessionSystem.of(server).geometry; }

   public boolean active(ServerPlayer player) {
      return sessions.hasActive(player.getUUID());
   }

   public Optional<GeometrySession> session(ServerPlayer player) {
      return Optional.ofNullable(sessions.active(player.getUUID()));
   }

   public boolean allows(ServerPlayer player, GeometryAction action) {
      GeometrySession session = sessions.active(player.getUUID());
      return session != null && GeometryWorkflows.get(session.mode()).allows(session, action);
   }

   public boolean confirmsOnRightClick(ServerPlayer player) {
      GeometrySession session = sessions.active(player.getUUID());
      return session != null && GeometryWorkflows.get(session.mode()).allows(session, GeometryAction.CONFIRM);
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
      sessions.activate(player.getUUID(), session);
      GeometryWorkflow workflow = GeometryWorkflows.get(session.mode());
      if (hit == null || !workflow.allows(session, GeometryAction.POINT_INPUT)) {
         session.addPoint(hit == null ? BlockPos.ZERO : hit.point());
      } else {
         workflow.onAddPoint(session, context(player, hit), hit);
      }
      FastPlaceNetwork.syncGeometry(player, session);
   }

   public void selectMode(ServerPlayer player, GeometryMode mode) {
      GeometrySession session = sessions.getOrCreate(player.getUUID(), GeometrySession::new);
      session.setMode(mode);
      if (mode == GeometryMode.CONE_PRISM) {
         session.setConePlaneMode(FastPlaceSettings.load(player).conePlaneMode());
      }
      FastPlaceNetwork.syncGeometry(player, session);
   }

   public void addPoint(ServerPlayer player, BlockPos point) {
      addPoint(player, GeometryHit.point(point));
   }

   public void addPoint(ServerPlayer player, GeometryHit hit) {
      addPoint(player, hit, context(player, hit));
   }

   public void addPoint(ServerPlayer player, GeometryHit hit, Vec3 eye, Vec3 view) {
      addPoint(player, hit, new GeometryActionContext(player, runtime.modifierHeld(player), hit, eye, view));
   }

   private void addPoint(ServerPlayer player, GeometryHit hit, GeometryActionContext context) {
      GeometrySession session = sessions.active(player.getUUID());
      if (session != null) {
         GeometryWorkflow workflow = GeometryWorkflows.get(session.mode());
         if (!workflow.allows(session, GeometryAction.POINT_INPUT)) {
            Component message = workflow.pointBlockedMessage(session);
            if (!message.getString().isEmpty()) {
               FastPlaceMessages.actionBar(player, message);
            }
            return;
         }
         workflow.onAddPoint(session, context, hit);
         FastPlaceNetwork.syncGeometry(player, session);
      }
   }

   public void removeOrUndo(ServerPlayer player, BlockPos point) {
      GeometrySession session = sessions.active(player.getUUID());
      if (session != null && !session.removeOrUndo(point)) {
         cancel(player);
      } else if (session != null) {
         FastPlaceNetwork.syncGeometry(player, session);
      }
   }

   public boolean closePath(ServerPlayer player) {
      GeometrySession session = sessions.active(player.getUUID());
      if (session == null) {
         return false;
      }
      GeometryWorkflow workflow = GeometryWorkflows.get(session.mode());
      if (!workflow.onClosePath(session, context(player))) {
         return false;
      }
      FastPlaceNetwork.syncGeometry(player, session);
      return true;
   }

   public void sync(ServerPlayer player) {
      GeometrySession session = sessions.active(player.getUUID());
      if (session != null) {
         FastPlaceNetwork.syncGeometry(player, session);
      } else {
         FastPlaceNetwork.clearGeometry(player);
      }
   }

   public void scroll(ServerPlayer player, int steps) {
      GeometrySession session = sessions.active(player.getUUID());
      if (session != null && steps != 0) {
         GeometryWorkflow workflow = GeometryWorkflows.get(session.mode());
         if (!workflow.allows(session, GeometryAction.SCALAR_ADJUST)) {
            return;
         }
         if (!workflow.onScroll(session, context(player), steps)) {
            return;
         }
         FastPlaceNetwork.syncGeometry(player, session);
      }
   }

   public boolean gizmoDrag(ServerPlayer player, AxisGizmo.Operation operation, Axis axis, int steps, boolean finish) {
      GeometrySession session = sessions.active(player.getUUID());
      if (session == null) {
         return false;
      }
      if (!GeometryWorkflows.get(session.mode()).allows(session, GeometryAction.GIZMO_DRAG)) {
         return false;
      }
      if (!finish && steps != 0 && !GeometryWorkflows.get(session.mode()).onGizmoDrag(session, context(player), operation, axis, steps)) {
         return false;
      }
      FastPlaceNetwork.syncGeometry(player, session);
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
         if (!isClick(gesture)
            || targetType != GeometryInteractionTarget.TargetType.CONTROL_POINT
            || session.mode() != GeometryMode.CONVEX_POLYHEDRON
            || session.selectedControlPoint() < 0) {
            return false;
         }
         session.clearSelectedControlPoint();
         FastPlaceNetwork.syncGeometry(player, session);
         return true;
      }
      GeometryWorkflow workflow = GeometryWorkflows.get(session.mode());
      GeometryInteractionTarget target = workflow.interactionTargets(GeometryWorkflowView.from(session)).stream()
         .filter(candidate -> candidate.type() == targetType && candidate.index() == index)
         .findFirst()
         .orElse(null);
      if (target == null || target.action(gesture) != action) {
         return false;
      }
      GeometryInteractionHit hit = GeometryInteractionHit.nearest(
         eye,
         view,
         ServerInputDispatcher.visibleExtendedReach(player, eye, view),
         List.of(target)
      );
      if (hit == null) {
         return false;
      }
      if (!workflow.onInteraction(session, new GeometryActionContext(player, runtime.modifierHeld(player), null, eye, view),
         targetType, index, action, gesture)) {
         return false;
      }
      FastPlaceNetwork.syncGeometry(player, session);
      return true;
   }

   private boolean isClick(PointerGesture gesture) {
      return gesture == PointerGesture.LEFT_CLICK || gesture == PointerGesture.RIGHT_CLICK;
   }

   public boolean rotateEuler(ServerPlayer player, double xDegrees, double yDegrees, double zDegrees) {
      GeometrySession session = sessions.active(player.getUUID());
      if (session == null || session.mode() != GeometryMode.POLYHEDRON) {
         FastPlaceMessages.chat(player, FastPlaceMessages.text("fastformer.message.geometry_polyhedron_only"));
         return false;
      }
      if (!session.setEulerDegrees(xDegrees, yDegrees, zDegrees)) {
         FastPlaceMessages.chat(player, FastPlaceMessages.text("fastformer.message.rotate_invalid"));
         return false;
      }
      FastPlaceNetwork.syncGeometry(player, session);
      FastPlaceMessages.chat(player, FastPlaceMessages.text("fastformer.message.rotate_euler_value", xDegrees, yDegrees, zDegrees));
      return true;
   }

   public boolean rotateQuaternion(ServerPlayer player, double w, double x, double y, double z) {
      GeometrySession session = sessions.active(player.getUUID());
      if (session == null || session.mode() != GeometryMode.POLYHEDRON) {
         FastPlaceMessages.chat(player, FastPlaceMessages.text("fastformer.message.geometry_polyhedron_only"));
         return false;
      }
      if (!session.setQuaternion(w, x, y, z)) {
         FastPlaceMessages.chat(player, FastPlaceMessages.text("fastformer.message.rotate_invalid"));
         return false;
      }
      FastPlaceNetwork.syncGeometry(player, session);
      FastPlaceMessages.chat(player, FastPlaceMessages.text("fastformer.message.rotate_quat_value", w, x, y, z));
      return true;
   }

   public boolean rotateMatrix(ServerPlayer player, double[] matrix) {
      GeometrySession session = sessions.active(player.getUUID());
      if (session == null || session.mode() != GeometryMode.POLYHEDRON) {
         FastPlaceMessages.chat(player, FastPlaceMessages.text("fastformer.message.geometry_polyhedron_only"));
         return false;
      }
      if (!session.setMatrix(matrix)) {
         FastPlaceMessages.chat(player, FastPlaceMessages.text("fastformer.message.rotate_invalid"));
         return false;
      }
      FastPlaceNetwork.syncGeometry(player, session);
      FastPlaceMessages.chat(player, FastPlaceMessages.text("fastformer.message.rotate_matrix_set"));
      return true;
   }

   public boolean rotateSnap(ServerPlayer player, int axis, int steps, boolean fine) {
      GeometrySession session = sessions.active(player.getUUID());
      if (session == null || session.mode() != GeometryMode.POLYHEDRON) {
         FastPlaceMessages.chat(player, FastPlaceMessages.text("fastformer.message.geometry_polyhedron_only"));
         return false;
      }
      session.rotateSnap(axis, steps, fine ? 1024 : 16);
      FastPlaceNetwork.syncGeometry(player, session);
      FastPlaceMessages.chat(player, FastPlaceMessages.text("fastformer.message.rotate_snap_value", axisName(axis), steps, fine ? 1024 : 16));
      return true;
   }

   public boolean setConePlaneMode(ServerPlayer player, ConePlaneMode mode) {
      GeometrySession session = sessions.active(player.getUUID());
      if (session == null || session.mode() != GeometryMode.CONE_PRISM) {
         FastPlaceMessages.chat(player, FastPlaceMessages.text("fastformer.message.cone_session_only"));
         return false;
      }
      session.setConePlaneMode(mode);
      FastPlaceSettings.load(player).setConePlaneMode(player, mode);
      FastPlaceNetwork.syncGeometry(player, session);
      FastPlaceMessages.chat(player, FastPlaceMessages.text("fastformer.message.cone_plane_value", FastPlaceMessages.text(mode)));
      return true;
   }

   public boolean setPolyhedronSizeMode(ServerPlayer player, PolyhedronSizeMode mode) {
      GeometrySession session = sessions.active(player.getUUID());
      if (session == null || session.mode() != GeometryMode.POLYHEDRON || session.closed()) {
         FastPlaceMessages.chat(player, FastPlaceMessages.text("fastformer.message.geometry_polyhedron_only"));
         return false;
      }
      session.setPolyhedronSizeMode(mode);
      FastPlaceNetwork.syncGeometry(player, session);
      return true;
   }

   public boolean cycleMode(ServerPlayer player) {
      GeometrySession session = sessions.active(player.getUUID());
      if (session == null) {
         return false;
      }
      GeometryWorkflow workflow = GeometryWorkflows.get(session.mode());
      if (!workflow.allows(session, GeometryAction.MODE_CYCLE) || !workflow.onCycleMode(session, context(player))) {
         return false;
      }
      if (session.mode() == GeometryMode.CONE_PRISM && session.coneStage() == ConePrismStage.FACE) {
         FastPlaceSettings.load(player).setConePlaneMode(player, session.conePlaneMode());
      }
      FastPlaceNetwork.syncGeometry(player, session);
      return true;
   }

   public boolean setConeEllipse(ServerPlayer player, double scaleX, double scaleZ) {
      GeometrySession session = sessions.active(player.getUUID());
      if (session == null || session.mode() != GeometryMode.CONE_PRISM) {
         FastPlaceMessages.chat(player, FastPlaceMessages.text("fastformer.message.cone_session_only"));
         return false;
      }
      if (!session.setConeScale(scaleX, scaleZ)) {
         FastPlaceMessages.chat(player, FastPlaceMessages.text("fastformer.message.number_invalid"));
         return false;
      }
      FastPlaceNetwork.syncGeometry(player, session);
      FastPlaceMessages.chat(player, FastPlaceMessages.text("fastformer.message.cone_scale_value", session.coneScaleX(), session.coneScaleZ()));
      return true;
   }

   public boolean fill(ServerPlayer player) {
      GeometrySession session = sessions.active(player.getUUID());
      GeometryWorkflow workflow = session == null ? null : GeometryWorkflows.get(session.mode());
      if (session == null
         || !workflow.allows(session, GeometryAction.CONFIRM)
         || !workflow.canFill(session)
         || PlaceableItems.defaultBlockState(player.getMainHandItem()).isEmpty()) {
         FastPlaceMessages.actionBar(player, fillBlockedMessage(session, player));
         return false;
      }
      BlockState placeState = PlaceableItems.defaultBlockState(player.getMainHandItem()).orElseThrow();
      int max = FastPlaceSettings.load(player).maxPlacement();
      FillMode fillMode = FastPlaceSettings.load(player).fillMode();
      GeometryBuildResult build = workflow.build(session, context(player), fillMode, max + 1);
      if (!build.ready()) {
         FastPlaceMessages.actionBar(player, build.blockedReason());
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
         FastPlaceNetwork.clearGeometry(player);
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
   void parkDimensionSession(UUID owner, ResourceKey<Level> dimension) {
      sessions.park(owner, dimension);
   }

   /** Restores the geometry session that belongs to the target dimension. */
   void restoreDimensionSession(UUID owner, ResourceKey<Level> dimension) {
      sessions.restore(owner, dimension);
   }

   private Component fillBlockedMessage(GeometrySession session, ServerPlayer player) {
      if (PlaceableItems.defaultBlockState(player.getMainHandItem()).isEmpty()) {
         return Component.translatable("fastformer.geometry.message.fill_blocked_hold_placeable");
      }
      if (session == null) {
         return Component.translatable("fastformer.geometry.message.fill_blocked_no_session");
      }
      return GeometryWorkflows.get(session.mode()).blockedMessage(session);
   }

   private String axisName(int axis) {
      return switch (Math.floorMod(axis, 3)) {
         case 0 -> "x";
         case 1 -> "y";
         default -> "z";
      };
   }

   private GeometryActionContext context(ServerPlayer player) {
      return new GeometryActionContext(player, runtime.modifierHeld(player));
   }

   private GeometryActionContext context(ServerPlayer player, GeometryHit hit) {
      return new GeometryActionContext(player, runtime.modifierHeld(player), hit);
   }
}
