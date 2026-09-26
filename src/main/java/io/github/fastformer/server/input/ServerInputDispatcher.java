package io.github.fastformer.server.input;

import io.github.fastformer.server.input.ServerInputState.OperationTransformIdentity;
import io.github.fastformer.server.input.ServerInputState.OperationExtendIdentity;
import io.github.fastformer.server.input.ServerInputState.OperationPointGesture;

import io.github.fastformer.server.session.FastPlaceManager;
import io.github.fastformer.fastplace.text.FastPlaceMessages;
import io.github.fastformer.fastplace.settings.FastPlaceSettings;
import io.github.fastformer.server.session.GeometryManager;
import io.github.fastformer.fastplace.session.InteractionState;
import io.github.fastformer.fastplace.geometry.raycast.LongRangeBlockRaycast;
import io.github.fastformer.server.session.OperationManager;
import io.github.fastformer.fastplace.interaction.SpecialItemHandlers;
import io.github.fastformer.fastplace.geometry.GeometryHit;
import io.github.fastformer.fastplace.geometry.GeometryMode;
import io.github.fastformer.fastplace.placement.context.PlaceableItems;
import io.github.fastformer.workspace.submission.OperationWorkspacePlan;

import io.github.fastformer.fastplace.quickshape.QuickShapeDraft;

import io.github.fastformer.fastplace.quickshape.QuickShapeStage;
import io.github.fastformer.fastplace.quickshape.RaycastPlacement;

import io.github.fastformer.fastplace.selection.OperationSelectionMode;

import io.github.fastformer.fastplace.task.TaskCancellationResult;
import io.github.fastformer.fastplace.world.*;

import io.github.fastformer.fastplace.session.*;
import io.github.fastformer.fastplace.geometry.AxisGizmo;
import io.github.fastformer.fastplace.geometry.GeometryAction;
import io.github.fastformer.fastplace.geometry.GeometryInteractionAction;
import io.github.fastformer.fastplace.geometry.GeometryInteractionTarget;
import io.github.fastformer.fastplace.geometry.GeometryPointerSequence;
import io.github.fastformer.fastplace.geometry.GeometryRayVisibility;
import io.github.fastformer.fastplace.geometry.PointerGesture;
import io.github.fastformer.network.payload.geometry.GeometryInteractionPayload;
import io.github.fastformer.network.payload.geometry.GeometryGizmoDragPayload;
import io.github.fastformer.fastplace.geometry.SelectionPrism;
import io.github.fastformer.network.payload.geometry.GeometryPointPayload;
import io.github.fastformer.network.payload.geometry.GeometryUndoPayload;
import io.github.fastformer.network.payload.operation.OperationPointPayload;
import io.github.fastformer.network.payload.operation.OperationPointDragPayload;
import io.github.fastformer.network.payload.operation.OperationPointClickPayload;
import io.github.fastformer.network.payload.operation.OperationSelectPointPayload;
import io.github.fastformer.network.payload.operation.OperationInsertPointPayload;
import io.github.fastformer.network.payload.operation.OperationExtendPayload;
import io.github.fastformer.network.payload.placement.PlacementActionPayload;
import io.github.fastformer.network.payload.placement.QuickShapeConfirmPayload;
import io.github.fastformer.network.payload.placement.StartPlacementPayload;
import io.github.fastformer.network.sync.PlayerPreviewSync;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public final class ServerInputDispatcher {
   public static final double EXTENDED_REACH = LongRangeBlockRaycast.MAX_REACH;
   private static final int MAX_DRAG_STEPS_PER_PACKET = 128;
   private static final int MAX_INHERITED_LINE_OFFSET = 128;
   private static final ServerInputState INPUT_STATE = new ServerInputState();

   public static void endOperationGestures(ServerPlayer player) {
      INPUT_STATE.OPERATION_EXTEND_IDENTITIES.remove(player.getUUID());
      INPUT_STATE.OPERATION_TRANSFORMS.remove(player.getUUID());
   }

   private ServerInputDispatcher() {
   }

   public static boolean canOperate(ServerPlayer player) {
      return player.isCreative()
         && FastPlaceSettings.load(player).enabled()
         && PersistentRecoveryJournal.writesAllowed();
   }

   /** A world task owns all editing input until it releases its resources. */
   public static boolean interactionBlocked(ServerPlayer player) {
      return interactionBlocked(player.getUUID());
   }

   static boolean interactionBlocked(UUID owner) {
      return FastPlaceManager.taskActive(owner)
         || OperationManager.taskActive(owner)
         || WorldHistoryManager.busy(owner);
   }

   public static void stopBecauseUnavailable(ServerPlayer player) {
      boolean taskWasActive = FastPlaceManager.taskActive(player) || OperationManager.taskActive(player);
      FastPlaceManager.cancel(player);
      FastPlaceManager.cancelTask(player);
      OperationManager.cancelTask(player);
      WorldHistoryManager.cancel(player);
      if (taskWasActive && !PersistentRecoveryJournal.writesAllowed()) {
         FastPlaceMessages.actionBar(player, FastPlaceMessages.text("fastformer.message.recovery_journal_blocked"));
      }
   }

   public static boolean rightClickBlock(ServerPlayer player, BlockHitResult hit) {
      if (interactionBlocked(player) || !canOperate(player) || withinNormalBlockReach(player, hit.getLocation())) {
         return false;
      }

      return switch (FastPlaceManager.classify(player)) {
         case OPERATION -> {
            if (!OperationManager.active(player)) {
               OperationManager.startSecond(player, hit.getBlockPos());
               yield true;
            }
            OperationSession session = OperationManager.session(player).orElse(null);
            if (session != null && session.selectionMode() == OperationSelectionMode.CUBOID) {
               OperationManager.setSecond(player, hit.getBlockPos());
               yield true;
            }
            if (OperationManager.needsSelectionPoint(player)) {
               OperationManager.addSelectionPoint(player, hit.getBlockPos());
               yield true;
            }
            yield false;
         }
         case GEOMETRY -> rightClickGeometry(player, hit);
         case SPECIAL_ITEM -> SpecialItemHandlers.useOnBlock(player, hit.getBlockPos());
         case BUILDING -> {
            if (!PlaceableItems.isPlaceable(player.getMainHandItem())) {
               FastPlaceManager.quit(player);
               yield false;
            }
            FastPlaceManager.addPoint(player, hit);
            yield true;
         }
      };
   }

   public static boolean rightClickItem(ServerPlayer player) {
      if (interactionBlocked(player) || !canOperate(player)) {
         return false;
      }

      BlockHitResult hit = raycastBlocks(player, EXTENDED_REACH);
      return switch (FastPlaceManager.classify(player)) {
         case OPERATION -> {
            if (hit.getType() == HitResult.Type.BLOCK && withinNormalBlockReach(player, hit.getLocation())) {
               yield false;
            }
            if (hit.getType() == HitResult.Type.BLOCK && !OperationManager.active(player)) {
               OperationManager.startSecond(player, hit.getBlockPos());
               yield true;
            }
            OperationSession session = OperationManager.session(player).orElse(null);
            if (hit.getType() == HitResult.Type.BLOCK
               && session != null
               && session.selectionMode() == OperationSelectionMode.CUBOID) {
               OperationManager.setSecond(player, hit.getBlockPos());
               yield true;
            }
            if (hit.getType() == HitResult.Type.BLOCK && OperationManager.needsSelectionPoint(player)) {
               OperationManager.addSelectionPoint(player, hit.getBlockPos());
               yield true;
            }
            yield false;
         }
         case BUILDING -> {
            if (hit.getType() == HitResult.Type.BLOCK) {
               if (withinNormalBlockReach(player, hit.getLocation())) {
                  yield false;
               }
               FastPlaceManager.addPoint(player, hit);
            } else {
               if (!FastPlaceManager.active(player)) {
                  yield false;
               }
               QuickShapeDraft session = FastPlaceManager.session(player).orElse(null);
               BlockPos offset = session != null ? session.freeScrollOffset() : BlockPos.ZERO;
               BlockPos first = session != null && !session.points().isEmpty() ? session.points().getFirst() : BlockPos.ZERO;
               BlockPos anchor = first.offset(offset);
               FastPlaceManager.addPoint(player, anchor, anchor);
            }
            yield true;
         }
         case GEOMETRY -> rightClickGeometry(player, hit);
         case SPECIAL_ITEM -> false;
      };
   }

   public static void startPlacement(ServerPlayer player, StartPlacementPayload payload) {
      if (payload == null || interactionBlocked(player) || !canOperate(player)
         || !acceptPlacementAction(player.getUUID(), payload.requestId())) {
         return;
      }
      if (!PlaceableItems.isPlaceable(player.getMainHandItem())
         || FastPlaceManager.active(player)
         || OperationManager.active(player)
         || GeometryManager.active(player)) {
         return;
      }
      StartPlacementPayload.Target target = payload.target();
      if (target.revision() != PlayerPreviewSync.buildingRevision(player)
         || !target.callbackScope().equals(PlayerPreviewSync.callbackScope(player))
         || target.eye().distanceToSqr(player.getEyePosition()) > player.blockInteractionRange() * player.blockInteractionRange()) {
         return;
      }
      BlockHitResult hit = LongRangeBlockRaycast.clipForPlacement(player.level(), player, target.eye(), target.view()).hit();
      if (sameHit(hit, target.hit()) && !withinNormalBlockReach(player, hit.getLocation())) {
         FastPlaceManager.addInitialPoint(player, hit, target.placement() == RaycastPlacement.EMBEDDED, target.eye(), target.view());
      }
   }

   private static boolean sameHit(BlockHitResult left, BlockHitResult right) {
      return left.getType() == HitResult.Type.BLOCK && right.getType() == HitResult.Type.BLOCK
         && left.getBlockPos().equals(right.getBlockPos()) && left.getDirection() == right.getDirection()
         && left.isInside() == right.isInside() && left.getLocation().distanceToSqr(right.getLocation()) <= 1.0E-10;
   }

   public static boolean leftClickBlock(ServerPlayer player, BlockPos point) {
      if (interactionBlocked(player) || !canOperate(player)) {
         return false;
      }
      if (withinNormalBlockReach(player, new AABB(point))) {
         return false;
      }
      return hasActiveSession(player);
   }

   public static void middleClick(ServerPlayer player, BlockPos point) {
      if (!interactionBlocked(player) && canOperate(player) && OperationManager.active(player) && !withinNormalBlockReach(player, new AABB(point))) {
         OperationManager.session(player).ifPresent(session -> {
            if (session.selectionMode() == OperationSelectionMode.CUBOID) {
               OperationManager.expandTo(player, point);
            } else {
               OperationManager.addExtraPoint(player, point);
            }
         });
      }
   }

   public static void extend(ServerPlayer player, int axis, boolean positive, int steps, boolean finish) {
      if (interactionBlocked(player) || !canOperate(player) || !OperationManager.active(player)) {
         return;
      }
      if (!finish && nearNormalBlockReach(player) && !FastPlaceManager.modifierHeld(player)) {
         return;
      }
      OperationManager.extend(player, axis, positive, clampDragSteps(steps), finish);
   }

   public static void operationSelectPoint(ServerPlayer player, OperationSelectPointPayload payload) {
      if (payload == null || payload.revision() != PlayerPreviewSync.operationRevision(player)
         || !payload.callbackScope().equals(PlayerPreviewSync.callbackScope(player))
         || interactionBlocked(player) || !canOperate(player) || !OperationManager.active(player)
         || nearNormalBlockReach(player) && !FastPlaceManager.modifierHeld(player)
         || !acceptsOperationCommand(player, payload.requestId())) return;
      OperationManager.selectPoint(player, payload.index());
   }

   public static void operationPointDrag(ServerPlayer player, OperationPointDragPayload payload) {
      if (interactionBlocked(player) || !canOperate(player) || !OperationManager.active(player)) {
         return;
      }
      OperationPointGesture gesture = acceptsOperationPointDrag(player, payload);
      if (gesture == null) return;
      try {
         OperationManager.dragPoint(player, payload.pointIndex(), payload.target(), payload.constraint(), payload.finish());
      } finally {
         if (payload.finish()) {
            INPUT_STATE.OPERATION_POINT_GESTURES.put(player.getUUID(), gesture.finished(PlayerPreviewSync.operationRevision(player)));
         }
      }
   }

   public static void extend(ServerPlayer player, OperationExtendPayload payload) {
      if (payload == null || !payload.callbackScope().equals(PlayerPreviewSync.callbackScope(player))
         || interactionBlocked(player) || !canOperate(player)) return;
      UUID owner = player.getUUID();
      OperationSession session = OperationManager.session(player).orElse(null);
      if (session == null) {
         INPUT_STATE.OPERATION_EXTEND_IDENTITIES.remove(owner);
         return;
      }
      OperationExtendIdentity active = INPUT_STATE.OPERATION_EXTEND_IDENTITIES.get(owner);
      if (active == null || active.session() != session || payload.requestId() > active.requestId()) {
         if (payload.revision() != PlayerPreviewSync.operationRevision(player)
            || !INPUT_STATE.LAST_OPERATION_EXTEND.accept(owner, payload.requestId())) return;
         if (active != null && active.session() == session) session.commitEdit();
         INPUT_STATE.OPERATION_EXTEND_IDENTITIES.put(owner, new OperationExtendIdentity(session, payload.requestId(), payload.revision(), payload.callbackScope(), payload.axis(), payload.positive()));
      } else if (active.session() != session || active.requestId() != payload.requestId() || active.revision() != payload.revision()
         || !active.scope().equals(payload.callbackScope()) || active.axis() != payload.axis() || active.positive() != payload.positive()) {
         return;
      }
      extend(player, payload.axis(), payload.positive(), payload.steps(), payload.finish());
      if (payload.finish()) INPUT_STATE.OPERATION_EXTEND_IDENTITIES.remove(owner);
   }

   private static OperationPointGesture acceptsOperationPointDrag(ServerPlayer player, OperationPointDragPayload payload) {
      if (payload == null || !payload.callbackScope().equals(PlayerPreviewSync.callbackScope(player))) return null;
      UUID owner = player.getUUID();
      OperationSession session = OperationManager.session(player).orElse(null);
      if (session == null) return null;
      OperationPointGesture previous = INPUT_STATE.OPERATION_POINT_GESTURES.get(owner);
      long currentRevision = PlayerPreviewSync.operationRevision(player);
      if (previous == null || payload.gestureId() > previous.gestureId()) {
         if (payload.revision() != currentRevision) return null;
         var started = new OperationPointGesture(session, payload.gestureId(), payload.revision(), payload.callbackScope(),
            payload.pointIndex(), false, -1L, false);
         INPUT_STATE.OPERATION_POINT_GESTURES.put(owner, started);
         return started;
      }
      if (previous.gestureId() != payload.gestureId() || previous.finished() || previous.consumed()
         || previous.session() != session || previous.revision() != payload.revision()
         || previous.pointIndex() != payload.pointIndex() || !previous.callbackScope().equals(payload.callbackScope())) return null;
      return previous;
   }

   public static void operationPointClick(ServerPlayer player, OperationPointClickPayload payload) {
      if (payload == null || interactionBlocked(player) || !canOperate(player) || !OperationManager.active(player)) return;
      OperationPointGesture completed = INPUT_STATE.OPERATION_POINT_GESTURES.get(player.getUUID());
      OperationSession session = OperationManager.session(player).orElse(null);
      if (completed == null || !completed.finished() || completed.consumed() || completed.gestureId() != payload.gestureId()
         || completed.revision() != payload.revision() || !completed.callbackScope().equals(payload.callbackScope())
         || completed.pointIndex() != payload.pointIndex() || completed.session() != session
         || completed.completedRevision() != PlayerPreviewSync.operationRevision(player)
         || !payload.callbackScope().equals(PlayerPreviewSync.callbackScope(player))) return;
      if (payload.action() == OperationPointClickPayload.Action.CLOSE) {
         INPUT_STATE.OPERATION_POINT_GESTURES.put(player.getUUID(), completed.markConsumed());
         OperationManager.closePrismBase(player);
      } else {
         INPUT_STATE.OPERATION_POINT_GESTURES.put(player.getUUID(), completed.markConsumed());
         OperationManager.removePoint(player, payload.pointIndex());
      }
   }

   public static void operationInsertPoint(ServerPlayer player, OperationInsertPointPayload payload) {
      if (payload == null || payload.revision() != PlayerPreviewSync.operationRevision(player)
         || !io.github.fastformer.network.RaySnapshotValidation.near(payload.eye(), player.getEyePosition(), player.blockInteractionRange())
         || !payload.callbackScope().equals(PlayerPreviewSync.callbackScope(player))
         || interactionBlocked(player) || !canOperate(player) || !OperationManager.active(player)) {
         return;
      }
      OperationSession session = OperationManager.session(player).orElse(null);
      if (session == null || session.selectionMode() != OperationSelectionMode.PRISM) {
         return;
      }
      if (!acceptsOperationCommand(player, payload.requestId())) return;
      LongRangeBlockRaycast.Result raycast = LongRangeBlockRaycast.clip(
         player.level(), player, payload.eye(), payload.view()
      );
      SelectionPrism.EdgeInsertion insertion = SelectionPrism.resolveEdgeInsertion(
         session.points(),
         session.prismBasePointCount(),
         payload.eye(),
         payload.view(),
         raycast.distance()
      );
      if (insertion != null) {
         OperationManager.insertPoint(player, insertion.insertionIndex(), insertion.point());
      }
   }

   public static boolean geometryGizmoDrag(ServerPlayer player, GeometryGizmoDragPayload payload) {
      if (payload == null || interactionBlocked(player) || !canOperate(player)) {
         return false;
      }
      GeometrySession session = GeometryManager.session(player).orElse(null);
      if (session == null || !session.draftId().equals(payload.draftId())
         || !payload.callbackScope().equals(PlayerPreviewSync.callbackScope(player))
         || !payload.finish() && nearNormalBlockReach(player)) {
         return false;
      }
      AxisGizmo.Operation operation = gizmoOperation(payload.operation());
      AxisGizmo.Axis axis = gizmoAxis(payload.axis());
      if (operation == null || axis == null) return false;
      GeometryPointerSequence sequence = acceptGeometryPointer(player, payload.requestId(), payload.revision(),
         payload.callbackScope(), session, INPUT_STATE.LAST_GEOMETRY_DRAG_ACTION);
      if (sequence == null) return false;
      try {
         return GeometryManager.gizmoDrag(player, operation, axis, clampDragSteps(payload.steps()), payload.finish());
      } finally {
         advanceGeometryPointer(player, session, sequence);
      }
   }

   /** Routes a captured geometry interaction through the authoritative preview sequence. */
   public static boolean geometryInteraction(ServerPlayer player, GeometryInteractionPayload payload) {
      if (payload == null || interactionBlocked(player) || !canOperate(player)
         || !acceptsGeometryInteractionSnapshot(payload, PlayerPreviewSync.callbackScope(player), player.getEyePosition(),
            player.blockInteractionRange())
         || nearNormalBlockReach(player, payload.eye(), payload.view())) {
         return false;
      }
      GeometrySession session = GeometryManager.session(player).orElse(null);
      GeometryPointerSequence sequence = acceptGeometryPointer(player, payload.requestId(), payload.revision(),
         payload.callbackScope(), session);
      if (sequence == null) {
         return false;
      }
      try {
         if (isCapturedDoubleClickClose(payload)) {
            return GeometryManager.closePath(player);
         }
         return GeometryManager.interaction(player, payload.targetType(), payload.index(), payload.action(), payload.gesture(),
            payload.eye(), payload.view());
      } finally {
         advanceGeometryPointer(player, session, sequence);
      }
   }

   private static boolean acceptsOperationCommand(ServerPlayer player, long requestId) {
      return acceptOperationCommand(player.getUUID(), requestId);
   }

   static boolean acceptOperationCommand(UUID owner, long requestId) {
      return INPUT_STATE.LAST_OPERATION_POINT_COMMAND.accept(owner, requestId);
   }

   private static boolean isCapturedDoubleClickClose(GeometryInteractionPayload payload) {
      return payload.targetType() == GeometryInteractionTarget.TargetType.CLOSE_PATH
         && payload.index() == 0
         && payload.action() == GeometryInteractionAction.CLOSE_PATH
         && payload.gesture() == PointerGesture.RIGHT_DOUBLE_CLICK;
   }

   public static void operationPoint(ServerPlayer player, OperationPointPayload.Role role) {
      if (interactionBlocked(player) || !canOperate(player)) {
         return;
      }
      LongRangeBlockRaycast.Result raycast = LongRangeBlockRaycast.clip(
         player.level(), player, player.getEyePosition(), player.getViewVector(1.0F)
      );
      BlockHitResult hit = raycast.hit();
      OperationSession session = OperationManager.session(player).orElse(null);
      boolean prismPointInput = session != null && session.selectionMode() == OperationSelectionMode.PRISM;
      boolean prismHeight = prismPointInput
         && session.prismBaseClosed()
         && session.points().size() == session.prismBasePointCount();
      boolean prismBasePlane = prismPointInput
         && !session.prismBaseClosed()
         && session.points().size() >= 3;
      if (!prismHeight && !prismBasePlane && (hit.getType() != HitResult.Type.BLOCK
         || withinNormalBlockReach(player, hit) && !FastPlaceManager.modifierHeld(player))) {
         return;
      }
      BlockPos point = prismHeight
         ? SelectionPrism.resolveHeightPoint(
            session.points().subList(0, session.prismBasePointCount()),
            player.getEyePosition(),
            player.getViewVector(1.0F),
            raycast.distance()
         )
         : prismBasePlane
         ? SelectionPrism.resolveBasePlanePoint(
            session.points(),
            player.getEyePosition(),
            player.getViewVector(1.0F),
            raycast.distance()
         )
         : hit.getBlockPos();
      if (point == null) {
         return;
      }
      if (prismPointInput) {
         if (!session.hasFirst()) {
            OperationManager.startFirst(player, point);
         } else if (!session.hasSecond()) {
            OperationManager.setSecond(player, point);
         } else if (session.needsSelectionPoint()) {
            OperationManager.addSelectionPoint(player, point);
         }
         return;
      }
      if (role == OperationPointPayload.Role.FIRST) {
         OperationManager.startFirst(player, point);
      } else if (role == OperationPointPayload.Role.SECOND && !OperationManager.active(player)) {
         OperationManager.startSecond(player, point);
      } else if (!OperationManager.active(player)) {
         return;
      } else if (role == OperationPointPayload.Role.SECOND) {
         OperationManager.setSecond(player, point);
      } else if (OperationManager.needsSelectionPoint(player)) {
         OperationManager.addSelectionPoint(player, point);
      } else if (role == OperationPointPayload.Role.EXTRA
         && session != null && session.selectionMode() == OperationSelectionMode.CUBOID) {
         OperationManager.expandTo(player, point);
      } else if (session != null && session.second() != null) {
         OperationManager.addExtraPoint(player, point);
      }
   }

   public static void closeActivePath(ServerPlayer player, io.github.fastformer.network.payload.geometry.ClosePathPayload payload) {
      if (interactionBlocked(player) || !canOperate(player)) {
         return;
      }
      if (payload == null || !payload.scope().equals(PlayerPreviewSync.callbackScope(player))) return;
      long revision = switch (payload.kind()) {
         case OPERATION -> OperationManager.active(player) ? PlayerPreviewSync.operationRevision(player) : -1;
         case GEOMETRY -> GeometryManager.active(player) ? PlayerPreviewSync.geometryRevision(player) : -1;
         case BUILDING -> FastPlaceManager.active(player) ? PlayerPreviewSync.buildingRevision(player) : -1;
      };
      if (revision != payload.revision() || !acceptsOperationCommand(player, payload.requestId())) return;
      if (payload.kind() != io.github.fastformer.network.payload.geometry.ClosePathPayload.Kind.OPERATION && nearNormalBlockReach(player)) return;
      switch (payload.kind()) {
         case OPERATION -> OperationManager.closePrismBase(player);
         case GEOMETRY -> GeometryManager.closePath(player);
         case BUILDING -> FastPlaceManager.closePolygon(player);
      }
   }

   public static boolean selectGeometryMode(ServerPlayer player, GeometryMode mode) {
      if (interactionBlocked(player) || !canOperate(player) || FastPlaceManager.active(player) || OperationManager.active(player)) {
         return false;
      }
      if (!GeometryManager.active(player) || GeometryManager.awaitingFirstPoint(player)) {
         GeometryManager.selectMode(player, mode);
         return true;
      }
      return false;
   }

   public static void setModifierHeld(ServerPlayer player, boolean held) {
      if (interactionBlocked(player)) {
         return;
      }
      boolean allowed = canOperate(player)
         && (!GeometryManager.active(player) || GeometryManager.allows(player, GeometryAction.SUBMODE));
      FastPlaceManager.setModifierHeld(player, allowed && held);
   }

   public static void shortModifier(ServerPlayer player) {
      shortModifier(player, null, false);
   }

   public static void shortModifier(ServerPlayer player, BlockPos lineCandidate, boolean hasLineCandidate) {
      if (interactionBlocked(player) || !canOperate(player)) {
         return;
      }
      cycleModeAction(player, lineCandidate, hasLineCandidate);
   }

   public static boolean commandCycleMode(ServerPlayer player) {
      if (interactionBlocked(player) || !canOperate(player)) {
         return false;
      }
      return cycleModeAction(player, null, false);
   }

   private static boolean cycleModeAction(ServerPlayer player, BlockPos lineCandidate, boolean hasLineCandidate) {
      if (OperationManager.active(player)) {
         OperationManager.cycleMode(player);
         return true;
      } else if (GeometryManager.active(player)) {
         return GeometryManager.cycleMode(player);
      } else if (FastPlaceManager.active(player)) {
         FastPlaceManager.cycleStageMode(player, hasLineCandidate ? trustedLineCandidate(player, lineCandidate) : null);
         return true;
      }
      OperationManager.cycleMode(player);
      return true;
   }

   public static void scroll(ServerPlayer player, int steps) {
      if (interactionBlocked(player) || !canOperate(player) || steps == 0) {
         return;
      }
      if (nearNormalBlockReach(player)) {
         return;
      }
      adjustAction(player, Math.clamp(steps, -MAX_DRAG_STEPS_PER_PACKET, MAX_DRAG_STEPS_PER_PACKET));
   }

   public static boolean commandAdjust(ServerPlayer player, int steps) {
      if (interactionBlocked(player) || !canOperate(player) || steps == 0 || !hasActiveSession(player)) {
         return false;
      }
      if (GeometryManager.active(player) && !GeometryManager.allows(player, GeometryAction.SCALAR_ADJUST)) {
         return false;
      }
      if (OperationManager.active(player) && !OperationManager.selectionReady(player)) {
         return false;
      }
      adjustAction(player, Math.clamp(steps, -MAX_DRAG_STEPS_PER_PACKET, MAX_DRAG_STEPS_PER_PACKET));
      return true;
   }

   private static void adjustAction(ServerPlayer player, int scrollSteps) {
      if (OperationManager.active(player)) {
         OperationManager.scroll(player, scrollSteps);
      } else if (GeometryManager.active(player)) {
         GeometryManager.scroll(player, scrollSteps);
      } else {
         FastPlaceManager.scrollContext(player, scrollSteps);
      }
   }

   public static boolean fill(ServerPlayer player) {
      if (interactionBlocked(player) || !canOperate(player)) {
         return false;
      }
      BlockHitResult hit = raycastBlocks(player, EXTENDED_REACH);
      if (withinNormalBlockReach(player, hit)) {
         return false;
      }
      if (PlaceableItems.isPlaceable(player.getMainHandItem()) && !GeometryManager.active(player) && !OperationManager.active(player) && !FastPlaceManager.active(player)) {
         if (hit.getType() == HitResult.Type.BLOCK) {
            if (!FastPlaceManager.fillBoundedPlane(player, hit.getBlockPos().relative(hit.getDirection()), hit.getDirection().getAxis())) {
               FastPlaceManager.cycleFillMode(player);
            }
         } else {
            FastPlaceManager.cycleFillMode(player);
         }
      } else {
         FastPlaceManager.cycleFillMode(player);
      }
      return true;
   }

   public static void confirm(ServerPlayer player) {
      if (interactionBlocked(player) || !canOperate(player) || nearNormalBlockReach(player)) {
         return;
      }
      confirmAction(player);
   }

   /** Routes a semantic placement action through the same server-side guards. */
   public static void placementAction(ServerPlayer player, PlacementActionPayload payload) {
      if (payload == null || interactionBlocked(player) || !canOperate(player)) {
         return;
      }
      if (!acceptPlacementAction(player.getUUID(), payload.requestId())) {
         return;
      }
      switch (payload.action()) {
         case CONFIRM -> confirm(player);
         case QUICK_SHAPE -> quickShape(player);
      }
   }

   static boolean acceptPlacementAction(UUID owner, long requestId) {
      return INPUT_STATE.LAST_PLACEMENT_ACTION.accept(owner, requestId);
   }

   public static void confirmQuickShape(
      ServerPlayer player, QuickShapeConfirmPayload payload
   ) {
      if (payload == null || interactionBlocked(player) || !canOperate(player)
         || !acceptPlacementAction(player.getUUID(), payload.requestId())) return;
      if (!FastPlaceManager.active(player) || GeometryManager.active(player) || OperationManager.active(player)
         || !PlayerPreviewSync.buildingSubmissionParametersMatch(player)
         || !payload.matches(PlayerPreviewSync.buildingRevision(player), PlayerPreviewSync.callbackScope(player))) {
         FastPlaceMessages.actionBar(player, FastPlaceMessages.text("fastformer.message.placement_confirm_failed"));
         return;
      }
      if (!nearNormalBlockReach(player)) FastPlaceManager.fill(player);
   }


   public static void quickShapePointer(ServerPlayer player,
      io.github.fastformer.network.payload.placement.QuickShapePointerPayload payload) {
      if (payload == null || interactionBlocked(player) || !canOperate(player)
         || !FastPlaceManager.active(player) || GeometryManager.active(player) || OperationManager.active(player)
         || !PlaceableItems.isPlaceable(player.getMainHandItem())) return;
      var draft = FastPlaceManager.session(player).orElseThrow();
      var scope = PlayerPreviewSync.callbackScope(player);
      long revision = PlayerPreviewSync.buildingRevision(player);
      var sequence = INPUT_STATE.POINTER_SEQUENCES.get(player.getUUID());
      if (!payload.scope().equals(scope)) return;
      if (payload.revision() == revision) {
         sequence = new io.github.fastformer.fastplace.quickshape.QuickShapePointerSequence(draft, scope, revision,
            FastPlaceManager.candidateContext(player));
      } else if (sequence == null || !sequence.accepts(draft, scope, payload.revision(), revision)) {
         return;
      }
      if (!acceptPlacementAction(player.getUUID(), payload.requestId())) return;
      if (payload.revision() == revision) INPUT_STATE.POINTER_SEQUENCES.put(player.getUUID(), sequence);
      try {
         applyQuickShapePointer(player, payload, sequence.candidateContext());
      } finally {
         if (FastPlaceManager.session(player).orElse(null) == draft) {
            sequence.advance(PlayerPreviewSync.buildingRevision(player));
         } else {
            INPUT_STATE.POINTER_SEQUENCES.remove(player.getUUID());
         }
      }
   }

   private static void applyQuickShapePointer(ServerPlayer player,
      io.github.fastformer.network.payload.placement.QuickShapePointerPayload payload,
      io.github.fastformer.fastplace.quickshape.QuickShapeCandidateContext candidateContext) {
      if (payload.action() == io.github.fastformer.network.payload.placement.QuickShapePointerPayload.Action.UNDO) {
         FastPlaceManager.undo(player);
         return;
      }
      // Bound the captured ray origin to the player's interaction range before tracing the world.
      if (payload.eye().distanceToSqr(player.getEyePosition()) > player.blockInteractionRange() * player.blockInteractionRange()) return;
      BlockHitResult hit = LongRangeBlockRaycast.clipForPlacement(player.level(), player, payload.eye(), payload.view()).hit();
      if (hit.getType() == HitResult.Type.BLOCK && withinNormalBlockReach(player, hit.getLocation())) return;
      switch (payload.action()) {
         case CLOSE -> FastPlaceManager.closePolygon(player);
         case UNDO -> throw new IllegalStateException("Undo was already dispatched");
         case POINT -> FastPlaceManager.confirmCapturedPoint(player, payload, hit, candidateContext);
         case MIDDLE -> {
            QuickShapeDraft draft = FastPlaceManager.session(player).orElse(null);
            if (draft == null || !FastPlaceSettings.load(player).middleConfirmEnabled()
               || io.github.fastformer.fastplace.quickshape.QuickShapeInputRules.ignoresMiddleClick(
                  FastPlaceSettings.load(player).faceMode(), draft.points().size(), draft.polygonClosed())) return;
            if (FastPlaceManager.confirmCapturedPoint(player, payload, hit, candidateContext) && FastPlaceManager.active(player)) FastPlaceManager.fill(player);
         }
      }
   }

   public static void clearPlacementActions(UUID owner) {
      INPUT_STATE.clear(owner);
   }

   public static void clearAllPlacementActions() {
      INPUT_STATE.clear();
   }

   public static void quickShape(ServerPlayer player) {
      QuickShapeDraft session = FastPlaceManager.session(player).orElse(null);
      if (session != null && io.github.fastformer.fastplace.quickshape.QuickShapeInputRules.ignoresMiddleClick(
         FastPlaceSettings.load(player).faceMode(), session.points().size(), session.polygonClosed()
      )) {
         return;
      }
      rightClickItem(player);
      confirm(player);
   }

   public static void applyOperation(ServerPlayer player, boolean copy, long requestId) {
      if (interactionBlocked(player) || !canOperate(player) || nearNormalBlockReach(player)
         || !acceptPlacementAction(player.getUUID(), requestId)) {
         return;
      }
      OperationManager.applyConfirmed(player, copy);
   }

   /**
    * Admits one workspace submission and reports what happened.
    *
    * <p>A refused admission and a replayed transfer are different answers. The caller must
    * not flatten them into a failure, because a replay of applied work would then record a
    * failure for work that the world already holds.</p>
    *
    * <p>The ledger is examined before the gates. A gate refuses new work, and a replay is
    * not new work: it reports a result that already exists.</p>
    */
   public static io.github.fastformer.workspace.submission.WorkspaceAdmission applyWorkspace(
      ServerPlayer player, UUID transferId, OperationWorkspacePlan plan
   ) {
      io.github.fastformer.network.payload.operation.OperationSubmissionOutcome recorded =
         OperationManager.recordedOutcome(player, transferId);
      if (recorded != io.github.fastformer.network.payload.operation.OperationSubmissionOutcome.UNKNOWN) {
         return io.github.fastformer.workspace.submission.WorkspaceAdmission.replayed(recorded);
      }
      if (interactionBlocked(player) || !canOperate(player) || nearNormalBlockReach(player)) {
         return io.github.fastformer.workspace.submission.WorkspaceAdmission.rejected();
      }
      return OperationManager.applyWorkspace(player, transferId, plan);
   }

   public static void operationTransform(
      ServerPlayer player, io.github.fastformer.network.payload.operation.OperationTransformPayload payload
   ) {
      if (payload == null || !payload.valid() || !payload.scope().equals(PlayerPreviewSync.callbackScope(player))
         || interactionBlocked(player) || !canOperate(player)) {
         return;
      }
      var session = OperationManager.session(player).orElse(null);
      if (session == null) return;
      UUID owner = player.getUUID();
      var active = INPUT_STATE.OPERATION_TRANSFORMS.get(owner);
      if (active == null || payload.gestureId() > active.gestureId()) {
         if (payload.revision() != PlayerPreviewSync.operationRevision(player)) return;
      } else if (active.finished() || active.session() != session || active.gestureId() != payload.gestureId()
         || active.revision() != payload.revision() || active.operation() != payload.operation()
         || active.axis() != payload.axis() || active.direction() != payload.direction()) return;
      if (!INPUT_STATE.LAST_OPERATION_TRANSFORM.accept(owner, payload.requestId())) return;
      if (active != null && active.session() == session && payload.gestureId() > active.gestureId()) session.finishTransform();
      INPUT_STATE.OPERATION_TRANSFORMS.put(owner, new OperationTransformIdentity(session, payload.gestureId(), payload.revision(),
         payload.operation(), payload.axis(), payload.direction(), payload.finish()));
      int operation = payload.operation(), axis = payload.axis(), direction = payload.direction(), totalSteps = payload.totalSteps();
      boolean finish = payload.finish();
      AxisGizmo.Operation[] operations = AxisGizmo.Operation.values();
      AxisGizmo.Axis[] axes = AxisGizmo.Axis.values();
      if (operation < 0 || operation >= operations.length || axis < 0 || axis >= axes.length) {
         return;
      }
      OperationManager.adjustTransform(player, operations[operation], axes[axis], direction, totalSteps, finish);
   }

   public static boolean commandConfirm(ServerPlayer player) {
      if (interactionBlocked(player) || !canOperate(player) || !hasActiveSession(player)) {
         return false;
      }
      return confirmAction(player);
   }

   private static boolean confirmAction(ServerPlayer player) {
      if (GeometryManager.active(player)) {
         return GeometryManager.fill(player);
      } else if (OperationManager.active(player)) {
         return OperationManager.applyConfirmed(player, false);
      } else if (FastPlaceManager.active(player)) {
         FastPlaceManager.fill(player);
         return true;
      }
      return false;
   }

   public static void undo(ServerPlayer player) {
      requestWorldUndo(player, 1, true);
   }

   /** Ctrl+Z path: session rollback is allowed at any distance; only when no
    * session is active does it consume a world-history batch. */
   public static void worldUndo(ServerPlayer player) {
      requestWorldUndo(player, 1, true);
   }

   /** Command path for committed world history. It may interrupt an active
    * writer, but does not consume an edit-session step. */
   public static boolean commandWorldUndo(ServerPlayer player, int count) {
      return requestWorldUndo(player, count, false);
   }

   private static boolean requestWorldUndo(ServerPlayer player, int count, boolean allowSessionRollback) {
      if (canOperate(player) && WorldHistoryManager.snapshotRetryAvailable(player)) {
         return WorldHistoryManager.requestUndo(player, count);
      }
      UndoRoute route = undoRoute(
         canOperate(player),
         WorldHistoryManager.busy(player),
         allowSessionRollback && hasActiveSession(player),
         FastPlaceManager.taskActive(player),
         OperationManager.taskActive(player)
      );
      return switch (route) {
         case BLOCKED -> false;
         case SESSION -> rollbackActiveSession(player);
         case FAST_PLACE_TASK -> finishTaskCancellation(player, FastPlaceManager.cancelTask(player), count);
         case OPERATION_TASK -> finishTaskCancellation(player, OperationManager.cancelTask(player), count);
         case HISTORY -> WorldHistoryManager.requestUndo(player, count);
      };
   }

   private static boolean finishTaskCancellation(
      ServerPlayer player,
      TaskCancellationResult result,
      int requestedCount
   ) {
      if (!result.handled()) {
         return false;
      }
      if (result == TaskCancellationResult.CANCELLED_BEFORE_WRITE) {
         FastPlaceMessages.actionBar(player, FastPlaceMessages.text("fastformer.message.task_cancelled_before_write"));
      } else if (result == TaskCancellationResult.RECOVERY_BLOCKED) {
         FastPlaceMessages.actionBar(player, FastPlaceMessages.text("fastformer.message.task_recovery_blocked"));
      }
      int remaining = remainingUndoAfterTaskCancellation(requestedCount);
      if (remaining > 0 && (result == TaskCancellationResult.CANCELLED_BEFORE_WRITE
         || result == TaskCancellationResult.ROLLBACK_STARTED)) {
         WorldHistoryManager.deferUndoAfterRecovery(player, remaining);
         FastPlaceMessages.chat(player, FastPlaceMessages.text("fastformer.message.task_cancelled_batch_deferred"));
      }
      return true;
   }

   static int remainingUndoAfterTaskCancellation(int requestedCount) {
      return WorldHistoryManager.remainingAfterUncommittedUndo(requestedCount);
   }

   static UndoRoute undoRoute(
      boolean canOperate,
      boolean historyBusy,
      boolean sessionActive,
      boolean fastPlaceTaskActive,
      boolean operationTaskActive
   ) {
      if (!canOperate || historyBusy) {
         return UndoRoute.BLOCKED;
      }
      if (sessionActive) {
         return UndoRoute.SESSION;
      }
      if (fastPlaceTaskActive) {
         return UndoRoute.FAST_PLACE_TASK;
      }
      if (operationTaskActive) {
         return UndoRoute.OPERATION_TASK;
      }
      return UndoRoute.HISTORY;
   }

   enum UndoRoute {
      BLOCKED,
      SESSION,
      FAST_PLACE_TASK,
      OPERATION_TASK,
      HISTORY
   }

   public static void redo(ServerPlayer player) {
      if (canOperate(player) && WorldHistoryManager.snapshotRetryAvailable(player)) {
         WorldHistoryManager.requestRedo(player, 1);
         return;
      }
      if (!canOperate(player) || WorldHistoryManager.busy(player)) {
         return;
      }
      if (OperationManager.active(player)) {
         OperationManager.redo(player);
      } else if (!hasActiveSession(player)) {
         WorldHistoryManager.requestRedo(player, 1);
      }
   }

   public static boolean commandBack(ServerPlayer player) {
      return !WorldHistoryManager.busy(player) && canOperate(player) && rollbackActiveSession(player);
   }

   public static boolean commandSubmode(ServerPlayer player, boolean active) {
      if (interactionBlocked(player) || !canOperate(player) || !hasActiveSession(player)) {
         return false;
      }
      setModifierHeld(player, active);
      return !GeometryManager.active(player) || GeometryManager.allows(player, GeometryAction.SUBMODE);
   }

   public static void quit(ServerPlayer player) {
      if (FastPlaceManager.active(player)
         || OperationManager.active(player)
         || GeometryManager.active(player)
         || FastPlaceManager.taskActive(player)
         || OperationManager.taskActive(player)
         || FastPlaceManager.restoreActive(player)
         || OperationManager.restoreActive(player)) {
         FastPlaceManager.quit(player);
      }
   }

   private static boolean hasActiveSession(ServerPlayer player) {
      return GeometryManager.active(player) || OperationManager.active(player) || FastPlaceManager.active(player);
   }

   private static boolean rollbackActiveSession(ServerPlayer player) {
      if (OperationManager.active(player)) {
         OperationManager.undo(player);
         return true;
      }
      if (GeometryManager.active(player)) {
         return rollbackSession(player, GeometryManager.session(player).orElse(null), () -> GeometryManager.sync(player), () -> GeometryManager.cancel(player));
      }
      if (FastPlaceManager.active(player)) {
         var draft = FastPlaceManager.session(player).orElse(null);
         if (draft == null || !draft.canUndoStep()) return false;
         FastPlaceManager.undo(player);
         return true;
      }
      return false;
   }

   private static boolean rollbackSession(ServerPlayer player, GeometrySession session, Runnable sync, Runnable cancel) {
      if (session == null || !session.canUndoStep()) {
         return false;
      }
      boolean stillActive = session.undoStep();
      if (stillActive && session.canUndoStep()) {
         sync.run();
      } else {
         cancel.run();
      }
      return true;
   }

   public static BlockHitResult raycastBlocks(ServerPlayer player, double range) {
      Vec3 start = player.getEyePosition();
      LongRangeBlockRaycast.Result result = FastPlaceManager.classify(player) == InteractionState.BUILDING
         ? LongRangeBlockRaycast.clipForPlacement(player.level(), player, start, player.getViewVector(1.0F))
         : LongRangeBlockRaycast.clip(player.level(), player, start, player.getViewVector(1.0F));
      BlockHitResult hit = result.hit();
      if (hit.getType() != HitResult.Type.BLOCK || isWithinRaycastRange(start, hit.getLocation(), range)) {
         return hit;
      }
      Vec3 direction = player.getViewVector(1.0F).normalize();
      Vec3 end = start.add(direction.scale(range));
      return BlockHitResult.miss(end, net.minecraft.core.Direction.UP, BlockPos.containing(end));
   }

   static boolean isWithinRaycastRange(Vec3 start, Vec3 hit, double range) {
      return start != null && hit != null && Double.isFinite(range) && range >= 0.0
         && hit.distanceToSqr(start) <= range * range;
   }

   public static double visibleExtendedReach(ServerPlayer player) {
      return visibleExtendedReach(player, player.getEyePosition(), player.getViewVector(1.0F));
   }

   public static double visibleExtendedReach(ServerPlayer player, Vec3 eye, Vec3 view) {
      if (player == null) {
         return 0.0;
      }
      LongRangeBlockRaycast.Result raycast = LongRangeBlockRaycast.clip(player.level(), player, eye, view);
      return raycast.hit().getType() == HitResult.Type.BLOCK
         ? GeometryRayVisibility.visibleReach(raycast.distance(), eye, raycast.hit())
         : raycast.distance();
   }

   private static boolean nearNormalBlockReach(ServerPlayer player) {
      return withinNormalBlockReach(player, raycastBlocks(player, EXTENDED_REACH));
   }

   private static boolean nearNormalBlockReach(ServerPlayer player, Vec3 eye, Vec3 view) {
      BlockHitResult hit = LongRangeBlockRaycast.clip(player.level(), player, eye, view).hit();
      return hit.getType() == HitResult.Type.BLOCK && withinNormalBlockReach(player, eye, hit.getLocation());
   }

   private static boolean withinNormalBlockReach(ServerPlayer player, BlockHitResult hit) {
      return hit.getType() == HitResult.Type.BLOCK && withinNormalBlockReach(player, hit.getLocation());
   }

   private static boolean withinNormalBlockReach(ServerPlayer player, Vec3 point) {
      return withinNormalBlockReach(player, player.getEyePosition(), point);
   }

   private static boolean withinNormalBlockReach(ServerPlayer player, Vec3 eye, Vec3 point) {
      double reach = player.blockInteractionRange();
      return point.distanceToSqr(eye) <= reach * reach;
   }

   private static boolean withinNormalBlockReach(ServerPlayer player, AABB box) {
      double reach = player.blockInteractionRange();
      return box.distanceToSqr(player.getEyePosition()) <= reach * reach;
   }

   private static AxisGizmo.Operation gizmoOperation(int operation) {
      return switch (operation) {
         case 0 -> AxisGizmo.Operation.MOVE;
         case 1 -> AxisGizmo.Operation.SCALE;
         case 2 -> AxisGizmo.Operation.ROTATE;
         default -> null;
      };
   }

   public static boolean geometryPoint(ServerPlayer player, GeometryPointPayload payload) {
      if (payload == null || interactionBlocked(player) || !canOperate(player) || !GeometryManager.active(player)
         || !acceptsGeometryPointSnapshot(payload, PlayerPreviewSync.callbackScope(player),
            player.getEyePosition(), player.blockInteractionRange())) {
         return false;
      }
      GeometrySession session = GeometryManager.session(player).orElse(null);
      GeometryPointerSequence sequence = acceptGeometryPointer(player, payload.requestId(), payload.revision(),
         payload.callbackScope(), session);
      if (sequence == null) {
         return false;
      }
      try {
         BlockHitResult hit = LongRangeBlockRaycast.clip(player.level(), player, payload.eye(), payload.view()).hit();
         return rightClickGeometry(player, hit, payload.eye(), payload.view());
      } finally {
         advanceGeometryPointer(player, session, sequence);
      }
   }

   /** Applies one captured short-left-click undo to the same geometry draft. */
   public static boolean geometryUndo(ServerPlayer player, GeometryUndoPayload payload) {
      if (payload == null || interactionBlocked(player) || !canOperate(player) || !GeometryManager.active(player)
         || !acceptsGeometryUndoSnapshot(payload, PlayerPreviewSync.callbackScope(player), player.getEyePosition(),
            player.blockInteractionRange())) {
         return false;
      }
      GeometrySession session = GeometryManager.session(player).orElse(null);
      if (session == null || !session.draftId().equals(payload.draftId())
         || nearNormalBlockReach(player, payload.eye(), payload.view())) {
         return false;
      }
      GeometryPointerSequence sequence = acceptGeometryPointer(player, payload.requestId(), payload.revision(),
         payload.callbackScope(), session);
      if (sequence == null) {
         return false;
      }
      try {
         return rollbackActiveSession(player);
      } finally {
         advanceGeometryPointer(player, session, sequence);
      }
   }

   static boolean acceptsGeometryPointSnapshot(
      GeometryPointPayload payload, io.github.fastformer.network.payload.operation.OperationCallbackScope callbackScope,
      Vec3 currentEye, double interactionRange
   ) {
      return payload != null && payload.callbackScope().equals(callbackScope)
         && currentEye != null && Double.isFinite(interactionRange) && interactionRange >= 0.0
         && payload.eye().distanceToSqr(currentEye) <= interactionRange * interactionRange;
   }

   static boolean acceptsGeometryInteractionSnapshot(
      GeometryInteractionPayload payload, io.github.fastformer.network.payload.operation.OperationCallbackScope callbackScope,
      Vec3 currentEye, double interactionRange
   ) {
      return payload != null && payload.callbackScope().equals(callbackScope)
         && currentEye != null && Double.isFinite(interactionRange) && interactionRange >= 0.0
         && payload.eye().distanceToSqr(currentEye) <= interactionRange * interactionRange;
   }

   static boolean acceptsGeometryUndoSnapshot(
      GeometryUndoPayload payload, io.github.fastformer.network.payload.operation.OperationCallbackScope callbackScope,
      Vec3 currentEye, double interactionRange
   ) {
      return payload != null && payload.callbackScope().equals(callbackScope)
         && currentEye != null && Double.isFinite(interactionRange) && interactionRange >= 0.0
         && payload.eye().distanceToSqr(currentEye) <= interactionRange * interactionRange;
   }

   private static GeometryPointerSequence acceptGeometryPointer(ServerPlayer player, long requestId, long revision,
      io.github.fastformer.network.payload.operation.OperationCallbackScope scope, GeometrySession session) {
      return acceptGeometryPointer(player, requestId, revision, scope, session, INPUT_STATE.LAST_GEOMETRY_ACTION);
   }

   private static GeometryPointerSequence acceptGeometryPointer(ServerPlayer player, long requestId, long revision,
      io.github.fastformer.network.payload.operation.OperationCallbackScope scope, GeometrySession session,
      InputRequestSequence actionLedger) {
      UUID owner = player.getUUID();
      if (session == null || !scope.equals(PlayerPreviewSync.callbackScope(player))) {
         return null;
      }
      long currentRevision = PlayerPreviewSync.geometryRevision(player);
      GeometryPointerSequence sequence = INPUT_STATE.GEOMETRY_POINTER_SEQUENCES.get(owner);
      if (revision == currentRevision) {
         sequence = new GeometryPointerSequence(session, scope, revision);
      } else if (sequence == null || !sequence.accepts(session, scope, revision, currentRevision)) {
         return null;
      }
      if (!actionLedger.accept(owner, requestId)) return null;
      if (revision == currentRevision) INPUT_STATE.GEOMETRY_POINTER_SEQUENCES.put(owner, sequence);
      return sequence;
   }

   static boolean acceptGeometryAction(UUID owner, long requestId) {
      return INPUT_STATE.LAST_GEOMETRY_ACTION.accept(owner, requestId);
   }

   private static void advanceGeometryPointer(ServerPlayer player, GeometrySession session, GeometryPointerSequence sequence) {
      if (GeometryManager.session(player).orElse(null) == session) {
         sequence.advance(PlayerPreviewSync.geometryRevision(player));
      } else {
         INPUT_STATE.GEOMETRY_POINTER_SEQUENCES.remove(player.getUUID());
      }
   }

   private static boolean rightClickGeometry(ServerPlayer player, BlockHitResult hit) {
      return rightClickGeometry(player, hit, player.getEyePosition(), player.getViewVector(1.0F));
   }

   private static boolean rightClickGeometry(ServerPlayer player, BlockHitResult hit, Vec3 eye, Vec3 view) {
      if (GeometryManager.confirmsOnRightClick(player)) {
         return capturedGeometryConfirmAllowed(hit, eye, player.blockInteractionRange()) && GeometryManager.fill(player);
      }
      if (hit == null || hit.getType() != HitResult.Type.BLOCK || withinNormalBlockReach(player, eye, hit.getLocation())) {
         return false;
      }
      GeometryHit geometryHit = GeometryHit.from(hit);
      if (GeometryManager.active(player)) {
         GeometryManager.addPoint(player, geometryHit, eye, view);
      } else {
         GeometryManager.start(player, geometryHit);
      }
      return true;
   }

   static boolean capturedGeometryConfirmAllowed(BlockHitResult hit, Vec3 eye, double interactionRange) {
      if (hit == null) {
         return false;
      }
      if (hit.getType() != HitResult.Type.BLOCK) {
         return true;
      }
      return eye != null && Double.isFinite(interactionRange) && interactionRange >= 0.0
         && hit.getLocation().distanceToSqr(eye) > interactionRange * interactionRange;
   }

   private static AxisGizmo.Axis gizmoAxis(int axis) {
      return switch (axis) {
         case 0 -> AxisGizmo.Axis.X;
         case 1 -> AxisGizmo.Axis.Y;
         case 2 -> AxisGizmo.Axis.Z;
         default -> null;
      };
   }

   private static int clampDragSteps(int steps) {
      return Math.clamp(steps, -MAX_DRAG_STEPS_PER_PACKET, MAX_DRAG_STEPS_PER_PACKET);
   }

   private static BlockPos trustedLineCandidate(ServerPlayer player, BlockPos candidate) {
      if (candidate == null) {
         return null;
      }
      QuickShapeDraft session = FastPlaceManager.session(player).orElse(null);
      if (session == null || QuickShapeStage.resolve(session.points().size(),
         FastPlaceSettings.load(player).faceMode(), session.polygonClosed()) != QuickShapeStage.LINE) {
         return null;
      }
      BlockPos first = session.points().getFirst();
      int dx = candidate.getX() - first.getX();
      int dy = candidate.getY() - first.getY();
      int dz = candidate.getZ() - first.getZ();
      if (Math.abs(dx) > MAX_INHERITED_LINE_OFFSET || Math.abs(dy) > MAX_INHERITED_LINE_OFFSET || Math.abs(dz) > MAX_INHERITED_LINE_OFFSET) {
         return null;
      }
      double reach = EXTENDED_REACH;
      if ((double)dx * (double)dx + (double)dy * (double)dy + (double)dz * (double)dz > reach * reach) {
         return null;
      }
      return candidate.immutable();
   }

}
