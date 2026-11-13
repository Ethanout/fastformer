package io.github.fastformer.server.input;

import io.github.fastformer.core.command.IntentSource;
import io.github.fastformer.server.intent.PlayerIntentSystem;
import io.github.fastformer.server.intent.SessionIntents;
import io.github.fastformer.server.intent.HistoryIntents;
import io.github.fastformer.server.history.HistorySystem;

import io.github.fastformer.server.gate.ServerOperationGates;

import io.github.fastformer.fastplace.geometry.AxisGizmo;
import io.github.fastformer.fastplace.geometry.GeometryAction;
import io.github.fastformer.fastplace.geometry.GeometryHit;
import io.github.fastformer.fastplace.geometry.GeometryMode;
import io.github.fastformer.fastplace.geometry.GeometryPointerSequence;
import io.github.fastformer.fastplace.geometry.GeometryRayVisibility;
import io.github.fastformer.fastplace.geometry.SelectionPrism;
import io.github.fastformer.fastplace.geometry.interaction.GeometryInteractionAction;
import io.github.fastformer.fastplace.geometry.interaction.GeometryInteractionTarget;
import io.github.fastformer.fastplace.geometry.interaction.PointerGesture;
import io.github.fastformer.fastplace.geometry.raycast.LongRangeBlockRaycast;
import io.github.fastformer.fastplace.interaction.SpecialItemHandlers;
import io.github.fastformer.fastplace.placement.context.PlaceableItems;
import io.github.fastformer.fastplace.quickshape.QuickShapeDraft;
import io.github.fastformer.fastplace.quickshape.QuickShapeStage;
import io.github.fastformer.fastplace.quickshape.RaycastPlacement;
import io.github.fastformer.server.recovery.PersistentRecoveryJournal;
import io.github.fastformer.fastplace.selection.OperationSelectionMode;
import io.github.fastformer.fastplace.session.*;
import io.github.fastformer.fastplace.session.InteractionState;
import io.github.fastformer.fastplace.settings.FastPlaceSettings;
import io.github.fastformer.fastplace.text.FastPlaceMessages;
import io.github.fastformer.fastplace.world.*;
import io.github.fastformer.protocol.c2s.geometry.GeometryGizmoDragPayload;
import io.github.fastformer.protocol.c2s.geometry.GeometryInteractionPayload;
import io.github.fastformer.protocol.c2s.geometry.GeometryPointPayload;
import io.github.fastformer.protocol.c2s.geometry.GeometryUndoPayload;
import io.github.fastformer.protocol.c2s.operation.OperationExtendPayload;
import io.github.fastformer.protocol.c2s.operation.OperationInsertPointPayload;
import io.github.fastformer.protocol.c2s.operation.OperationPointClickPayload;
import io.github.fastformer.protocol.c2s.operation.OperationPointDragPayload;
import io.github.fastformer.protocol.c2s.operation.OperationPointPayload;
import io.github.fastformer.protocol.c2s.operation.OperationSelectPointPayload;
import io.github.fastformer.protocol.c2s.placement.PlacementActionPayload;
import io.github.fastformer.protocol.c2s.placement.QuickShapeConfirmPayload;
import io.github.fastformer.protocol.c2s.placement.StartPlacementPayload;
import io.github.fastformer.server.sync.PreviewSync;
import io.github.fastformer.server.input.ServerInputState.OperationExtendIdentity;
import io.github.fastformer.server.input.ServerInputState.OperationPointGesture;
import io.github.fastformer.server.input.ServerInputState.OperationTransformIdentity;
import io.github.fastformer.server.session.QuickShapeSystem;
import io.github.fastformer.server.session.GeometrySystem;
import io.github.fastformer.server.session.OperationSystem;
import io.github.fastformer.workspace.submission.OperationWorkspacePlan;
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

   public static void endOperationGestures(ServerPlayer player) {
      ServerInputState.of(player).extendGesture = null;
      ServerInputState.of(player).transformGesture = null;
   }

   private ServerInputDispatcher() {
   }

   public static boolean canOperate(ServerPlayer player) {
      return ServerOperationGates.AVAILABLE.test(player).allowed();
   }

   /** A world task owns all editing input until it releases its resources. */
   public static boolean interactionBlocked(ServerPlayer player) {
      return !ServerOperationGates.CONTINUE_INTERACTION.test(player).allowed();
   }

   public static void stopBecauseUnavailable(ServerPlayer player) {
      io.github.fastformer.server.fill.SmartFillSystem.cancel(player);
      boolean taskWasActive = QuickShapeSystem.of(player).taskActive(player) || OperationSystem.of(player).taskActive(player);
      QuickShapeSystem.of(player).cancel(player);
      QuickShapeSystem.of(player).cancelTask(player);
      OperationSystem.of(player).cancelTask(player);
      HistorySystem.of(player).cancel(player);
      if (taskWasActive && !PersistentRecoveryJournal.writesAllowed()) {
         FastPlaceMessages.actionBar(player, FastPlaceMessages.text("fastformer.message.recovery_journal_blocked"));
      }
   }

   public static boolean rightClickBlock(ServerPlayer player, BlockHitResult hit) {
      if (interactionBlocked(player) || !canOperate(player) || IdleInteractionSnapshots.idle(player)) {
         return false;
      }

      return switch (QuickShapeSystem.of(player).classify(player)) {
         // Selection points belong to explicit selection commands, not vanilla item callbacks.
         case OPERATION, VANILLA -> false;
         case GEOMETRY -> rightClickGeometry(player, hit);
         case SPECIAL_ITEM -> SpecialItemHandlers.useOnBlock(player, hit.getBlockPos());
         case BUILDING -> {
            if (!PlaceableItems.isPlaceable(player.getMainHandItem())) {
               QuickShapeSystem.of(player).quit(player);
               yield false;
            }
            QuickShapeSystem.of(player).addPoint(player, hit);
            yield true;
         }
      };
   }

   public static boolean rightClickItem(ServerPlayer player) {
      if (interactionBlocked(player) || !canOperate(player) || IdleInteractionSnapshots.idle(player)) {
         return false;
      }

      BlockHitResult hit = raycastBlocks(player, EXTENDED_REACH);
      return switch (QuickShapeSystem.of(player).classify(player)) {
         case OPERATION -> false;
         case BUILDING -> {
            if (hit.getType() == HitResult.Type.BLOCK) {
               QuickShapeSystem.of(player).addPoint(player, hit);
            } else {
               if (!QuickShapeSystem.of(player).active(player)) {
                  yield false;
               }
               QuickShapeDraft session = QuickShapeSystem.of(player).session(player).orElse(null);
               BlockPos offset = session != null ? session.freeScrollOffset() : BlockPos.ZERO;
               BlockPos first = session != null && !session.points().isEmpty() ? session.points().getFirst() : BlockPos.ZERO;
               BlockPos anchor = first.offset(offset);
               QuickShapeSystem.of(player).addPoint(player, anchor, anchor);
            }
            yield true;
         }
         case GEOMETRY -> rightClickGeometry(player, hit);
         case SPECIAL_ITEM, VANILLA -> false;
      };
   }

   public static void startPlacement(ServerPlayer player, StartPlacementPayload payload) {
      if (payload == null || interactionBlocked(player) || !canOperate(player)
         || !acceptPlacementAction(player, payload.requestId())) {
         return;
      }
      if (!PlaceableItems.isPlaceable(player.getMainHandItem())
         || QuickShapeSystem.of(player).active(player)
         || OperationSystem.of(player).active(player)
         || GeometrySystem.of(player).active(player)) {
         return;
      }
      StartPlacementPayload.Target target = payload.target();
      var profile = io.github.fastformer.common.interaction.binding.SessionProfiles.find(player.registryAccess(), target.profile()).orElse(null);
      if (profile == null) return;
      if (target.revision() != PreviewSync.buildingRevision(player)
         || !target.callbackScope().equals(PreviewSync.callbackScope(player))
         || !IdleInteractionSnapshots.validRay(player, target.eye(), target.view())) {
         return;
      }
      var entry = target.anchorToken() > 0
         ? IdleInteractionSnapshots.consumeEntry(player, target.anchorToken(), target.eye(), target.view()) : null;
      BlockHitResult hit = target.anchorToken() > 0
         ? entry == null ? null : entry.hit()
         : io.github.fastformer.fastplace.geometry.raycast.SessionEntryRaycast.clip(player, target.eye(), target.view(),
            new io.github.fastformer.common.raycast.InteractionRaycastRules(target.embedded(), target.throughFluids()));
      if (hit != null && hit.getType() == HitResult.Type.BLOCK
         && (target.anchorToken() > 0 || sameHit(hit, target.hit()))) {
         if (!io.github.fastformer.server.interaction.profile.ProfileEntryRules.accepts(player, profile, target, hit)) return;
         if (entry != null && entry.placement() != null) {
            QuickShapeSystem.of(player).beginAtInteraction(player, entry.placement(), target.eye(), target.view());
         } else {
            boolean embedded = entry == null ? target.embedded() : entry.rules().embedded();
            var placement = io.github.fastformer.fastplace.placement.context.PlacementContextSnapshot.capture(
               player.level(), player, player.getMainHandItem(), hit, embedded);
            QuickShapeSystem.of(player).beginAtInteraction(player, placement, target.eye(), target.view(), target.profile(), profile);
         }
         if (target.submitAfterEntry() && QuickShapeSystem.of(player).active(player)) QuickShapeSystem.of(player).fillConfirmed(player);
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
      return hasActiveSession(player);
   }

   public static void middleClick(ServerPlayer player, BlockPos point) {
      if (!interactionBlocked(player) && canOperate(player) && OperationSystem.of(player).active(player)) {
         OperationSystem.of(player).session(player).ifPresent(session -> {
            if (session.selectionMode() == OperationSelectionMode.CUBOID) {
               OperationSystem.of(player).expandTo(player, point);
            } else {
               OperationSystem.of(player).addExtraPoint(player, point);
            }
         });
      }
   }

   public static void extend(ServerPlayer player, int axis, boolean positive, int steps, boolean finish) {
      if (interactionBlocked(player) || !canOperate(player) || !OperationSystem.of(player).active(player)) {
         return;
      }
      OperationSystem.of(player).extend(player, axis, positive, clampDragSteps(steps), finish);
   }

   public static void operationSelectPoint(ServerPlayer player, OperationSelectPointPayload payload) {
      if (payload == null || payload.revision() != PreviewSync.operationRevision(player)
         || !payload.callbackScope().equals(PreviewSync.callbackScope(player))
         || interactionBlocked(player) || !canOperate(player) || !OperationSystem.of(player).active(player)
         || !acceptsOperationCommand(player, payload.requestId())) return;
      OperationSystem.of(player).selectPoint(player, payload.index());
   }

   public static void operationPointDrag(ServerPlayer player, OperationPointDragPayload payload) {
      if (interactionBlocked(player) || !canOperate(player) || !OperationSystem.of(player).active(player)) {
         return;
      }
      OperationPointGesture gesture = acceptsOperationPointDrag(player, payload);
      if (gesture == null) return;
      try {
         OperationSystem.of(player).dragPoint(player, payload.pointIndex(), payload.target(), payload.constraint(), payload.finish());
      } finally {
         if (payload.finish()) {
            ServerInputState.of(player).pointGesture = gesture.finished(PreviewSync.operationRevision(player));
         }
      }
   }

   public static void extend(ServerPlayer player, OperationExtendPayload payload) {
      if (payload == null || !payload.callbackScope().equals(PreviewSync.callbackScope(player))
         || interactionBlocked(player) || !canOperate(player)) return;
      OperationSession session = OperationSystem.of(player).session(player).orElse(null);
      if (session == null) {
         ServerInputState.of(player).extendGesture = null;
         return;
      }
      OperationExtendIdentity active = ServerInputState.of(player).extendGesture;
      if (active == null || active.session() != session || payload.requestId() > active.requestId()) {
         if (payload.revision() != PreviewSync.operationRevision(player)
            || !ServerInputState.of(player).extendActions.accept(payload.requestId())) return;
         if (active != null && active.session() == session) session.commitEdit();
         ServerInputState.of(player).extendGesture = new OperationExtendIdentity(session, payload.requestId(), payload.revision(), payload.callbackScope(), payload.axis(), payload.positive());
      } else if (active.session() != session || active.requestId() != payload.requestId() || active.revision() != payload.revision()
         || !active.scope().equals(payload.callbackScope()) || active.axis() != payload.axis() || active.positive() != payload.positive()) {
         return;
      }
      extend(player, payload.axis(), payload.positive(), payload.steps(), payload.finish());
      if (payload.finish()) ServerInputState.of(player).extendGesture = null;
   }

   private static OperationPointGesture acceptsOperationPointDrag(ServerPlayer player, OperationPointDragPayload payload) {
      if (payload == null || !payload.callbackScope().equals(PreviewSync.callbackScope(player))) return null;
      OperationSession session = OperationSystem.of(player).session(player).orElse(null);
      if (session == null) return null;
      OperationPointGesture previous = ServerInputState.of(player).pointGesture;
      long currentRevision = PreviewSync.operationRevision(player);
      if (previous == null || payload.gestureId() > previous.gestureId()) {
         if (payload.revision() != currentRevision) return null;
         var started = new OperationPointGesture(session, payload.gestureId(), payload.revision(), payload.callbackScope(),
            payload.pointIndex(), false, -1L, false);
         ServerInputState.of(player).pointGesture = started;
         return started;
      }
      if (previous.gestureId() != payload.gestureId() || previous.finished() || previous.consumed()
         || previous.session() != session || previous.revision() != payload.revision()
         || previous.pointIndex() != payload.pointIndex() || !previous.callbackScope().equals(payload.callbackScope())) return null;
      return previous;
   }

   public static void operationPointClick(ServerPlayer player, OperationPointClickPayload payload) {
      if (payload == null || interactionBlocked(player) || !canOperate(player) || !OperationSystem.of(player).active(player)) return;
      OperationPointGesture completed = ServerInputState.of(player).pointGesture;
      OperationSession session = OperationSystem.of(player).session(player).orElse(null);
      if (completed == null || !completed.finished() || completed.consumed() || completed.gestureId() != payload.gestureId()
         || completed.revision() != payload.revision() || !completed.callbackScope().equals(payload.callbackScope())
         || completed.pointIndex() != payload.pointIndex() || completed.session() != session
         || completed.completedRevision() != PreviewSync.operationRevision(player)
         || !payload.callbackScope().equals(PreviewSync.callbackScope(player))) return;
      if (payload.action() == OperationPointClickPayload.Action.CLOSE) {
         ServerInputState.of(player).pointGesture = completed.markConsumed();
         OperationSystem.of(player).closePrismBase(player);
      } else {
         ServerInputState.of(player).pointGesture = completed.markConsumed();
         OperationSystem.of(player).removePoint(player, payload.pointIndex());
      }
   }

   public static void operationInsertPoint(ServerPlayer player, OperationInsertPointPayload payload) {
      if (payload == null || payload.revision() != PreviewSync.operationRevision(player)
         || !io.github.fastformer.common.raycast.RaySnapshotValidation.near(payload.eye(), player.getEyePosition(), player.blockInteractionRange())
         || !payload.callbackScope().equals(PreviewSync.callbackScope(player))
         || interactionBlocked(player) || !canOperate(player) || !OperationSystem.of(player).active(player)) {
         return;
      }
      OperationSession session = OperationSystem.of(player).session(player).orElse(null);
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
         OperationSystem.of(player).insertPoint(player, insertion.insertionIndex(), insertion.point());
      }
   }

   public static boolean geometryGizmoDrag(ServerPlayer player, GeometryGizmoDragPayload payload) {
      if (payload == null || interactionBlocked(player) || !canOperate(player)) {
         return false;
      }
      GeometrySession session = GeometrySystem.of(player).session(player).orElse(null);
      if (session == null || !session.draftId().equals(payload.draftId())
         || !payload.callbackScope().equals(PreviewSync.callbackScope(player))) {
         return false;
      }
      AxisGizmo.Operation operation = gizmoOperation(payload.operation());
      AxisGizmo.Axis axis = gizmoAxis(payload.axis());
      if (operation == null || axis == null) return false;
      GeometryPointerSequence sequence = acceptGeometryPointer(player, payload.requestId(), payload.revision(),
         payload.callbackScope(), session, ServerInputState.of(player).geometryDragActions);
      if (sequence == null) return false;
      try {
         return GeometrySystem.of(player).gizmoDrag(player, operation, axis, clampDragSteps(payload.steps()), payload.finish());
      } finally {
         advanceGeometryPointer(player, session, sequence);
      }
   }

   /** Routes a captured geometry interaction through the authoritative preview sequence. */
   public static boolean geometryInteraction(ServerPlayer player, GeometryInteractionPayload payload) {
      if (payload == null || interactionBlocked(player) || !canOperate(player)
         || !acceptsGeometryInteractionSnapshot(payload, PreviewSync.callbackScope(player), player.getEyePosition(),
            player.blockInteractionRange())
         || IdleInteractionSnapshots.idle(player)) {
         return false;
      }
      GeometrySession session = GeometrySystem.of(player).session(player).orElse(null);
      GeometryPointerSequence sequence = acceptGeometryPointer(player, payload.requestId(), payload.revision(),
         payload.callbackScope(), session);
      if (sequence == null) {
         return false;
      }
      try {
         if (isCapturedDoubleClickClose(payload)) {
            return GeometrySystem.of(player).closePath(player);
         }
         return GeometrySystem.of(player).interaction(player, payload.targetType(), payload.index(), payload.action(), payload.gesture(),
            payload.eye(), payload.view());
      } finally {
         advanceGeometryPointer(player, session, sequence);
      }
   }

   private static boolean acceptsOperationCommand(ServerPlayer player, long requestId) {
      return ServerInputState.of(player).pointCommands.accept(requestId);
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
      LongRangeBlockRaycast.Result raycast = LongRangeBlockRaycast.clipForSelection(
         player.level(), player, player.getEyePosition(), player.getViewVector(1.0F), QuickShapeSystem.of(player).modifierHeld(player)
      );
      BlockHitResult hit = raycast.hit();
      OperationSession session = OperationSystem.of(player).session(player).orElse(null);
      boolean prismPointInput = session != null && session.selectionMode() == OperationSelectionMode.PRISM;
      boolean prismHeight = prismPointInput
         && session.prismBaseClosed()
         && session.points().size() == session.prismBasePointCount();
      boolean prismBasePlane = prismPointInput
         && !session.prismBaseClosed()
         && session.points().size() >= 3;
      if (!prismHeight && !prismBasePlane && (hit.getType() != HitResult.Type.BLOCK
         || session == null && IdleInteractionSnapshots.idle(player) && !QuickShapeSystem.of(player).modifierHeld(player))) {
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
            OperationSystem.of(player).startFirst(player, point);
         } else if (!session.hasSecond()) {
            OperationSystem.of(player).setSecond(player, point);
         } else if (session.needsSelectionPoint()) {
            OperationSystem.of(player).addSelectionPoint(player, point);
         }
         return;
      }
      if (role == OperationPointPayload.Role.FIRST) {
         OperationSystem.of(player).startFirst(player, point);
      } else if (role == OperationPointPayload.Role.SECOND && !OperationSystem.of(player).active(player)) {
         OperationSystem.of(player).startSecond(player, point);
      } else if (!OperationSystem.of(player).active(player)) {
         OperationSystem.of(player).startFirst(player, point);
      } else if (role == OperationPointPayload.Role.SECOND) {
         OperationSystem.of(player).setSecond(player, point);
      } else if (OperationSystem.of(player).needsSelectionPoint(player)) {
         OperationSystem.of(player).addSelectionPoint(player, point);
      } else if (role == OperationPointPayload.Role.EXTRA
         && session != null && session.selectionMode() == OperationSelectionMode.CUBOID) {
         OperationSystem.of(player).expandTo(player, point);
      } else if (session != null && session.second() != null) {
         OperationSystem.of(player).addExtraPoint(player, point);
      }
   }

   public static void closeActivePath(ServerPlayer player, io.github.fastformer.protocol.c2s.geometry.ClosePathPayload payload) {
      if (interactionBlocked(player) || !canOperate(player)) {
         return;
      }
      if (payload == null || !payload.scope().equals(PreviewSync.callbackScope(player))) return;
      long revision = switch (payload.kind()) {
         case OPERATION -> OperationSystem.of(player).active(player) ? PreviewSync.operationRevision(player) : -1;
         case GEOMETRY -> GeometrySystem.of(player).active(player) ? PreviewSync.geometryRevision(player) : -1;
         case BUILDING -> QuickShapeSystem.of(player).active(player) ? PreviewSync.buildingRevision(player) : -1;
      };
      if (revision != payload.revision() || !acceptsOperationCommand(player, payload.requestId())) return;
      switch (payload.kind()) {
         case OPERATION -> OperationSystem.of(player).closePrismBase(player);
         case GEOMETRY -> GeometrySystem.of(player).closePath(player);
         case BUILDING -> QuickShapeSystem.of(player).closePolygon(player);
      }
   }

   public static boolean selectGeometryMode(ServerPlayer player, GeometryMode mode) {
      if (interactionBlocked(player) || !canOperate(player) || QuickShapeSystem.of(player).active(player) || OperationSystem.of(player).active(player)) {
         return false;
      }
      if (!GeometrySystem.of(player).active(player) || GeometrySystem.of(player).awaitingFirstPoint(player)) {
         GeometrySystem.of(player).selectMode(player, mode);
         return true;
      }
      return false;
   }

   public static boolean fill(ServerPlayer player) {
      if (io.github.fastformer.server.fill.SmartFillSystem.active(player)) return true;
      if (interactionBlocked(player) || !canOperate(player)) {
         return false;
      }
      if (!QuickShapeSystem.of(player).active(player) || GeometrySystem.of(player).active(player) || OperationSystem.of(player).active(player)) return false;
      QuickShapeSystem.of(player).cycleFillMode(player);
      return true;
   }

   /** Routes a semantic placement action through the same server-side guards. */
   public static void placementAction(ServerPlayer player, PlacementActionPayload payload) {
      if (payload == null || interactionBlocked(player) || !canOperate(player)) {
         return;
      }
      if (!acceptPlacementAction(player, payload.requestId())) {
         return;
      }
      switch (payload.action()) {
         case CONFIRM -> PlayerIntentSystem.dispatch(player, IntentSource.NETWORK, payload.requestId(), new SessionIntents.Confirm());
         case QUICK_SHAPE -> quickShape(player);
      }
   }

   private static boolean acceptPlacementAction(ServerPlayer player, long requestId) {
      return ServerInputState.of(player).placementActions.accept(requestId);
   }

   public static void confirmQuickShape(
      ServerPlayer player, QuickShapeConfirmPayload payload
   ) {
      if (payload == null || interactionBlocked(player) || !canOperate(player)
         || !acceptPlacementAction(player, payload.requestId())) return;
      if (!QuickShapeSystem.of(player).active(player) || GeometrySystem.of(player).active(player) || OperationSystem.of(player).active(player)
         || !PreviewSync.buildingSubmissionParametersMatch(player)
         || !payload.matches(PreviewSync.buildingRevision(player), PreviewSync.callbackScope(player))) {
         FastPlaceMessages.actionBar(player, FastPlaceMessages.text("fastformer.message.placement_confirm_failed"));
         return;
      }
      QuickShapeSystem.of(player).fill(player);
   }

   public static void quickShapePointer(ServerPlayer player,
      io.github.fastformer.protocol.c2s.placement.QuickShapePointerPayload payload) {
      if (payload == null || interactionBlocked(player) || !canOperate(player)
         || !QuickShapeSystem.of(player).active(player) || GeometrySystem.of(player).active(player) || OperationSystem.of(player).active(player)
         || !PlaceableItems.isPlaceable(player.getMainHandItem())) return;
      var draft = QuickShapeSystem.of(player).session(player).orElseThrow();
      var scope = PreviewSync.callbackScope(player);
      long revision = PreviewSync.buildingRevision(player);
      var sequence = ServerInputState.of(player).quickShapePointer;
      if (!payload.scope().equals(scope)) return;
      if (payload.revision() == revision) {
         sequence = new io.github.fastformer.fastplace.quickshape.QuickShapePointerSequence(draft, scope, revision,
            QuickShapeSystem.of(player).candidateContext(player));
      } else if (sequence == null || !sequence.accepts(draft, scope, payload.revision(), revision)) {
         return;
      }
      if (!acceptPlacementAction(player, payload.requestId())) return;
      if (payload.revision() == revision) ServerInputState.of(player).quickShapePointer = sequence;
      try {
         applyQuickShapePointer(player, payload, sequence.candidateContext());
      } finally {
         if (QuickShapeSystem.of(player).session(player).orElse(null) == draft) {
            sequence.advance(PreviewSync.buildingRevision(player));
         } else {
            ServerInputState.of(player).quickShapePointer = null;
         }
      }
   }

   private static void applyQuickShapePointer(ServerPlayer player,
      io.github.fastformer.protocol.c2s.placement.QuickShapePointerPayload payload,
      io.github.fastformer.fastplace.quickshape.QuickShapeCandidateContext candidateContext) {
      if (payload.action() == io.github.fastformer.protocol.c2s.placement.QuickShapePointerPayload.Action.UNDO) {
         QuickShapeSystem.of(player).undo(player);
         return;
      }
      // Bound the captured ray origin to the player's interaction range before tracing the world.
      if (payload.eye().distanceToSqr(player.getEyePosition()) > player.blockInteractionRange() * player.blockInteractionRange()) return;
      BlockHitResult hit = LongRangeBlockRaycast.clipForPlacement(player.level(), player, payload.eye(), payload.view()).hit();
      switch (payload.action()) {
         case SUBMIT_CURRENT -> {
            QuickShapeDraft draft = QuickShapeSystem.of(player).session(player).orElse(null);
            if (draft != null && candidateContext.modifierHeld() == payload.modifierHeld()
               && draft.points().equals(candidateContext.points())
               && candidateContext.resolve(hit, payload.eye(), payload.view()).equals(payload.candidate())
               && io.github.fastformer.fastplace.quickshape.QuickShapeOverlap.overlaps(
                  draft.points(), payload.candidate(),
                  FastPlaceSettings.load(player).faceMode(), draft.polygonClosed())) {
               QuickShapeSystem.of(player).fillConfirmed(player);
            }
         }
         case CLOSE -> QuickShapeSystem.of(player).closePolygon(player);
         case UNDO -> throw new IllegalStateException("Undo was already dispatched");
         case POINT -> QuickShapeSystem.of(player).confirmCapturedPoint(player, payload, hit, candidateContext);
         case MIDDLE -> {
            QuickShapeDraft draft = QuickShapeSystem.of(player).session(player).orElse(null);
            if (draft == null || !io.github.fastformer.server.interaction.profile.ProfileConstraints.middleConfirm(draft, FastPlaceSettings.load(player))
               || io.github.fastformer.fastplace.quickshape.QuickShapeInputRules.ignoresMiddleClick(
                  FastPlaceSettings.load(player).faceMode(), draft.points().size(), draft.polygonClosed())) return;
            if (QuickShapeSystem.of(player).confirmCapturedPoint(player, payload, hit, candidateContext) && QuickShapeSystem.of(player).active(player)) QuickShapeSystem.of(player).fill(player);
         }
      }
   }

   public static void clearPlacementActions(ServerPlayer player) {
      ServerInputState.reset(player);
   }

   public static void quickShape(ServerPlayer player) {
      QuickShapeDraft session = QuickShapeSystem.of(player).session(player).orElse(null);
      if (session != null && io.github.fastformer.fastplace.quickshape.QuickShapeInputRules.ignoresMiddleClick(
         FastPlaceSettings.load(player).faceMode(), session.points().size(), session.polygonClosed()
      )) {
         return;
      }
      rightClickItem(player);
      PlayerIntentSystem.dispatch(player, IntentSource.INTERNAL, new SessionIntents.Confirm());
   }

   public static void applyOperation(ServerPlayer player, boolean copy, long requestId) {
      if (interactionBlocked(player) || !canOperate(player) || !OperationSystem.of(player).active(player)
         || !acceptPlacementAction(player, requestId)) {
         return;
      }
      OperationSystem.of(player).applyConfirmed(player, copy);
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
      io.github.fastformer.workspace.submission.OperationSubmissionOutcome recorded =
         OperationSystem.of(player).recordedOutcome(player, transferId);
      if (recorded != io.github.fastformer.workspace.submission.OperationSubmissionOutcome.UNKNOWN) {
         return io.github.fastformer.workspace.submission.WorkspaceAdmission.replayed(recorded);
      }
      if (interactionBlocked(player) || !canOperate(player)) {
         return io.github.fastformer.workspace.submission.WorkspaceAdmission.rejected();
      }
      return OperationSystem.of(player).applyWorkspace(player, transferId, plan);
   }

   public static void operationTransform(
      ServerPlayer player, io.github.fastformer.protocol.c2s.operation.OperationTransformPayload payload
   ) {
      if (payload == null || !payload.valid() || !payload.scope().equals(PreviewSync.callbackScope(player))
         || interactionBlocked(player) || !canOperate(player)) {
         return;
      }
      var session = OperationSystem.of(player).session(player).orElse(null);
      if (session == null) return;
      var active = ServerInputState.of(player).transformGesture;
      if (active == null || payload.gestureId() > active.gestureId()) {
         if (payload.revision() != PreviewSync.operationRevision(player)) return;
      } else if (active.finished() || active.session() != session || active.gestureId() != payload.gestureId()
         || active.revision() != payload.revision() || active.operation() != payload.operation()
         || active.axis() != payload.axis() || active.direction() != payload.direction()) return;
      if (!ServerInputState.of(player).transformActions.accept(payload.requestId())) return;
      if (active != null && active.session() == session && payload.gestureId() > active.gestureId()) session.finishTransform();
      ServerInputState.of(player).transformGesture = new OperationTransformIdentity(session, payload.gestureId(), payload.revision(),
         payload.operation(), payload.axis(), payload.direction(), payload.finish());
      int operation = payload.operation(), axis = payload.axis(), direction = payload.direction(), totalSteps = payload.totalSteps();
      boolean finish = payload.finish();
      AxisGizmo.Operation[] operations = AxisGizmo.Operation.values();
      AxisGizmo.Axis[] axes = AxisGizmo.Axis.values();
      if (operation < 0 || operation >= operations.length || axis < 0 || axis >= axes.length) {
         return;
      }
      OperationSystem.of(player).adjustTransform(player, operations[operation], axes[axis], direction, totalSteps, finish);
   }

   private static boolean hasActiveSession(ServerPlayer player) {
      return GeometrySystem.of(player).active(player) || OperationSystem.of(player).active(player) || QuickShapeSystem.of(player).active(player);
   }

   public static BlockHitResult raycastBlocks(ServerPlayer player, double range) {
      Vec3 start = player.getEyePosition();
      Vec3 view = player.getViewVector(1.0F);
      LongRangeBlockRaycast.Result result = switch (QuickShapeSystem.of(player).classify(player)) {
         case BUILDING -> LongRangeBlockRaycast.clipForPlacement(player.level(), player, start, view);
         case OPERATION -> LongRangeBlockRaycast.clipForSelection(player.level(), player, start, view,
            QuickShapeSystem.of(player).modifierHeld(player));
         default -> LongRangeBlockRaycast.clip(player.level(), player, start, view);
      };
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

   public static void tinker(ServerPlayer player, io.github.fastformer.protocol.c2s.placement.TinkerInteractionPayload payload) {
      if (!canOperate(player) || interactionBlocked(player) || !IdleInteractionSnapshots.idle(player)
         || !IdleInteractionSnapshots.validRay(player, payload.eye(), payload.view())) return;
      var hit = io.github.fastformer.fastplace.geometry.raycast.SessionEntryRaycast.clip(player, payload.eye(), payload.view(),
         io.github.fastformer.common.raycast.InteractionRaycastRules.VANILLA_OUTLINE);
      if (sameHit(hit, payload.hit())) {
         IdleInteractionSnapshots.tinker(player, payload);
      }
   }

   public static BlockPos idleSelectionEntry(ServerPlayer player, io.github.fastformer.protocol.c2s.placement.IdleSelectionEntryPayload payload) {
      if (!canOperate(player) || interactionBlocked(player) || !IdleInteractionSnapshots.idle(player)
         || !IdleInteractionSnapshots.validRay(player, payload.eye(), payload.view())
         || !acceptPlacementAction(player, payload.requestId())) return null;
      var hit = IdleInteractionSnapshots.consume(player, payload.token(), payload.eye(), payload.view());
      return hit == null ? null : hit.getBlockPos();
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
      if (payload == null || interactionBlocked(player) || !canOperate(player) || !GeometrySystem.of(player).active(player)
         || !acceptsGeometryPointSnapshot(payload, PreviewSync.callbackScope(player),
            player.getEyePosition(), player.blockInteractionRange())) {
         return false;
      }
      GeometrySession session = GeometrySystem.of(player).session(player).orElse(null);
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
      if (payload == null || interactionBlocked(player) || !canOperate(player) || !GeometrySystem.of(player).active(player)
         || !acceptsGeometryUndoSnapshot(payload, PreviewSync.callbackScope(player), player.getEyePosition(),
            player.blockInteractionRange())) {
         return false;
      }
      GeometrySession session = GeometrySystem.of(player).session(player).orElse(null);
      if (session == null || !session.draftId().equals(payload.draftId())) {
         return false;
      }
      GeometryPointerSequence sequence = acceptGeometryPointer(player, payload.requestId(), payload.revision(),
         payload.callbackScope(), session);
      if (sequence == null) {
         return false;
      }
      try {
         return PlayerIntentSystem.dispatch(player, IntentSource.NETWORK, payload.requestId(), new HistoryIntents.Back()).handled();
      } finally {
         advanceGeometryPointer(player, session, sequence);
      }
   }

   static boolean acceptsGeometryPointSnapshot(
      GeometryPointPayload payload, io.github.fastformer.protocol.callback.OperationCallbackScope callbackScope,
      Vec3 currentEye, double interactionRange
   ) {
      return payload != null && payload.callbackScope().equals(callbackScope)
         && currentEye != null && Double.isFinite(interactionRange) && interactionRange >= 0.0
         && payload.eye().distanceToSqr(currentEye) <= interactionRange * interactionRange;
   }

   static boolean acceptsGeometryInteractionSnapshot(
      GeometryInteractionPayload payload, io.github.fastformer.protocol.callback.OperationCallbackScope callbackScope,
      Vec3 currentEye, double interactionRange
   ) {
      return payload != null && payload.callbackScope().equals(callbackScope)
         && currentEye != null && Double.isFinite(interactionRange) && interactionRange >= 0.0
         && payload.eye().distanceToSqr(currentEye) <= interactionRange * interactionRange;
   }

   static boolean acceptsGeometryUndoSnapshot(
      GeometryUndoPayload payload, io.github.fastformer.protocol.callback.OperationCallbackScope callbackScope,
      Vec3 currentEye, double interactionRange
   ) {
      return payload != null && payload.callbackScope().equals(callbackScope)
         && currentEye != null && Double.isFinite(interactionRange) && interactionRange >= 0.0
         && payload.eye().distanceToSqr(currentEye) <= interactionRange * interactionRange;
   }

   private static GeometryPointerSequence acceptGeometryPointer(ServerPlayer player, long requestId, long revision,
      io.github.fastformer.protocol.callback.OperationCallbackScope scope, GeometrySession session) {
      return acceptGeometryPointer(player, requestId, revision, scope, session, ServerInputState.of(player).geometryActions);
   }

   private static GeometryPointerSequence acceptGeometryPointer(ServerPlayer player, long requestId, long revision,
      io.github.fastformer.protocol.callback.OperationCallbackScope scope, GeometrySession session,
      InputRequestSequence actionLedger) {
      if (session == null || !scope.equals(PreviewSync.callbackScope(player))) {
         return null;
      }
      long currentRevision = PreviewSync.geometryRevision(player);
      GeometryPointerSequence sequence = ServerInputState.of(player).geometryPointer;
      if (revision == currentRevision) {
         sequence = new GeometryPointerSequence(session, scope, revision);
      } else if (sequence == null || !sequence.accepts(session, scope, revision, currentRevision)) {
         return null;
      }
      if (!actionLedger.accept(requestId)) return null;
      if (revision == currentRevision) ServerInputState.of(player).geometryPointer = sequence;
      return sequence;
   }

   private static void advanceGeometryPointer(ServerPlayer player, GeometrySession session, GeometryPointerSequence sequence) {
      if (GeometrySystem.of(player).session(player).orElse(null) == session) {
         sequence.advance(PreviewSync.geometryRevision(player));
      } else {
         ServerInputState.of(player).geometryPointer = null;
      }
   }

   private static boolean rightClickGeometry(ServerPlayer player, BlockHitResult hit) {
      return rightClickGeometry(player, hit, player.getEyePosition(), player.getViewVector(1.0F));
   }

   private static boolean rightClickGeometry(ServerPlayer player, BlockHitResult hit, Vec3 eye, Vec3 view) {
      if (GeometrySystem.of(player).confirmsOnRightClick(player)) {
         return hit != null && GeometrySystem.of(player).fill(player);
      }
      if (hit == null || hit.getType() != HitResult.Type.BLOCK) {
         return false;
      }
      GeometryHit geometryHit = GeometryHit.from(hit);
      if (GeometrySystem.of(player).active(player)) {
         GeometrySystem.of(player).addPoint(player, geometryHit, eye, view);
      } else {
         GeometrySystem.of(player).start(player, geometryHit);
      }
      return true;
   }

   static boolean capturedGeometryConfirmAllowed(BlockHitResult hit, boolean vanillaOwnsInput) {
      return hit != null && !vanillaOwnsInput;
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

}
