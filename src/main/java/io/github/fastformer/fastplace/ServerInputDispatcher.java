package io.github.fastformer.fastplace;

import io.github.fastformer.fastplace.geometry.AxisGizmo;
import io.github.fastformer.fastplace.geometry.GeometryAction;
import io.github.fastformer.fastplace.geometry.GeometryInteractionAction;
import io.github.fastformer.fastplace.geometry.GeometryInteractionTarget;
import io.github.fastformer.fastplace.geometry.GeometryRayVisibility;
import io.github.fastformer.fastplace.geometry.PointerGesture;
import io.github.fastformer.fastplace.geometry.SelectionPrism;
import io.github.fastformer.network.OperationPointPayload;
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

   private ServerInputDispatcher() {
   }

   public static boolean canOperate(ServerPlayer player) {
      return player.isCreative()
         && FastPlaceSettings.load(player).enabled()
         && PersistentRecoveryJournal.writesAllowed();
   }

   public static void stopBecauseUnavailable(ServerPlayer player) {
      boolean taskWasActive = FastPlaceManager.taskActive(player) || OperationManager.taskActive(player);
      FastPlaceManager.cancel(player);
      FastPlaceManager.cancelTask(player);
      OperationManager.cancelTask(player);
      if (taskWasActive && !PersistentRecoveryJournal.writesAllowed()) {
         FastPlaceMessages.actionBar(player, FastPlaceMessages.text("fastformer.message.recovery_journal_blocked"));
      }
   }

   public static boolean rightClickBlock(ServerPlayer player, BlockHitResult hit) {
      if (WorldHistoryManager.busy(player) || !canOperate(player) || withinNormalBlockReach(player, hit.getLocation())) {
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
      if (WorldHistoryManager.busy(player) || !canOperate(player)) {
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
               FastPlaceSession session = FastPlaceManager.session(player).orElse(null);
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

   public static void startPlacement(ServerPlayer player, boolean embedded) {
      if (WorldHistoryManager.busy(player) || !canOperate(player)
         || !PlaceableItems.isPlaceable(player.getMainHandItem())
         || FastPlaceManager.active(player)
         || OperationManager.active(player)
         || GeometryManager.active(player)) {
         return;
      }
      BlockHitResult hit = raycastBlocks(player, EXTENDED_REACH);
      if (hit.getType() == HitResult.Type.BLOCK) {
         boolean previousModifier = FastPlaceManager.modifierHeld(player);
         if (embedded) {
            FastPlaceManager.setModifierHeld(player, true);
         }
         try {
            FastPlaceManager.addPoint(player, hit);
         } finally {
            if (embedded) {
               FastPlaceManager.setModifierHeld(player, previousModifier);
            }
         }
      }
   }

   public static boolean leftClickBlock(ServerPlayer player, BlockPos point) {
      if (WorldHistoryManager.busy(player) || !canOperate(player)) {
         return false;
      }
      if (withinNormalBlockReach(player, new AABB(point))) {
         return false;
      }
      return hasActiveSession(player);
   }

   public static void middleClick(ServerPlayer player, BlockPos point) {
      if (!WorldHistoryManager.busy(player) && canOperate(player) && OperationManager.active(player) && !withinNormalBlockReach(player, new AABB(point))) {
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
      if (WorldHistoryManager.busy(player) || !canOperate(player) || !OperationManager.active(player)) {
         return;
      }
      if (!finish && nearNormalBlockReach(player) && !FastPlaceManager.modifierHeld(player)) {
         return;
      }
      OperationManager.extend(player, axis, positive, clampDragSteps(steps), finish);
   }

   public static void operationSelectPoint(ServerPlayer player, int index) {
      if (!WorldHistoryManager.busy(player)
         && canOperate(player)
         && OperationManager.active(player)
         && (!nearNormalBlockReach(player) || FastPlaceManager.modifierHeld(player))) {
         OperationManager.selectPoint(player, index);
      }
   }

   public static void operationRemovePoint(ServerPlayer player, int index) {
      if (!WorldHistoryManager.busy(player) && canOperate(player) && OperationManager.active(player)) {
         OperationManager.removePoint(player, index);
      }
   }

   public static void operationPointDrag(
      ServerPlayer player,
      int index,
      BlockPos target,
      OperationPointDragConstraint constraint,
      boolean finish
   ) {
      if (WorldHistoryManager.busy(player) || !canOperate(player) || !OperationManager.active(player)) {
         return;
      }
      OperationManager.dragPoint(player, index, target, constraint, finish);
   }

   public static void operationInsertPoint(ServerPlayer player) {
      if (WorldHistoryManager.busy(player) || !canOperate(player) || !OperationManager.active(player)) {
         return;
      }
      OperationSession session = OperationManager.session(player).orElse(null);
      if (session == null || session.selectionMode() != OperationSelectionMode.PRISM) {
         return;
      }
      LongRangeBlockRaycast.Result raycast = LongRangeBlockRaycast.clip(
         player.level(), player, player.getEyePosition(), player.getViewVector(1.0F)
      );
      SelectionPrism.EdgeInsertion insertion = SelectionPrism.resolveEdgeInsertion(
         session.points(),
         session.prismBasePointCount(),
         player.getEyePosition(),
         player.getViewVector(1.0F),
         raycast.distance()
      );
      if (insertion != null) {
         OperationManager.insertPoint(player, insertion.insertionIndex(), insertion.point());
      }
   }

   public static void geometryGizmoDrag(ServerPlayer player, int operation, int axis, int steps, boolean finish) {
      if (WorldHistoryManager.busy(player) || !canOperate(player) || !GeometryManager.active(player)) {
         return;
      }
      if (!finish && nearNormalBlockReach(player)) {
         return;
      }
      AxisGizmo.Operation gizmoOperation = gizmoOperation(operation);
      AxisGizmo.Axis gizmoAxis = gizmoAxis(axis);
      if (gizmoOperation != null && gizmoAxis != null) {
         GeometryManager.gizmoDrag(player, gizmoOperation, gizmoAxis, clampDragSteps(steps), finish);
      }
   }

   public static void geometryInteraction(
      ServerPlayer player,
      GeometryInteractionTarget.TargetType targetType,
      int index,
      GeometryInteractionAction action,
      PointerGesture gesture
   ) {
      if (WorldHistoryManager.busy(player) || !canOperate(player) || nearNormalBlockReach(player)) {
         return;
      }
      GeometryManager.interaction(player, targetType, index, action, gesture);
   }

   public static void operationPoint(ServerPlayer player, OperationPointPayload.Role role) {
      if (WorldHistoryManager.busy(player) || !canOperate(player)) {
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

   public static void legacyGeometryRemovePoint(ServerPlayer player, BlockPos point) {
      if (!WorldHistoryManager.busy(player) && canOperate(player) && GeometryManager.active(player) && !withinNormalBlockReach(player, new AABB(point))) {
         rollbackActiveSession(player);
      }
   }

   public static void closeActivePath(ServerPlayer player) {
      if (WorldHistoryManager.busy(player) || !canOperate(player)) {
         return;
      }
      if (OperationManager.active(player)) {
         OperationManager.closePrismBase(player);
      } else if (nearNormalBlockReach(player)) {
         return;
      } else if (GeometryManager.active(player)) {
         GeometryManager.closePath(player);
      } else if (FastPlaceManager.active(player)) {
         FastPlaceManager.closePolygon(player);
      }
   }

   public static boolean selectGeometryMode(ServerPlayer player, GeometryMode mode) {
      if (WorldHistoryManager.busy(player) || !canOperate(player) || FastPlaceManager.active(player) || OperationManager.active(player)) {
         return false;
      }
      if (!GeometryManager.active(player) || GeometryManager.awaitingFirstPoint(player)) {
         GeometryManager.selectMode(player, mode);
         return true;
      }
      return false;
   }

   public static void setModifierHeld(ServerPlayer player, boolean held) {
      if (WorldHistoryManager.busy(player)) {
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
      if (WorldHistoryManager.busy(player) || !canOperate(player)) {
         return;
      }
      cycleModeAction(player, lineCandidate, hasLineCandidate);
   }

   public static boolean commandCycleMode(ServerPlayer player) {
      if (WorldHistoryManager.busy(player) || !canOperate(player) || !hasActiveSession(player)) {
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
      return false;
   }

   public static void scroll(ServerPlayer player, int steps) {
      if (WorldHistoryManager.busy(player) || !canOperate(player) || steps == 0) {
         return;
      }
      if (nearNormalBlockReach(player)) {
         return;
      }
      adjustAction(player, Math.clamp(steps, -MAX_DRAG_STEPS_PER_PACKET, MAX_DRAG_STEPS_PER_PACKET));
   }

   public static boolean commandAdjust(ServerPlayer player, int steps) {
      if (WorldHistoryManager.busy(player) || !canOperate(player) || steps == 0 || !hasActiveSession(player)) {
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
      if (WorldHistoryManager.busy(player) || !canOperate(player)) {
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
      if (WorldHistoryManager.busy(player) || !canOperate(player) || nearNormalBlockReach(player)) {
         return;
      }
      confirmAction(player);
   }

   public static void quickShape(ServerPlayer player) {
      rightClickItem(player);
      confirm(player);
   }

   public static void applyOperation(ServerPlayer player, boolean copy) {
      if (WorldHistoryManager.busy(player) || !canOperate(player) || nearNormalBlockReach(player)) {
         return;
      }
      OperationManager.applyConfirmed(player, copy);
   }

   public static boolean applyWorkspace(ServerPlayer player, OperationWorkspacePlan plan) {
      if (WorldHistoryManager.busy(player) || !canOperate(player) || nearNormalBlockReach(player)) {
         return false;
      }
      return OperationManager.applyWorkspace(player, plan);
   }

   public static void operationTransform(
      ServerPlayer player, int operation, int axis, int direction, int totalSteps, boolean finish
   ) {
      if (WorldHistoryManager.busy(player) || !canOperate(player)) {
         return;
      }
      AxisGizmo.Operation[] operations = AxisGizmo.Operation.values();
      AxisGizmo.Axis[] axes = AxisGizmo.Axis.values();
      if (operation < 0 || operation >= operations.length || axis < 0 || axis >= axes.length) {
         return;
      }
      OperationManager.adjustTransform(player, operations[operation], axes[axis], direction, totalSteps, finish);
   }

   public static boolean commandConfirm(ServerPlayer player) {
      if (WorldHistoryManager.busy(player) || !canOperate(player) || !hasActiveSession(player)) {
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
      if (canOperate(player) && !WorldHistoryManager.busy(player) && hasActiveSession(player) && !nearNormalBlockReach(player)) {
         rollbackActiveSession(player);
      }
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
      if (WorldHistoryManager.busy(player) || !canOperate(player) || !hasActiveSession(player)) {
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
         return rollbackSession(player, FastPlaceManager.session(player).orElse(null), () -> FastPlaceManager.sync(player), () -> FastPlaceManager.cancel(player));
      }
      return false;
   }

   private static boolean rollbackSession(ServerPlayer player, SessionLifecycle session, Runnable sync, Runnable cancel) {
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
      return LongRangeBlockRaycast.clip(player.level(), player, start, player.getViewVector(1.0F)).hit();
   }

   public static double visibleExtendedReach(ServerPlayer player) {
      if (player == null) {
         return 0.0;
      }
      LongRangeBlockRaycast.Result raycast = LongRangeBlockRaycast.clip(
         player.level(), player, player.getEyePosition(), player.getViewVector(1.0F)
      );
      return raycast.hit().getType() == HitResult.Type.BLOCK
         ? GeometryRayVisibility.visibleReach(raycast.distance(), player.getEyePosition(), raycast.hit())
         : raycast.distance();
   }

   private static boolean nearNormalBlockReach(ServerPlayer player) {
      return withinNormalBlockReach(player, raycastBlocks(player, EXTENDED_REACH));
   }

   private static boolean withinNormalBlockReach(ServerPlayer player, BlockHitResult hit) {
      return hit.getType() == HitResult.Type.BLOCK && withinNormalBlockReach(player, hit.getLocation());
   }

   private static boolean withinNormalBlockReach(ServerPlayer player, Vec3 point) {
      double reach = player.blockInteractionRange();
      return point.distanceToSqr(player.getEyePosition()) <= reach * reach;
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

   public static boolean geometryPoint(ServerPlayer player) {
      if (WorldHistoryManager.busy(player) || !canOperate(player) || !GeometryManager.active(player)) {
         return false;
      }
      return rightClickGeometry(player, raycastBlocks(player, EXTENDED_REACH));
   }

   private static boolean rightClickGeometry(ServerPlayer player, BlockHitResult hit) {
      if (GeometryManager.confirmsOnRightClick(player)) {
         return !nearNormalBlockReach(player) && GeometryManager.fill(player);
      }
      if (hit == null || hit.getType() != HitResult.Type.BLOCK || withinNormalBlockReach(player, hit.getLocation())) {
         return false;
      }
      GeometryHit geometryHit = GeometryHit.from(hit);
      if (GeometryManager.active(player)) {
         GeometryManager.addPoint(player, geometryHit);
      } else {
         GeometryManager.start(player, geometryHit);
      }
      return true;
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
      FastPlaceSession session = FastPlaceManager.session(player).orElse(null);
      if (session == null || session.stage() != FastPlaceStage.LINE || session.points().isEmpty()) {
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
