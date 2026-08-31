package io.github.fastformer.fastplace;

import io.github.fastformer.fastplace.world.*;

import io.github.fastformer.fastplace.session.OperationSession;
import io.github.fastformer.fastplace.task.ClientWorkspacePlacementTask;
import io.github.fastformer.fastplace.task.OperationTaskResult;
import io.github.fastformer.fastplace.task.TaskCancellationResult;
import io.github.fastformer.fastplace.task.SelectionOperationTask;
import io.github.fastformer.fastplace.task.WorldOperationTask;
import com.mojang.logging.LogUtils;
import io.github.fastformer.fastplace.geometry.OperationGeometry;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import io.github.fastformer.network.FastPlaceNetwork;
import org.slf4j.Logger;

public final class OperationManager {
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final Map<UUID, OperationSession> SESSIONS = new HashMap<>();
   private static final Map<UUID, WorldOperationTask> TASKS = new HashMap<>();

   private OperationManager() {
   }

   private static OperationSession createSession(ServerPlayer player) {
      FastPlaceSettings settings = FastPlaceSettings.load(player);
      return new OperationSession(settings.operationSelectionMode(), settings.sessionUndoHistoryLimit());
   }

   public static void updateSessionHistoryLimit(ServerPlayer player, int historyLimit) {
      OperationSession session = SESSIONS.get(player.getUUID());
      if (session != null) {
         session.updateHistoryLimit(historyLimit);
      }
   }

   private static boolean recordEdit(OperationSession session, BooleanSupplier mutation) {
      session.commitEdit();
      session.beginEdit();
      boolean changed = mutation.getAsBoolean();
      session.commitEdit();
      return changed;
   }

   private static void rememberSelectionMode(ServerPlayer player, OperationSession session) {
      FastPlaceSettings settings = FastPlaceSettings.load(player);
      if (settings.operationSelectionMode() != session.selectionMode()) {
         settings.setOperationSelectionMode(player, session.selectionMode());
      }
   }

   public static Optional<OperationSession> session(ServerPlayer player) {
      return Optional.ofNullable(SESSIONS.get(player.getUUID()));
   }

   public static boolean active(ServerPlayer player) {
      return SESSIONS.containsKey(player.getUUID());
   }

   public static boolean needsSecond(ServerPlayer player) {
      OperationSession session = SESSIONS.get(player.getUUID());
      return session != null && session.first() != null && session.second() == null;
   }

   public static boolean needsSelectionPoint(ServerPlayer player) {
      OperationSession session = SESSIONS.get(player.getUUID());
      return session != null && session.needsSelectionPoint();
   }

   public static boolean selectionReady(ServerPlayer player) {
      OperationSession session = SESSIONS.get(player.getUUID());
      return session != null && session.selectionReady();
   }

   public static OperationSession start(ServerPlayer player, BlockPos point) {
      return startFirst(player, point);
   }

   public static OperationSession startFirst(ServerPlayer player, BlockPos point) {
      OperationSession session = SESSIONS.computeIfAbsent(player.getUUID(), ignored -> createSession(player));
      recordEdit(session, () -> {
         session.setFirst(point);
         return true;
      });
      FastPlaceMessages.actionBar(player, FastPlaceMessages.text("fastformer.message.operation_point1"));
      FastPlaceNetwork.syncOperation(player, session);
      return session;
   }

   public static OperationSession startSecond(ServerPlayer player, BlockPos point) {
      OperationSession session = SESSIONS.computeIfAbsent(player.getUUID(), ignored -> createSession(player));
      recordEdit(session, () -> {
         session.setSecond(point);
         return true;
      });
      FastPlaceMessages.actionBar(player, FastPlaceMessages.text("fastformer.message.operation_point2"));
      FastPlaceNetwork.syncOperation(player, session);
      return session;
   }

   public static void setSecond(ServerPlayer player, BlockPos point) {
      OperationSession session = SESSIONS.get(player.getUUID());
      if (session != null) {
         recordEdit(session, () -> {
            session.setSecond(point);
            return true;
         });
         FastPlaceMessages.actionBar(player, FastPlaceMessages.text("fastformer.message.operation_point2"));
         FastPlaceNetwork.syncOperation(player, session);
      }
   }

   public static void addExtraPoint(ServerPlayer player, BlockPos point) {
      OperationSession session = SESSIONS.get(player.getUUID());
      if (session != null && recordEdit(session, () -> session.addExtraPoint(point))) {
         FastPlaceMessages.actionBar(player, FastPlaceMessages.text("fastformer.message.operation_extra_point", session.extraPoints().size()));
         FastPlaceNetwork.syncOperation(player, session);
      }
   }

   public static void expandTo(ServerPlayer player, BlockPos point) {
      OperationSession session = SESSIONS.get(player.getUUID());
      if (session != null && recordEdit(session, () -> session.expandTo(point))) {
         FastPlaceNetwork.syncOperation(player, session);
      }
   }

   public static boolean confirmSelection(ServerPlayer player) {
      OperationSession session = SESSIONS.get(player.getUUID());
      if (session == null || !recordEdit(session, session::confirmSelection)) {
         FastPlaceMessages.actionBar(player, FastPlaceMessages.text("fastformer.message.operation_need_points"));
         return false;
      }
      FastPlaceNetwork.syncOperation(player, session);
      FastPlaceMessages.actionBar(player, FastPlaceMessages.text("fastformer.message.operation_selection_confirmed"));
      return true;
   }

   public static boolean closePrismBase(ServerPlayer player) {
      OperationSession session = SESSIONS.get(player.getUUID());
      if (session == null || !recordEdit(session, session::closePrismBase)) {
         return false;
      }
      FastPlaceNetwork.syncOperation(player, session);
      return true;
   }

   public static void selectPoint(ServerPlayer player, int index) {
      OperationSession session = SESSIONS.get(player.getUUID());
      if (session != null && session.selectPoint(index)) {
         FastPlaceNetwork.syncOperation(player, session);
      }
   }

   public static void removePoint(ServerPlayer player, int index) {
      OperationSession session = SESSIONS.get(player.getUUID());
      if (session != null && recordEdit(session, () -> session.removePoint(index))) {
         FastPlaceNetwork.syncOperation(player, session);
      }
   }

   public static void dragPoint(
      ServerPlayer player,
      int index,
      BlockPos target,
      OperationPointDragConstraint constraint,
      boolean finish
   ) {
      OperationSession session = SESSIONS.get(player.getUUID());
      if (session == null) {
         return;
      }
      session.beginEdit();
      if (session.dragPointTo(index, target, constraint, finish)) {
         FastPlaceNetwork.syncOperation(player, session);
      }
      if (finish) {
         session.commitEdit();
      }
   }

   public static void insertPoint(ServerPlayer player, int insertionIndex, BlockPos point) {
      OperationSession session = SESSIONS.get(player.getUUID());
      if (session != null && recordEdit(session, () -> session.insertPoint(insertionIndex, point))) {
         FastPlaceNetwork.syncOperation(player, session);
      }
   }

   public static void addSelectionPoint(ServerPlayer player, BlockPos point) {
      OperationSession session = SESSIONS.get(player.getUUID());
      if (session != null && recordEdit(session, () -> session.addSelectionPoint(point))) {
         FastPlaceMessages.actionBar(
            player,
            FastPlaceMessages.text("fastformer.message.operation_selection_point", session.points().size(), session.selectionMode().requiredPoints())
         );
         FastPlaceNetwork.syncOperation(player, session);
      }
   }

   public static void undo(ServerPlayer player) {
      OperationSession session = SESSIONS.get(player.getUUID());
      boolean changed = session != null && (session.adjustmentStarted()
         ? session.undoAdjustment()
         : session.undoStep());
      if (changed) {
         rememberSelectionMode(player, session);
         FastPlaceNetwork.syncOperation(player, session);
      }
   }

   public static void redo(ServerPlayer player) {
      OperationSession session = SESSIONS.get(player.getUUID());
      if (session != null && session.redoStep()) {
         rememberSelectionMode(player, session);
         FastPlaceNetwork.syncOperation(player, session);
      }
   }

   public static void sync(ServerPlayer player) {
      OperationSession session = SESSIONS.get(player.getUUID());
      if (session != null) {
         FastPlaceNetwork.syncOperation(player, session);
      } else {
         FastPlaceNetwork.syncSettings(player);
      }
   }

   public static void extend(ServerPlayer player, int axis, boolean positive, int steps, boolean finish) {
      OperationSession session = SESSIONS.get(player.getUUID());
      if (session == null) {
         return;
      }

      session.beginEdit();
      boolean changed = axis >= 6
         ? session.moveSelectedPoint(axis - 6, steps)
         : axis >= 3
         ? session.moveSelection(axis - 3, steps)
         : session.extend(axis, positive, steps);
      if (finish) {
         session.setExtend(false);
         session.commitEdit();
      }
      if (changed || finish) {
         FastPlaceNetwork.syncOperation(player, session);
      }
   }

   public static void cycleMode(ServerPlayer player) {
      OperationSession session = SESSIONS.get(player.getUUID());
      if (session != null) {
         if (session.operationReady()) {
            session.cycleStageMode();
         } else {
            recordEdit(session, () -> {
               session.cycleSelectionMode();
               return true;
            });
            rememberSelectionMode(player, session);
         }
         FastPlaceNetwork.syncOperation(player, session);
      }
   }

   public static boolean setSelectionMode(ServerPlayer player, OperationSelectionMode mode) {
      OperationSession session = SESSIONS.get(player.getUUID());
      if (session == null || mode == OperationSelectionMode.CONVEX_HULL) {
         return false;
      }
      recordEdit(session, () -> {
         session.setSelectionMode(mode);
         return true;
      });
      rememberSelectionMode(player, session);
      FastPlaceNetwork.syncOperation(player, session);
      return true;
   }

   public static boolean setConflictMode(ServerPlayer player, OperationConflictMode mode) {
      OperationSession session = SESSIONS.get(player.getUUID());
      if (session == null) {
         return false;
      }
      session.setConflictMode(mode);
      FastPlaceMessages.actionBar(player, FastPlaceMessages.text("fastformer.message.operation_conflict_mode", FastPlaceMessages.text(mode)));
      return true;
   }

   public static void scroll(ServerPlayer player, int steps) {
      OperationSession session = SESSIONS.get(player.getUUID());
      if (session == null || !session.operationReady() || steps == 0) {
         return;
      }
      BlockPos axisStep = OperationGeometry.viewAxisStep(player.getViewVector(1.0F), steps);
      int axis = axisStep.getX() != 0 ? 0 : axisStep.getY() != 0 ? 1 : 2;
      int amount = axis == 0 ? axisStep.getX() : axis == 1 ? axisStep.getY() : axisStep.getZ();
      var operation = io.github.fastformer.fastplace.geometry.AxisGizmo.Operation.MOVE;
      session.beginTransform(operation, io.github.fastformer.fastplace.geometry.AxisGizmo.Axis.values()[axis], Integer.signum(amount));
      session.updateTransform(Math.abs(amount));
      session.finishTransform();
      FastPlaceNetwork.syncOperation(player, session);
   }

   public static void adjustTransform(
      ServerPlayer player,
      io.github.fastformer.fastplace.geometry.AxisGizmo.Operation operation,
      io.github.fastformer.fastplace.geometry.AxisGizmo.Axis axis,
      int direction,
      int totalSteps,
      boolean finish
   ) {
      OperationSession session = SESSIONS.get(player.getUUID());
      if (session == null || !session.operationReady()) {
         return;
      }
      if (!session.transformActive() && !session.beginTransform(operation, axis, direction)) {
         return;
      }
      boolean changed = session.updateTransform(totalSteps);
      if (finish) {
         changed = session.finishTransform() || changed;
      }
      if (changed || finish) {
         FastPlaceNetwork.syncOperation(player, session);
      }
   }

   public static boolean applyConfirmed(ServerPlayer player, boolean copy) {
      OperationSession session = SESSIONS.get(player.getUUID());
      if (session == null || !session.operationReady() || !session.adjustmentStarted()) {
         return false;
      }
      if (!session.stageMode().executable()) {
         FastPlaceMessages.actionBar(player, FastPlaceMessages.text("fastformer.message.operation_mode_not_implemented"));
         return false;
      }
      return apply(player, copy);
   }

   public static boolean apply(ServerPlayer player, boolean copy) {
      OperationSession session = SESSIONS.get(player.getUUID());
      if (session == null || !session.selectionReady()) {
         FastPlaceMessages.actionBar(player, FastPlaceMessages.text("fastformer.message.operation_need_points"));
         return false;
      }

      OperationSelectionVolume selection = session.currentSelectionVolume();
      if (selection == null) {
         return false;
      }

      int maxPlacement = FastPlaceSettings.load(player).maxPlacement();
      PlacementUpdateMode updateMode = FastPlaceSettings.load(player).placementUpdateMode();
      AABB bounds = selection.bounds();
      long volume = scanCells(bounds);
      if (volume > maxPlacement) {
         FastPlaceMessages.actionBar(player, FastPlaceMessages.text("fastformer.message.operation_too_large", maxPlacement));
         return false;
      }
      if (!WorldOperationMemory.canPrepare(volume)) {
         FastPlaceMessages.actionBar(player, FastPlaceMessages.text("fastformer.message.operation_memory_unsafe"));
         return false;
      }
      if (operationBusy(player)) {
         FastPlaceMessages.actionBar(player, FastPlaceMessages.text("fastformer.message.operation_task_running"));
         return false;
      }
      TASKS.put(
         player.getUUID(),
         new SelectionOperationTask(
            selection,
            session.mode(),
            session.conflictMode(),
            copy,
            session.translation(),
            session.stackRegion(),
            updateMode,
            maxPlacement,
            player.serverLevel().dimension()
         )
      );
      cancel(player);
      FastPlaceMessages.actionBar(player, FastPlaceMessages.text("fastformer.message.operation_scanning"));
      return true;
   }

   public static boolean applyWorkspace(ServerPlayer player, UUID transferId, OperationWorkspacePlan plan) {
      if (transferId == null || plan == null || plan.parts().isEmpty()) {
         return false;
      }
      int maxPlacement = FastPlaceSettings.load(player).maxPlacement();
      if (operationBusy(player)) {
         FastPlaceMessages.actionBar(player, FastPlaceMessages.text("fastformer.message.operation_task_running"));
         return false;
      }
      TASKS.put(
         player.getUUID(),
         new ClientWorkspacePlacementTask(
            transferId,
            plan,
            FastPlaceSettings.load(player).placementUpdateMode(),
            maxPlacement,
            player.serverLevel().dimension()
         )
      );
      cancel(player);
      FastPlaceMessages.actionBar(player, FastPlaceMessages.text("fastformer.message.operation_scanning"));
      return true;
   }

   public static void tickWorld(net.minecraft.server.MinecraftServer server) {
      for (UUID owner : List.copyOf(TASKS.keySet())) {
         if (!PersistentRecoveryJournal.writesAllowed()) {
            break;
         }
         if (WorldHistoryManager.busy(owner) || FastPlaceManager.taskActive(owner)) {
            continue;
         }
         tickTask(new WorldTaskContext(server, owner));
      }
   }

   private static void tickTask(WorldTaskContext context) {
      UUID owner = context.owner();
      WorldOperationTask task = TASKS.get(owner);
      if (task == null) {
         return;
      }
      try {
      ServerLevel level = context.level(task.dimension());
      if (level == null) {
         context.actionBar(FastPlaceMessages.text("fastformer.message.history_dimension_failed"));
         return;
      }
      if (!task.acquireLease(context)) {
         context.actionBar(FastPlaceMessages.text("fastformer.message.world_write_waiting"));
         return;
      }
      OperationTaskResult result = task.tick(context, level, WorldTaskBudget.forServerTick());
      if (result != OperationTaskResult.ACTIVE) {
         if (result == OperationTaskResult.FAILED) {
            settleFailedTask(context, task);
         } else if (result == OperationTaskResult.JOURNAL_FAILED) {
            settleFailedTask(context, task);
         } else if (task.hasWrites()) {
            if (!WorldHistoryManager.commitPreparedOperation(context, task.preparedBatch(), task.journal())) {
               result = OperationTaskResult.FAILED;
               settleFailedTask(context, task);
            } else {
               task.releaseLease(context);
            }
         } else {
            // A no-op can still have a prepared .dat. The coordinator must
            // retain the lease until that unused journal is actually gone;
            // direct release here would let a later writer cross stale WAL.
            task.releaseAfterCancelledJournal(context);
         }
         if (task instanceof ClientWorkspacePlacementTask workspaceTask) {
            FastPlaceNetwork.sendWorkspaceResult(
               context.onlinePlayer(),
               workspaceTask.transferId(),
               result == OperationTaskResult.COMPLETE || result == OperationTaskResult.EMPTY,
               workspaceTask.failedPartIds()
            );
         }
         TASKS.remove(owner, task);
         context.actionBar(
            result == OperationTaskResult.COMPLETE
               ? FastPlaceMessages.text("fastformer.message.operation_complete")
               : result == OperationTaskResult.EMPTY
                  ? FastPlaceMessages.text("fastformer.message.operation_empty")
                  : result == OperationTaskResult.FAILED
                     ? FastPlaceMessages.text("fastformer.message.operation_failed_rollback")
                      : result == OperationTaskResult.JOURNAL_FAILED
                         ? FastPlaceMessages.text("fastformer.message.recovery_journal_failed")
                         : result == OperationTaskResult.MEMORY_UNSAFE
                            ? FastPlaceMessages.text("fastformer.message.operation_memory_unsafe")
                            : FastPlaceMessages.text("fastformer.message.operation_too_large_run")
         );
      } else {
         context.actionBar(FastPlaceMessages.text("fastformer.message.operation_phase", task.phaseName()));
      }
      } catch (RuntimeException | OutOfMemoryError exception) {
         if (TASKS.remove(owner, task)) {
            settleFailedTask(context, task);
         }
         if (task instanceof ClientWorkspacePlacementTask workspaceTask) {
            FastPlaceNetwork.sendWorkspaceResult(
               context.onlinePlayer(), workspaceTask.transferId(), false, workspaceTask.failedPartIds()
            );
         }
         LOGGER.error("FastFormer operation task failed for {} and was transferred to recovery", owner, exception);
         context.actionBar(FastPlaceMessages.text("fastformer.message.operation_failed_rollback"));
      }
   }

   private static void settleFailedTask(WorldTaskContext context, WorldOperationTask task) {
      task.cancelJournalPreparation();
      switch (WorldTaskFeature.failureDisposition(task.hasWrites())) {
         case RECOVER_WRITES -> startRestore(
            context, task.dimension(), task.undoChanges(), task.afterChanges(), task.journal()
         );
         case DISCARD_UNUSED_JOURNAL -> task.releaseAfterCancelledJournal(context);
      }
   }

   public static void cancel(ServerPlayer player) {
      OperationSession removed = SESSIONS.remove(player.getUUID());
      if (removed != null) {
         rememberSelectionMode(player, removed);
         FastPlaceNetwork.syncSettings(player);
      }
   }

   public static void remove(ServerPlayer player) {
      OperationSession removed = SESSIONS.remove(player.getUUID());
      if (removed != null) {
         rememberSelectionMode(player, removed);
      }
   }

   /** Drops server-bound operation sessions/tasks before a world instance is replaced. */
   public static void clearServer() {
      for (WorldOperationTask task : TASKS.values()) {
         task.cancelJournalPreparation();
      }
      TASKS.clear();
      SESSIONS.clear();
   }

   public static boolean cancelRestore(ServerPlayer player) {
      return WorldHistoryManager.cancel(player);
   }

   public static TaskCancellationResult cancelTask(ServerPlayer player) {
      WorldOperationTask task = TASKS.remove(player.getUUID());
      if (task == null) {
         return TaskCancellationResult.NOT_ACTIVE;
      }
      task.cancelJournalPreparation();
      ArrayDeque<ReversibleBlockSnapshot> undoChanges = task.undoChanges();
      ServerLevel taskLevel = player.getServer().getLevel(task.dimension());
      if (undoChanges.isEmpty()) {
         task.releaseAfterCancelledJournal(new WorldTaskContext(player.getServer(), player.getUUID()));
         return TaskCancellationResult.CANCELLED_BEFORE_WRITE;
      }
      boolean rollbackStarted;
      if (taskLevel != null) {
         rollbackStarted = WorldHistoryManager.startRollback(
            player, taskLevel, undoChanges, task.afterChanges(), task.journal()
         );
      } else {
         rollbackStarted = WorldHistoryManager.startRollback(
            player, task.dimension(), undoChanges, task.afterChanges(), task.journal()
         );
      }
      return rollbackStarted
         ? TaskCancellationResult.ROLLBACK_STARTED
         : TaskCancellationResult.RECOVERY_BLOCKED;
   }

   public static boolean undoLast(ServerPlayer player) {
      return WorldHistoryManager.requestUndo(player, 1);
   }

   private static void startRestore(
      ServerPlayer player,
      ArrayDeque<ReversibleBlockSnapshot> changes,
      Map<BlockPos, ReversibleBlockSnapshot> after,
      PersistentRecoveryJournal journal
   ) {
      WorldHistoryManager.startRollback(player, player.serverLevel(), changes, after, journal);
   }

   private static void startRestore(
      WorldTaskContext context,
      ResourceKey<Level> dimension,
      ArrayDeque<ReversibleBlockSnapshot> changes,
      Map<BlockPos, ReversibleBlockSnapshot> after,
      PersistentRecoveryJournal journal
   ) {
      WorldHistoryManager.startRollback(context, dimension, changes, after, journal);
   }

   private static boolean operationBusy(ServerPlayer player) {
      return localTaskBusy(player)
         || FastPlaceManager.taskBusy(player)
         || WorldHistoryManager.busy(player)
         || WorldWriteCoordinator.busy(player.getServer(), player.serverLevel().dimension());
   }

   public static boolean taskBusy(ServerPlayer player) {
      return localTaskBusy(player);
   }

   public static boolean taskActive(ServerPlayer player) {
      return taskActive(player.getUUID());
   }

   static boolean taskActive(UUID owner) {
      return TASKS.containsKey(owner);
   }

   public static boolean restoreActive(ServerPlayer player) {
      return WorldHistoryManager.restoreActive(player);
   }

   private static boolean localTaskBusy(ServerPlayer player) {
      UUID id = player.getUUID();
      return TASKS.containsKey(id);
   }

   private static long scanCells(AABB bounds) {
      long width = Math.max(0, Mth.ceil(bounds.maxX) - Mth.floor(bounds.minX));
      long height = Math.max(0, Mth.ceil(bounds.maxY) - Mth.floor(bounds.minY));
      long depth = Math.max(0, Mth.ceil(bounds.maxZ) - Mth.floor(bounds.minZ));
      if (width > 0L && height > Long.MAX_VALUE / width) {
         return Long.MAX_VALUE;
      }
      long area = width * height;
      return depth > 0L && area > Long.MAX_VALUE / depth ? Long.MAX_VALUE : area * depth;
   }
}

