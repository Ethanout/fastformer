package io.github.fastformer.fastplace;

import com.mojang.logging.LogUtils;
import io.github.fastformer.fastplace.geometry.OperationGeometry;
import io.github.fastformer.client.operation.ClientBlockSnapshot;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import io.github.fastformer.network.FastPlaceNetwork;
import org.slf4j.Logger;

public final class OperationManager {
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final Map<UUID, OperationSession> SESSIONS = new HashMap<>();
   private static final Map<UUID, OperationTask> TASKS = new HashMap<>();

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
         new OperationTask(
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
         new WorkspaceTask(
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

   static void tickWorld(net.minecraft.server.MinecraftServer server) {
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
      OperationTask task = TASKS.get(owner);
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
      TaskResult result = task.tick(context, level, WorldTaskBudget.forServerTick());
      if (result != TaskResult.ACTIVE) {
         if (result == TaskResult.FAILED) {
            settleFailedTask(context, task);
         } else if (result == TaskResult.JOURNAL_FAILED) {
            settleFailedTask(context, task);
         } else if (task.hasWrites()) {
            if (!WorldHistoryManager.commitPreparedOperation(context, task.preparedBatch(), task.journal())) {
               result = TaskResult.FAILED;
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
         if (task instanceof WorkspaceTask workspaceTask) {
            FastPlaceNetwork.sendWorkspaceResult(
               context.onlinePlayer(),
               workspaceTask.transferId(),
               result == TaskResult.COMPLETE || result == TaskResult.EMPTY,
               workspaceTask.failedPartIds()
            );
         }
         TASKS.remove(owner, task);
         context.actionBar(
            result == TaskResult.COMPLETE
               ? FastPlaceMessages.text("fastformer.message.operation_complete")
               : result == TaskResult.EMPTY
                  ? FastPlaceMessages.text("fastformer.message.operation_empty")
                  : result == TaskResult.FAILED
                     ? FastPlaceMessages.text("fastformer.message.operation_failed_rollback")
                      : result == TaskResult.JOURNAL_FAILED
                         ? FastPlaceMessages.text("fastformer.message.recovery_journal_failed")
                         : result == TaskResult.MEMORY_UNSAFE
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
         if (task instanceof WorkspaceTask workspaceTask) {
            FastPlaceNetwork.sendWorkspaceResult(
               context.onlinePlayer(), workspaceTask.transferId(), false, workspaceTask.failedPartIds()
            );
         }
         LOGGER.error("FastFormer operation task failed for {} and was transferred to recovery", owner, exception);
         context.actionBar(FastPlaceMessages.text("fastformer.message.operation_failed_rollback"));
      }
   }

   private static void settleFailedTask(WorldTaskContext context, OperationTask task) {
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
      for (OperationTask task : TASKS.values()) {
         task.cancelJournalPreparation();
      }
      TASKS.clear();
      SESSIONS.clear();
   }

   public static boolean cancelRestore(ServerPlayer player) {
      return WorldHistoryManager.cancel(player);
   }

   public static TaskCancellationResult cancelTask(ServerPlayer player) {
      OperationTask task = TASKS.remove(player.getUUID());
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

   private enum TaskResult {
      ACTIVE,
      COMPLETE,
      EMPTY,
      EXCEEDED,
      MEMORY_UNSAFE,
      FAILED,
      JOURNAL_FAILED
   }

   private enum TaskPhase {
      SCAN,
      VALIDATE,
      JOURNAL,
      CLEAR,
      PLACE,
      FINALIZE,
      FINAL_JOURNAL
   }

   private static class OperationTask {
      private final AABB bounds;
      private final OperationSelectionVolume selection;
      private final OperationMode mode;
      private final OperationConflictMode conflictMode;
      private final boolean copy;
      private final BlockPos translation;
      private final OperationStackRegion stackRegion;
      private final PlacementUpdateMode updateMode;
      private final int maxPlacement;
      private final ResourceKey<Level> dimension;
      private final List<ReversibleBlockSnapshot> source = new ArrayList<>();
      private final ArrayDeque<ReversibleBlockSnapshot> undo = new ArrayDeque<>();
      private final Map<BlockPos, ReversibleBlockSnapshot> expected = new HashMap<>();
      private final Map<BlockPos, ReversibleBlockSnapshot> after = new HashMap<>();
      private boolean failed;
      private PersistentRecoveryJournal journal;
      private CompletableFuture<Optional<PersistentRecoveryJournal>> journalFuture;
      private WorldOperationCommit commitPreparation;
      private volatile boolean cancelled;
      private TaskPhase phase = TaskPhase.SCAN;
      private int x;
      private int y;
      private int z;
      private int sourceIndex;
      private int repeatX;
      private int repeatY;
      private int repeatZ;
      private Iterator<Map.Entry<BlockPos, ReversibleBlockSnapshot>> finalizationIterator;
      private long plannedBlocks;
      private long blockEntityReserve;
      private boolean memoryUnsafe;

      private OperationTask(
         OperationSelectionVolume selection,
         OperationMode mode,
         OperationConflictMode conflictMode,
         boolean copy,
         BlockPos translation,
         OperationStackRegion stackRegion,
         PlacementUpdateMode updateMode,
         int maxPlacement,
         ResourceKey<Level> dimension
      ) {
         this.selection = selection;
         this.bounds = selection.bounds();
         this.mode = mode;
         this.conflictMode = conflictMode;
         this.copy = copy;
         this.translation = translation;
         this.stackRegion = stackRegion;
         this.updateMode = updateMode;
         this.maxPlacement = maxPlacement;
         this.dimension = dimension;
         this.resetCursor();
         this.repeatX = stackRegion.min().getX();
         this.repeatY = stackRegion.min().getY();
         this.repeatZ = stackRegion.min().getZ();
      }

      private OperationTask() {
         this.selection = null;
         this.bounds = null;
         this.mode = OperationMode.MOVE;
         this.conflictMode = OperationConflictMode.REPLACE;
         this.copy = false;
         this.translation = BlockPos.ZERO;
         this.stackRegion = OperationStackRegion.origin();
         this.updateMode = PlacementUpdateMode.NORMAL;
         this.maxPlacement = 0;
         this.dimension = Level.OVERWORLD;
      }

      TaskResult tick(WorldTaskContext context, ServerLevel level, WorldTaskBudget budget) {
         while (budget.tryConsume()) {
            if (!PersistentRecoveryJournal.writesAllowed()) {
               this.failed = true;
               return TaskResult.FAILED;
            }
            if (this.phase == TaskPhase.SCAN) {
               BlockPos pos = this.cursorPos();
               BlockState state = level.getBlockState(pos);
               if (!state.isAir() && this.selection.intersects(new AABB(pos))) {
                  java.util.Optional<ReversibleBlockSnapshot> snapshot = ReversibleBlockSnapshot.capture(level, pos);
                  if (snapshot.isEmpty()) {
                     this.failed = true;
                     return TaskResult.FAILED;
                  }
                   this.source.add(snapshot.orElseThrow());
                   this.expected.putIfAbsent(pos.immutable(), snapshot.orElseThrow());
                   this.blockEntityReserve = WorldOperationMemory.saturatingAdd(
                      this.blockEntityReserve,
                      WorldOperationMemory.snapshotNbtReserve(snapshot.orElseThrow())
                   );
                   if (!WorldOperationMemory.canPrepare(this.source.size(), this.blockEntityReserve)) {
                      return TaskResult.MEMORY_UNSAFE;
                   }
               }
               if (!this.advanceCursor()) {
                  if (this.source.isEmpty()) {
                     return TaskResult.EMPTY;
                   }
                   long copies = this.placementRepetitionCount();
                   if (copies <= 0L || this.source.size() > (long)this.maxPlacement / copies) {
                      return TaskResult.EXCEEDED;
                   }
                   long operationBlocks = (long)this.source.size() * copies;
                   this.plannedBlocks = operationBlocks;
                   if (!WorldOperationMemory.canPrepare(operationBlocks, this.blockEntityReserve)) {
                      return TaskResult.MEMORY_UNSAFE;
                   }
                  this.phase = TaskPhase.VALIDATE;
                  this.resetPlacementCursor();
               }
            } else if (this.phase == TaskPhase.VALIDATE) {
               if (!this.validateNext(level)) {
                  if (this.memoryUnsafe) {
                     return TaskResult.MEMORY_UNSAFE;
                  }
                  if (this.failed) {
                     return TaskResult.FAILED;
                  }
                  this.phase = TaskPhase.JOURNAL;
               }
            } else if (this.phase == TaskPhase.JOURNAL) {
               JournalPreparation preparation = this.prepareJournal(context);
               if (preparation == JournalPreparation.PENDING) {
                  return TaskResult.ACTIVE;
               }
               if (preparation == JournalPreparation.FAILED) {
                  return TaskResult.JOURNAL_FAILED;
               }
               this.phase = this.clearsSource() ? TaskPhase.CLEAR : TaskPhase.PLACE;
               this.resetPlacementCursor();
               this.resetCursor();
            } else if (this.phase == TaskPhase.CLEAR) {
               if (this.sourceIndex >= this.source.size()) {
                  this.phase = TaskPhase.PLACE;
                  this.sourceIndex = 0;
               } else {
                  ReversibleBlockSnapshot sourceBlock = this.source.get(this.sourceIndex++);
                  if (!this.setBlock(level, sourceBlock.pos(), Blocks.AIR.defaultBlockState())) {
                     return TaskResult.FAILED;
                  }
               }
            } else if (this.phase == TaskPhase.PLACE) {
               if (!this.placeNext(level)) {
                  if (this.failed) {
                     return TaskResult.FAILED;
                  }
                  this.phase = TaskPhase.FINALIZE;
               }
            } else if (this.phase == TaskPhase.FINALIZE) {
               if (!this.finalizeNext(level)) {
                  if (this.failed) {
                     return TaskResult.FAILED;
                  }
                  this.phase = TaskPhase.FINAL_JOURNAL;
               }
            } else {
               JournalPreparation finalJournal = this.prepareCommit();
               if (finalJournal == JournalPreparation.PENDING) {
                  return TaskResult.ACTIVE;
               }
               return finalJournal == JournalPreparation.READY ? TaskResult.COMPLETE : TaskResult.FAILED;
            }
         }
         return TaskResult.ACTIVE;
      }

      private boolean placeNext(ServerLevel level) {
         PlacementTarget target = this.nextPlacementTarget();
         if (target == null) {
            return false;
         }
         this.place(level, target.pos(), target.source());
         return !this.failed;
      }

      private boolean finalizeNext(ServerLevel level) {
         if (this.finalizationIterator == null) {
            this.finalizationIterator = this.after.entrySet().iterator();
         }
         if (!this.finalizationIterator.hasNext()) {
            return false;
         }
         Map.Entry<BlockPos, ReversibleBlockSnapshot> entry = this.finalizationIterator.next();
         Optional<ReversibleBlockSnapshot> actual = ReversibleBlockSnapshot.capture(level, entry.getKey());
         if (actual.isEmpty()) {
            this.failed = true;
            return false;
         }
         entry.setValue(actual.orElseThrow());
         return true;
      }

      private boolean validateNext(ServerLevel level) {
         PlacementTarget target = this.nextPlacementTarget();
         if (target == null) {
            return false;
         }
         BlockState previous = level.getBlockState(target.pos());
         java.util.Optional<ReversibleBlockSnapshot> snapshot = ReversibleBlockSnapshot.capture(level, target.pos());
         if (snapshot.isEmpty()) {
            this.failed = true;
            return false;
         }
         ReversibleBlockSnapshot captured = snapshot.orElseThrow();
         if (this.expected.putIfAbsent(target.pos().immutable(), captured) == null) {
            this.blockEntityReserve = WorldOperationMemory.saturatingAdd(
               this.blockEntityReserve,
               WorldOperationMemory.snapshotNbtReserve(captured)
            );
            if (!WorldOperationMemory.canPrepare(this.plannedBlocks, this.blockEntityReserve)) {
               this.memoryUnsafe = true;
               return false;
            }
         }
         if (this.conflictMode == OperationConflictMode.KEEP_EXISTING && !previous.canBeReplaced()) {
            return true;
         }
         return true;
      }

      private PlacementTarget nextPlacementTarget() {
         while (this.repeatX <= this.stackRegion.max().getX()) {
            boolean skippedOrigin = this.skipOrigin()
               && this.repeatX == 0 && this.repeatY == 0 && this.repeatZ == 0;
            if (skippedOrigin) {
               if (!this.advanceRepetition()) {
                  return null;
               }
               continue;
            }
            ReversibleBlockSnapshot sourceBlock = this.source.get(this.sourceIndex++);
            BlockPos repetition = new BlockPos(this.repeatX, this.repeatY, this.repeatZ);
            BlockPos target = sourceBlock.pos().offset(
               OperationGeometry.stackDisplacement(this.bounds, repetition).offset(this.translation)
            );
            if (this.sourceIndex >= this.source.size()) {
               this.sourceIndex = 0;
               this.advanceRepetition();
            }
            return new PlacementTarget(target, sourceBlock);
         }
         return null;
      }

      private void resetPlacementCursor() {
         this.sourceIndex = 0;
         this.repeatX = this.stackRegion.min().getX();
         this.repeatY = this.stackRegion.min().getY();
         this.repeatZ = this.stackRegion.min().getZ();
      }

      private boolean advanceRepetition() {
         if (++this.repeatZ <= this.stackRegion.max().getZ()) {
            return true;
         }
         this.repeatZ = this.stackRegion.min().getZ();
         if (++this.repeatY <= this.stackRegion.max().getY()) {
            return true;
         }
         this.repeatY = this.stackRegion.min().getY();
         return ++this.repeatX <= this.stackRegion.max().getX();
      }

      private boolean skipOrigin() {
         return this.mode == OperationMode.STACK && this.translation.equals(BlockPos.ZERO);
      }

      private boolean clearsSource() {
         return OperationExecutionSemantics.clearsSource(this.mode, this.translation, this.copy);
      }

      private long placementRepetitionCount() {
         long count = this.stackRegion.cellCount();
         return this.skipOrigin() ? Math.max(0L, count - 1L) : count;
      }

      private void place(ServerLevel level, BlockPos pos, ReversibleBlockSnapshot source) {
         if (!this.clearsSource()) {
            ReversibleBlockSnapshot sourceExpected = this.expected.get(source.pos());
            if (sourceExpected == null || !sourceExpected.matches(level, source.pos())) {
               this.failed = true;
               return;
            }
         }
         BlockState current = level.getBlockState(pos);
         if (this.conflictMode == OperationConflictMode.KEEP_EXISTING && !current.canBeReplaced()) {
            return;
         }
         ReversibleBlockSnapshot expectedSnapshot = this.expected.get(pos);
         if (expectedSnapshot != null && !expectedSnapshot.matches(level, pos)) {
            this.failed = true;
            return;
         }
         if (this.conflictMode == OperationConflictMode.REPLACE || current.canBeReplaced()) {
            ReversibleBlockSnapshot before = expectedSnapshot;
            if (before == null) {
               java.util.Optional<ReversibleBlockSnapshot> previous = ReversibleBlockSnapshot.capture(level, pos);
               if (previous.isEmpty()) {
                  this.failed = true;
                  return;
               }
               before = previous.orElseThrow();
            }
            this.undo.addFirst(before);
            if (!source.placeAt(level, pos, this.updateMode.flags())) {
               java.util.Optional<ReversibleBlockSnapshot> written = ReversibleBlockSnapshot.capture(level, pos);
                this.after.put(
                   pos.immutable(),
                   written.orElseGet(() -> new ReversibleBlockSnapshot(
                      pos, level.getBlockState(pos), level.getFluidState(pos), null
                   ))
                );
                if (this.updateMode == PlacementUpdateMode.NORMAL) {
                   ReversibleBlockSnapshot.refreshTaskOwnedNeighbors(
                      level, pos, this.expected, this.undo, this.after
                   );
                }
                this.failed = true;
            } else {
               java.util.Optional<ReversibleBlockSnapshot> written = ReversibleBlockSnapshot.capture(level, pos);
               if (written.isEmpty()) {
                  ReversibleBlockSnapshot fallback = new ReversibleBlockSnapshot(
                     pos, level.getBlockState(pos), level.getFluidState(pos), null
                  );
                  this.expected.put(pos.immutable(), fallback);
                  this.after.put(pos.immutable(), fallback);
                  this.failed = true;
                } else {
                   this.expected.put(pos.immutable(), written.orElseThrow());
                   this.after.put(pos.immutable(), written.orElseThrow());
                   if (this.updateMode == PlacementUpdateMode.NORMAL
                      && !ReversibleBlockSnapshot.refreshTaskOwnedNeighbors(
                         level, pos, this.expected, this.undo, this.after
                      )) {
                      this.failed = true;
                   }
                }
            }
         }
      }

      private boolean setBlock(ServerLevel level, BlockPos pos, BlockState state) {
         BlockState previous = level.getBlockState(pos);
         ReversibleBlockSnapshot expectedSnapshot = this.expected.get(pos);
         if (expectedSnapshot != null && !expectedSnapshot.matches(level, pos)) {
            this.failed = true;
            return false;
         }
         if (previous.equals(state)) {
            // CLEAR is an intentional write in a MOVE operation.  Record its
            // resulting state as the new expected value so an overlapping
            // destination (move by one block, for example) is not mistaken
            // for an external edit during PLACE.
            java.util.Optional<ReversibleBlockSnapshot> current = ReversibleBlockSnapshot.capture(level, pos);
            if (current.isEmpty()) {
               this.failed = true;
               return false;
            }
            ReversibleBlockSnapshot snapshot = current.orElseThrow();
            this.expected.put(pos.immutable(), snapshot);
            this.after.put(pos.immutable(), snapshot);
            return true;
         }
         ReversibleBlockSnapshot before = expectedSnapshot;
         if (before == null) {
            java.util.Optional<ReversibleBlockSnapshot> snapshot = ReversibleBlockSnapshot.capture(level, pos);
            if (snapshot.isEmpty()) {
               this.failed = true;
               return false;
            }
            before = snapshot.orElseThrow();
         }
         // Journal before setBlock because callbacks may mutate the world and
         // then fail or throw before the call returns.
         this.undo.addFirst(before);
         if (!WorldWriteSideEffectGuard.setBlock(level, pos, state, this.updateMode.flags())) {
            java.util.Optional<ReversibleBlockSnapshot> written = ReversibleBlockSnapshot.capture(level, pos);
            this.after.put(
               pos.immutable(),
               written.orElseGet(() -> new ReversibleBlockSnapshot(
                  pos, level.getBlockState(pos), level.getFluidState(pos), null
               ))
            );
            if (this.updateMode == PlacementUpdateMode.NORMAL) {
               ReversibleBlockSnapshot.refreshTaskOwnedNeighbors(
                  level, pos, this.expected, this.undo, this.after
               );
            }
            this.failed = true;
            return false;
         }
         java.util.Optional<ReversibleBlockSnapshot> after = ReversibleBlockSnapshot.capture(level, pos);
         if (after.isEmpty()) {
            ReversibleBlockSnapshot fallback = new ReversibleBlockSnapshot(
               pos,
               level.getBlockState(pos),
               level.getFluidState(pos),
               null
            );
            this.expected.put(pos.immutable(), fallback);
            this.after.put(pos.immutable(), fallback);
            this.failed = true;
            return false;
         }
         this.expected.put(pos.immutable(), after.orElseThrow());
         this.after.put(pos.immutable(), after.orElseThrow());
         if (this.updateMode == PlacementUpdateMode.NORMAL
            && !ReversibleBlockSnapshot.refreshTaskOwnedNeighbors(
               level, pos, this.expected, this.undo, this.after
            )) {
            this.failed = true;
            return false;
         }
         return true;
      }

      private BlockPos cursorPos() {
         return new BlockPos(this.x, this.y, this.z);
      }

      private void resetCursor() {
         this.x = Mth.floor(this.bounds.minX);
         this.y = Mth.floor(this.bounds.minY);
         this.z = Mth.floor(this.bounds.minZ);
      }

      private boolean advanceCursor() {
         if (++this.z < Mth.ceil(this.bounds.maxZ)) {
            return true;
         }
         this.z = Mth.floor(this.bounds.minZ);
         if (++this.y < Mth.ceil(this.bounds.maxY)) {
            return true;
         }
         this.y = Mth.floor(this.bounds.minY);
         if (++this.x < Mth.ceil(this.bounds.maxX)) {
            return true;
         }
         this.resetCursor();
         return false;
      }

      String phaseName() {
         return switch (this.phase) {
            case SCAN -> "扫描";
            case VALIDATE -> "验证快照";
            case JOURNAL -> "写入安全日志";
            case CLEAR -> "清空";
            case PLACE -> "写入";
            case FINALIZE -> "确认最终状态";
            case FINAL_JOURNAL -> "压缩最终恢复状态";
         };
      }

      private JournalPreparation prepareJournal(WorldTaskContext context) {
         if (this.journal != null) {
            return JournalPreparation.READY;
         }
         if (this.journalFuture == null) {
            var server = context.server();
            UUID owner = context.owner();
            this.journalFuture = CompletableFuture.supplyAsync(() -> this.journalSnapshots().flatMap(snapshots ->
               snapshots.before().isEmpty()
                  ? Optional.empty()
                  : PersistentRecoveryJournal.begin(
                     server,
                     owner,
                     this.dimension,
                     snapshots.before(),
                     snapshots.after()
                  )
            ), PersistentRecoveryJournal.executor());
            return JournalPreparation.PENDING;
         }
         if (!this.journalFuture.isDone()) {
            return JournalPreparation.PENDING;
         }
         try {
            this.journal = this.journalFuture.join().orElse(null);
         } catch (RuntimeException exception) {
            return JournalPreparation.FAILED;
         }
         return this.journal == null ? JournalPreparation.FAILED : JournalPreparation.READY;
      }

      private Optional<JournalSnapshots> journalSnapshots() {
         List<ReversibleBlockSnapshot> originals = new ArrayList<>(this.expected.values());
         for (ReversibleBlockSnapshot snapshot : this.source) {
            if (!this.expected.containsKey(snapshot.pos())) {
               originals.add(snapshot);
            }
         }
         Map<BlockPos, ReversibleBlockSnapshot> finalStates = new HashMap<>();
         for (ReversibleBlockSnapshot snapshot : originals) {
            finalStates.put(snapshot.pos(), snapshot);
         }
         try {
            if (this.clearsSource()) {
               for (ReversibleBlockSnapshot sourceSnapshot : this.source) {
                  BlockPos pos = sourceSnapshot.pos();
                  BlockState air = Blocks.AIR.defaultBlockState();
                  finalStates.put(pos, new ReversibleBlockSnapshot(pos, air, air.getFluidState(), null));
               }
            }
            for (BlockPos repetition : this.stackRegion.repetitions(this.maxPlacement)) {
               if (this.skipOrigin() && repetition.equals(BlockPos.ZERO)) {
                  continue;
               }
               BlockPos displacement = OperationGeometry.stackDisplacement(this.bounds, repetition).offset(this.translation);
               for (ReversibleBlockSnapshot sourceSnapshot : this.source) {
                  putPredictedTarget(finalStates, sourceSnapshot.pos().offset(displacement), sourceSnapshot);
               }
            }
         } catch (RuntimeException exception) {
            return Optional.empty();
         }
         List<ReversibleBlockSnapshot> after = new ArrayList<>(originals.size());
         for (ReversibleBlockSnapshot snapshot : originals) {
            ReversibleBlockSnapshot finalSnapshot = finalStates.get(snapshot.pos());
            if (finalSnapshot == null) {
               return Optional.empty();
            }
            after.add(finalSnapshot);
         }
         return Optional.of(new JournalSnapshots(originals, after));
      }

      private JournalPreparation prepareCommit() {
         if (this.commitPreparation == null) {
            this.commitPreparation = WorldOperationCommit.begin(this.dimension, this.undo, this.after, this.journal);
         }
         return this.commitPreparation.poll();
      }

      Optional<WorldChangeBatch> preparedBatch() {
         return this.commitPreparation == null ? Optional.empty() : this.commitPreparation.batch();
      }

      private void putPredictedTarget(
         Map<BlockPos, ReversibleBlockSnapshot> finalStates,
         BlockPos target,
         ReversibleBlockSnapshot sourceSnapshot
      ) {
         ReversibleBlockSnapshot current = finalStates.get(target);
         if (current == null) {
            current = this.expected.get(target);
         }
         if (current == null) {
            throw new IllegalStateException("Missing validated operation target");
         }
         if (this.conflictMode == OperationConflictMode.KEEP_EXISTING && !current.state().canBeReplaced()) {
            return;
         }
         finalStates.put(target.immutable(), new ReversibleBlockSnapshot(
            target,
            sourceSnapshot.state(),
            sourceSnapshot.fluidState(),
            sourceSnapshot.blockEntity()
         ));
      }

      void cancelJournalPreparation() {
         this.cancelled = true;
         if (this.commitPreparation != null) {
            this.commitPreparation.cancel();
         }
         if (this.journalFuture != null) {
            this.journalFuture.whenComplete((created, exception) -> {
               if (this.cancelled && this.journal == null && exception == null && created != null) {
                  created.ifPresent(PersistentRecoveryJournal::discardUnused);
               }
            });
         }
      }

      PersistentRecoveryJournal journal() {
         return this.journal;
      }

      boolean acquireLease(WorldTaskContext context) {
         return WorldWriteCoordinator.tryAcquire(context.server(), this.dimension, context.owner());
      }

      void releaseLease(WorldTaskContext context) {
         WorldWriteCoordinator.release(context.server(), this.dimension, context.owner());
      }

      void releaseAfterCancelledJournal(WorldTaskContext context) {
         this.cancelJournalPreparation();
         WorldWriteCoordinator.releaseAfterUnusedJournal(
            context.server(), this.dimension, context.owner(), this.journal, this.journalFuture
         );
      }

      boolean hasWrites() {
         return !this.undo.isEmpty();
      }

      ArrayDeque<ReversibleBlockSnapshot> undoChanges() {
         return this.undo;
      }

      Map<BlockPos, ReversibleBlockSnapshot> afterChanges() {
         return this.after;
      }

      ResourceKey<Level> dimension() {
         return this.dimension;
      }

      private record PlacementTarget(BlockPos pos, ReversibleBlockSnapshot source) {
      }

      private record JournalSnapshots(
         List<ReversibleBlockSnapshot> before,
         List<ReversibleBlockSnapshot> after
      ) {
      }
   }

   /** Applies a fully client-edited workspace after one all-or-nothing validation pass. */
   private static final class WorkspaceTask extends OperationTask {
      private final UUID transferId;
      private final OperationWorkspacePlan plan;
      private final PlacementUpdateMode updateMode;
      private final int maxPlacement;
      private final ResourceKey<Level> dimension;
      private final ArrayDeque<ReversibleBlockSnapshot> undo = new ArrayDeque<>();
      private final Map<BlockPos, ReversibleBlockSnapshot> expected = new HashMap<>();
      private final Map<BlockPos, ReversibleBlockSnapshot> after = new HashMap<>();
      private Map<BlockPos, ClientBlockSnapshot> desired = Map.of();
      private Iterator<Map.Entry<BlockPos, ClientBlockSnapshot>> writeIterator;
      private Iterator<Map.Entry<BlockPos, ReversibleBlockSnapshot>> finalizationIterator;
      private PersistentRecoveryJournal journal;
      private CompletableFuture<Optional<PersistentRecoveryJournal>> journalFuture;
      private WorldOperationCommit commitPreparation;
      private boolean cancelled;
      private List<Integer> invalidPartIds = List.of();
      private WorkspacePhase workspacePhase = WorkspacePhase.VALIDATE;

      private WorkspaceTask(
         UUID transferId,
         OperationWorkspacePlan plan,
         PlacementUpdateMode updateMode,
         int maxPlacement,
         ResourceKey<Level> dimension
      ) {
         super();
         this.transferId = transferId;
         this.plan = plan;
         this.updateMode = updateMode;
         this.maxPlacement = maxPlacement;
         this.dimension = dimension;
      }

      private UUID transferId() {
         return this.transferId;
      }

      @Override
      TaskResult tick(WorldTaskContext context, ServerLevel level, WorldTaskBudget budget) {
         while (budget.tryConsume()) {
            if (!PersistentRecoveryJournal.writesAllowed()) {
               return TaskResult.FAILED;
            }
            switch (this.workspacePhase) {
               case VALIDATE -> {
                  TaskResult validation = this.validate(level);
                  if (validation != TaskResult.ACTIVE) {
                     return validation;
                  }
                  this.workspacePhase = WorkspacePhase.JOURNAL;
               }
               case JOURNAL -> {
                  JournalPreparation preparation = this.prepareJournal(context);
                  if (preparation == JournalPreparation.PENDING) {
                     return TaskResult.ACTIVE;
                  }
                  if (preparation == JournalPreparation.FAILED) {
                     return TaskResult.JOURNAL_FAILED;
                  }
                  this.workspacePhase = WorkspacePhase.WRITE;
                  this.writeIterator = this.desired.entrySet().iterator();
               }
               case WRITE -> {
                  if (!this.writeIterator.hasNext()) {
                     this.workspacePhase = WorkspacePhase.FINALIZE;
                     continue;
                  }
                  if (!this.write(level, this.writeIterator.next())) {
                     return TaskResult.FAILED;
                  }
               }
               case FINALIZE -> {
                  if (this.finalizationIterator == null) {
                     this.finalizationIterator = this.after.entrySet().iterator();
                  }
                  if (!this.finalizationIterator.hasNext()) {
                     this.workspacePhase = WorkspacePhase.FINAL_JOURNAL;
                     continue;
                  }
                  Map.Entry<BlockPos, ReversibleBlockSnapshot> entry = this.finalizationIterator.next();
                  Optional<ReversibleBlockSnapshot> actual = ReversibleBlockSnapshot.capture(level, entry.getKey());
                  if (actual.isEmpty()) {
                     return TaskResult.FAILED;
                  }
                  entry.setValue(actual.orElseThrow());
               }
               case FINAL_JOURNAL -> {
                  if (this.commitPreparation == null) {
                     this.commitPreparation = WorldOperationCommit.begin(
                        this.dimension, this.undo, this.after, this.journal
                     );
                  }
                  JournalPreparation preparation = this.commitPreparation.poll();
                  if (preparation == JournalPreparation.PENDING) {
                     return TaskResult.ACTIVE;
                  }
                  return preparation == JournalPreparation.READY ? TaskResult.COMPLETE : TaskResult.FAILED;
               }
            }
         }
         return TaskResult.ACTIVE;
      }

      private TaskResult validate(ServerLevel level) {
         OperationWorkspaceValidator.Result validated = OperationWorkspaceValidator.validate(
            this.plan,
            pos -> ReversibleBlockSnapshot.capture(level, pos).map(WorkspaceTask::clientSnapshot),
            this.maxPlacement
         );
         if (!validated.success()) {
            this.invalidPartIds = validated.invalidPartIds();
            return TaskResult.FAILED;
         }
         LinkedHashMap<BlockPos, ClientBlockSnapshot> composed = new LinkedHashMap<>();
         ClientBlockSnapshot air = new ClientBlockSnapshot(Blocks.AIR.defaultBlockState(), null);
         validated.clears().forEach(pos -> composed.put(pos.immutable(), air));
         composed.putAll(validated.writes());
         if (composed.isEmpty()) {
            return TaskResult.EMPTY;
         }
         if (composed.size() > this.maxPlacement) {
            return TaskResult.EXCEEDED;
         }
         long blockEntityReserve = 0L;
         for (Map.Entry<BlockPos, ClientBlockSnapshot> entry : composed.entrySet()) {
            if (!validSnapshot(level, entry.getKey(), entry.getValue())) {
               return TaskResult.FAILED;
            }
            Optional<ReversibleBlockSnapshot> before = ReversibleBlockSnapshot.capture(level, entry.getKey());
            if (before.isEmpty()) {
               return TaskResult.FAILED;
            }
            ReversibleBlockSnapshot snapshot = before.orElseThrow();
            this.expected.put(entry.getKey().immutable(), snapshot);
            blockEntityReserve = WorldOperationMemory.saturatingAdd(
               blockEntityReserve, WorldOperationMemory.snapshotNbtReserve(snapshot)
            );
         }
         if (!WorldOperationMemory.canPrepare(composed.size(), blockEntityReserve)) {
            return TaskResult.MEMORY_UNSAFE;
         }
         this.desired = Map.copyOf(composed);
         return TaskResult.ACTIVE;
      }

      private static boolean validSnapshot(ServerLevel level, BlockPos pos, ClientBlockSnapshot snapshot) {
         if (snapshot.blockEntity() == null) {
            return true;
         }
         if (!(snapshot.state().getBlock() instanceof EntityBlock entityBlock)) {
            return false;
         }
         try {
            BlockEntity entity = entityBlock.newBlockEntity(pos, snapshot.state());
            if (entity == null) {
               return false;
            }
            CompoundTagAtPosition.load(entity, snapshot.blockEntity(), level, pos);
            return true;
         } catch (RuntimeException exception) {
            return false;
         }
      }

      private boolean write(ServerLevel level, Map.Entry<BlockPos, ClientBlockSnapshot> entry) {
         BlockPos pos = entry.getKey();
         ReversibleBlockSnapshot before = this.expected.get(pos);
         if (before == null || !before.matches(level, pos)) {
            return false;
         }
         ClientBlockSnapshot target = entry.getValue();
         ReversibleBlockSnapshot desiredSnapshot = new ReversibleBlockSnapshot(
            pos,
            target.state(),
            target.state().getFluidState(),
            target.blockEntity() == null ? null : new BlockEntitySnapshot(target.blockEntity())
         );
         this.undo.addFirst(before);
         if (!desiredSnapshot.placeAt(level, pos, this.updateMode.flags())) {
            ReversibleBlockSnapshot.capture(level, pos).ifPresent(actual -> this.after.put(pos.immutable(), actual));
            return false;
         }
         Optional<ReversibleBlockSnapshot> actual = ReversibleBlockSnapshot.capture(level, pos);
         if (actual.isEmpty()) {
            return false;
         }
         this.expected.put(pos.immutable(), actual.orElseThrow());
         this.after.put(pos.immutable(), actual.orElseThrow());
         return this.updateMode != PlacementUpdateMode.NORMAL || ReversibleBlockSnapshot.refreshTaskOwnedNeighbors(
            level, pos, this.expected, this.undo, this.after
         );
      }

      private JournalPreparation prepareJournal(WorldTaskContext context) {
         if (this.journal != null) {
            return JournalPreparation.READY;
         }
         if (this.journalFuture == null) {
            List<ReversibleBlockSnapshot> before = new ArrayList<>(this.expected.values());
            List<ReversibleBlockSnapshot> predicted = new ArrayList<>(before.size());
            for (ReversibleBlockSnapshot original : before) {
               ClientBlockSnapshot target = this.desired.get(original.pos());
               if (target == null) {
                  return JournalPreparation.FAILED;
               }
               predicted.add(new ReversibleBlockSnapshot(
                  original.pos(), target.state(), target.state().getFluidState(),
                  target.blockEntity() == null ? null : new BlockEntitySnapshot(target.blockEntity())
               ));
            }
            this.journalFuture = CompletableFuture.supplyAsync(
               () -> PersistentRecoveryJournal.begin(
                  context.server(), context.owner(), this.dimension, before, predicted
               ),
               PersistentRecoveryJournal.executor()
            );
            return JournalPreparation.PENDING;
         }
         if (!this.journalFuture.isDone()) {
            return JournalPreparation.PENDING;
         }
         try {
            this.journal = this.journalFuture.join().orElse(null);
            return this.journal == null ? JournalPreparation.FAILED : JournalPreparation.READY;
         } catch (RuntimeException exception) {
            return JournalPreparation.FAILED;
         }
      }

      private static ClientBlockSnapshot clientSnapshot(ReversibleBlockSnapshot snapshot) {
         return new ClientBlockSnapshot(
            snapshot.state(), snapshot.blockEntity() == null ? null : snapshot.blockEntity().data()
         );
      }

      @Override
      String phaseName() {
         return switch (this.workspacePhase) {
            case VALIDATE -> "验证工作区";
            case JOURNAL -> "写入安全日志";
            case WRITE -> "写入";
            case FINALIZE -> "确认最终状态";
            case FINAL_JOURNAL -> "压缩最终恢复状态";
         };
      }

      @Override
      void cancelJournalPreparation() {
         this.cancelled = true;
         if (this.commitPreparation != null) {
            this.commitPreparation.cancel();
         }
         if (this.journalFuture != null) {
            this.journalFuture.whenComplete((created, exception) -> {
               if (this.cancelled && this.journal == null && exception == null && created != null) {
                  created.ifPresent(PersistentRecoveryJournal::discardUnused);
               }
            });
         }
      }

      @Override PersistentRecoveryJournal journal() { return this.journal; }
      @Override boolean acquireLease(WorldTaskContext context) {
         return WorldWriteCoordinator.tryAcquire(context.server(), this.dimension, context.owner());
      }
      @Override void releaseLease(WorldTaskContext context) {
         WorldWriteCoordinator.release(context.server(), this.dimension, context.owner());
      }
      @Override void releaseAfterCancelledJournal(WorldTaskContext context) {
         this.cancelJournalPreparation();
         WorldWriteCoordinator.releaseAfterUnusedJournal(
            context.server(), this.dimension, context.owner(), this.journal, this.journalFuture
         );
      }
      @Override boolean hasWrites() { return !this.undo.isEmpty(); }
      @Override ArrayDeque<ReversibleBlockSnapshot> undoChanges() { return this.undo; }
      @Override Map<BlockPos, ReversibleBlockSnapshot> afterChanges() { return this.after; }
      @Override ResourceKey<Level> dimension() { return this.dimension; }
      @Override Optional<WorldChangeBatch> preparedBatch() {
         return this.commitPreparation == null ? Optional.empty() : this.commitPreparation.batch();
      }

      private List<Integer> failedPartIds() {
         return this.invalidPartIds;
      }
   }

   private enum WorkspacePhase {
      VALIDATE,
      JOURNAL,
      WRITE,
      FINALIZE,
      FINAL_JOURNAL
   }

   /** Keeps malformed block-entity validation off the live world. */
   private static final class CompoundTagAtPosition {
      private CompoundTagAtPosition() {
      }

      private static void load(BlockEntity entity, net.minecraft.nbt.CompoundTag tag, ServerLevel level, BlockPos pos) {
         net.minecraft.nbt.CompoundTag positioned = tag.copy();
         positioned.putInt("x", pos.getX());
         positioned.putInt("y", pos.getY());
         positioned.putInt("z", pos.getZ());
         entity.loadWithComponents(positioned, level.registryAccess());
      }
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

