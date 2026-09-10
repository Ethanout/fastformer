package io.github.fastformer.fastplace;

import io.github.fastformer.fastplace.world.*;

import io.github.fastformer.fastplace.workflow.*;
import io.github.fastformer.fastplace.session.*;
import io.github.fastformer.network.FastPlaceNetwork;
import io.github.fastformer.fastplace.geometry.AxisGizmo;
import io.github.fastformer.fastplace.geometry.GeometryAction;
import io.github.fastformer.fastplace.geometry.GeometryBuildResult;
import io.github.fastformer.fastplace.geometry.GeometryInteractionAction;
import io.github.fastformer.fastplace.geometry.GeometryInteractionHit;
import io.github.fastformer.fastplace.geometry.GeometryInteractionTarget;
import io.github.fastformer.fastplace.geometry.PointerGesture;
import java.util.List;
import net.minecraft.network.chat.Component;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;

public final class GeometryManager {
   private static final Map<UUID, GeometrySession> SESSIONS = new HashMap<>();

   private GeometryManager() {
   }

   public static boolean active(ServerPlayer player) {
      return SESSIONS.containsKey(player.getUUID());
   }

   public static Optional<GeometrySession> session(ServerPlayer player) {
      return Optional.ofNullable(SESSIONS.get(player.getUUID()));
   }

   public static boolean allows(ServerPlayer player, GeometryAction action) {
      GeometrySession session = SESSIONS.get(player.getUUID());
      return session != null && GeometryWorkflows.get(session.mode()).allows(session, action);
   }

   public static boolean confirmsOnRightClick(ServerPlayer player) {
      GeometrySession session = SESSIONS.get(player.getUUID());
      return session != null && GeometryWorkflows.get(session.mode()).allows(session, GeometryAction.CONFIRM);
   }

   public static boolean awaitingFirstPoint(ServerPlayer player) {
      GeometrySession session = SESSIONS.get(player.getUUID());
      return session != null && session.points().isEmpty();
   }

   public static void start(ServerPlayer player, BlockPos point) {
      start(player, GeometryHit.point(point));
   }

   public static void start(ServerPlayer player, GeometryHit hit) {
      GeometrySession session = new GeometrySession();
      SESSIONS.put(player.getUUID(), session);
      GeometryWorkflow workflow = GeometryWorkflows.get(session.mode());
      if (hit == null || !workflow.allows(session, GeometryAction.POINT_INPUT)) {
         session.addPoint(hit == null ? BlockPos.ZERO : hit.point());
      } else {
         workflow.onAddPoint(session, context(player, hit), hit);
      }
      FastPlaceNetwork.syncGeometry(player, session);
   }

   public static void selectMode(ServerPlayer player, GeometryMode mode) {
      GeometrySession session = SESSIONS.computeIfAbsent(player.getUUID(), ignored -> new GeometrySession());
      session.setMode(mode);
      if (mode == GeometryMode.CONE_PRISM) {
         session.setConePlaneMode(FastPlaceSettings.load(player).conePlaneMode());
      }
      FastPlaceNetwork.syncGeometry(player, session);
   }

   public static void addPoint(ServerPlayer player, BlockPos point) {
      addPoint(player, GeometryHit.point(point));
   }

   public static void addPoint(ServerPlayer player, GeometryHit hit) {
      GeometrySession session = SESSIONS.get(player.getUUID());
      if (session != null) {
         GeometryWorkflow workflow = GeometryWorkflows.get(session.mode());
         if (!workflow.allows(session, GeometryAction.POINT_INPUT)) {
            Component message = workflow.pointBlockedMessage(session);
            if (!message.getString().isEmpty()) {
               FastPlaceMessages.actionBar(player, message);
            }
            return;
         }
         workflow.onAddPoint(session, context(player, hit), hit);
         FastPlaceNetwork.syncGeometry(player, session);
      }
   }

   public static void removeOrUndo(ServerPlayer player, BlockPos point) {
      GeometrySession session = SESSIONS.get(player.getUUID());
      if (session != null && !session.removeOrUndo(point)) {
         cancel(player);
      } else if (session != null) {
         FastPlaceNetwork.syncGeometry(player, session);
      }
   }

   public static boolean closePath(ServerPlayer player) {
      GeometrySession session = SESSIONS.get(player.getUUID());
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

   public static void sync(ServerPlayer player) {
      GeometrySession session = SESSIONS.get(player.getUUID());
      if (session != null) {
         FastPlaceNetwork.syncGeometry(player, session);
      } else {
         FastPlaceNetwork.clearGeometry(player);
      }
   }

   public static void scroll(ServerPlayer player, int steps) {
      GeometrySession session = SESSIONS.get(player.getUUID());
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

   public static void gizmoDrag(ServerPlayer player, AxisGizmo.Operation operation, AxisGizmo.Axis axis, int steps, boolean finish) {
      GeometrySession session = SESSIONS.get(player.getUUID());
      if (session == null) {
         return;
      }
      if (!GeometryWorkflows.get(session.mode()).allows(session, GeometryAction.GIZMO_DRAG)) {
         return;
      }
      if (!finish && steps != 0 && !GeometryWorkflows.get(session.mode()).onGizmoDrag(session, context(player), operation, axis, steps)) {
         return;
      }
      FastPlaceNetwork.syncGeometry(player, session);
   }

   public static boolean interaction(
      ServerPlayer player,
      GeometryInteractionTarget.TargetType targetType,
      int index,
      GeometryInteractionAction action,
      PointerGesture gesture
   ) {
      GeometrySession session = SESSIONS.get(player.getUUID());
      if (session == null || targetType == null || action == null || gesture == null) {
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
         player.getEyePosition(),
         player.getViewVector(1.0F),
         ServerInputDispatcher.visibleExtendedReach(player),
         List.of(target)
      );
      if (hit == null) {
         return false;
      }
      if (!workflow.onInteraction(session, context(player), targetType, index, action, gesture)) {
         return false;
      }
      FastPlaceNetwork.syncGeometry(player, session);
      return true;
   }

   private static boolean isClick(PointerGesture gesture) {
      return gesture == PointerGesture.LEFT_CLICK || gesture == PointerGesture.RIGHT_CLICK;
   }

   public static boolean rotateEuler(ServerPlayer player, double xDegrees, double yDegrees, double zDegrees) {
      GeometrySession session = SESSIONS.get(player.getUUID());
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

   public static boolean rotateQuaternion(ServerPlayer player, double w, double x, double y, double z) {
      GeometrySession session = SESSIONS.get(player.getUUID());
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

   public static boolean rotateMatrix(ServerPlayer player, double[] matrix) {
      GeometrySession session = SESSIONS.get(player.getUUID());
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

   public static boolean rotateSnap(ServerPlayer player, int axis, int steps, boolean fine) {
      GeometrySession session = SESSIONS.get(player.getUUID());
      if (session == null || session.mode() != GeometryMode.POLYHEDRON) {
         FastPlaceMessages.chat(player, FastPlaceMessages.text("fastformer.message.geometry_polyhedron_only"));
         return false;
      }
      session.rotateSnap(axis, steps, fine ? 1024 : 16);
      FastPlaceNetwork.syncGeometry(player, session);
      FastPlaceMessages.chat(player, FastPlaceMessages.text("fastformer.message.rotate_snap_value", axisName(axis), steps, fine ? 1024 : 16));
      return true;
   }

   public static boolean setConePlaneMode(ServerPlayer player, ConePlaneMode mode) {
      GeometrySession session = SESSIONS.get(player.getUUID());
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

   public static boolean setPolyhedronSizeMode(ServerPlayer player, PolyhedronSizeMode mode) {
      GeometrySession session = SESSIONS.get(player.getUUID());
      if (session == null || session.mode() != GeometryMode.POLYHEDRON || session.closed()) {
         FastPlaceMessages.chat(player, FastPlaceMessages.text("fastformer.message.geometry_polyhedron_only"));
         return false;
      }
      session.setPolyhedronSizeMode(mode);
      FastPlaceNetwork.syncGeometry(player, session);
      return true;
   }

   public static boolean cycleMode(ServerPlayer player) {
      GeometrySession session = SESSIONS.get(player.getUUID());
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

   public static boolean setConeEllipse(ServerPlayer player, double scaleX, double scaleZ) {
      GeometrySession session = SESSIONS.get(player.getUUID());
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

   public static boolean fill(ServerPlayer player) {
      GeometrySession session = SESSIONS.get(player.getUUID());
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
      if (!FastPlaceManager.queueGeneratedPlacement(
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

   public static void cancel(ServerPlayer player) {
      if (SESSIONS.remove(player.getUUID()) != null) {
         FastPlaceNetwork.clearGeometry(player);
      }
   }

   public static void remove(ServerPlayer player) {
      SESSIONS.remove(player.getUUID());
   }

   /** Drops server-bound geometry sessions before a world instance is replaced. */
   public static void clearServer() {
      SESSIONS.clear();
   }

   private static Component fillBlockedMessage(GeometrySession session, ServerPlayer player) {
      if (PlaceableItems.defaultBlockState(player.getMainHandItem()).isEmpty()) {
         return Component.translatable("fastformer.geometry.message.fill_blocked_hold_placeable");
      }
      if (session == null) {
         return Component.translatable("fastformer.geometry.message.fill_blocked_no_session");
      }
      return GeometryWorkflows.get(session.mode()).blockedMessage(session);
   }

   private static String axisName(int axis) {
      return switch (Math.floorMod(axis, 3)) {
         case 0 -> "x";
         case 1 -> "y";
         default -> "z";
      };
   }

   private static GeometryActionContext context(ServerPlayer player) {
      return new GeometryActionContext(player, FastPlaceManager.modifierHeld(player));
   }

   private static GeometryActionContext context(ServerPlayer player, GeometryHit hit) {
      return new GeometryActionContext(player, FastPlaceManager.modifierHeld(player), hit);
   }
}

