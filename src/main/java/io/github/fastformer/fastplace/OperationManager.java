package io.github.fastformer.fastplace;

import io.github.fastformer.fastplace.selection.OperationSelectionMode;
import io.github.fastformer.fastplace.selection.OperationSelectionVolume;

import io.github.fastformer.fastplace.world.*;

import io.github.fastformer.fastplace.session.DimensionSessionStore;
import io.github.fastformer.fastplace.session.OperationSession;
import io.github.fastformer.fastplace.task.ClientWorkspacePlacementTask;
import io.github.fastformer.fastplace.task.MemoryReservationAttempt;
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
   /**
    * Recovery snapshots already extracted from a task and not yet accepted.
    *
    * <p>The extraction moves the before/after record out of the task. The record therefore
    * lives here until the recovery path accepts it, so a failed acceptance can retry
    * without extracting a second, empty snapshot.
    */
   private static final Map<UUID, WorldRecoverySnapshot> HANDOVERS = new HashMap<>();
   /** Operation sessions kept for the dimension the player left. */
   private static final DimensionSessionStore<OperationSession> PARKED_SESSIONS = new DimensionSessionStore<>();

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
      if (!WorldOperationMemory.snapshotAdmission(volume, 0L).allowed()) {
         FastPlaceMessages.actionBar(player, FastPlaceMessages.text("fastformer.message.operation_memory_unsafe"));
         return false;
      }
      if (operationBusy(player)) {
         FastPlaceMessages.actionBar(player, FastPlaceMessages.text("fastformer.message.operation_task_running"));
         return false;
      }
      enqueueTask(
         player,
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

   /**
    * Returns the state that the ledger already holds for one transfer.
    *
    * <p>A caller uses this before any gate, because a replay reports finished work and is
    * not a new admission. A gate that refuses new work must not hide an existing result.</p>
    *
    * @return the recorded state, or {@code UNKNOWN} when the ledger holds none
    */
   public static io.github.fastformer.network.payload.operation.OperationSubmissionOutcome recordedOutcome(
      ServerPlayer player, UUID transferId
   ) {
      if (player == null || transferId == null) {
         return io.github.fastformer.network.payload.operation.OperationSubmissionOutcome.UNKNOWN;
      }
      return io.github.fastformer.fastplace.world.WorkspaceSubmissionLedger.outcomeFor(
         player.getServer(), player.getUUID(), player.serverLevel().dimension().location(), transferId
      );
   }

   /**
    * Admits one workspace submission.
    *
    * <p>This method sends no result packet. The admission handler owns every result
    * packet, so one transfer can never receive two answers that contradict each other.</p>
    *
    * @return what happened to this admission
    */
   public static io.github.fastformer.fastplace.WorkspaceAdmission applyWorkspace(
      ServerPlayer player, UUID transferId, OperationWorkspacePlan plan
   ) {
      if (transferId == null || plan == null || plan.parts().isEmpty()) {
         return io.github.fastformer.fastplace.WorkspaceAdmission.rejected();
      }
      net.minecraft.server.MinecraftServer server = player.getServer();
      net.minecraft.resources.ResourceLocation dimension = player.serverLevel().dimension().location();
      var recorded = io.github.fastformer.fastplace.world.WorkspaceSubmissionLedger.outcomeFor(
         server, player.getUUID(), dimension, transferId
      );
      if (recorded != io.github.fastformer.network.payload.operation.OperationSubmissionOutcome.UNKNOWN) {
         // This transfer already reached the server. A replay can arrive when a client
         // retries an upload that the server already accepted. Running the work again
         // would write the same blocks a second time, so the server starts no new task and
         // reports the state that it already holds.
         return io.github.fastformer.fastplace.WorkspaceAdmission.replayed(recorded);
      }
      if (!PersistentRecoveryJournal.writesAllowed()) {
         // The global write gate is closed for this server session, because a disk
         // journal could not be recovered safely. Refuse before the ledger begin so no
         // record claims this transfer was accepted. A later retry uses a new transfer.
         FastPlaceMessages.actionBar(player, FastPlaceMessages.text("fastformer.message.recovery_journal_blocked"));
         return io.github.fastformer.fastplace.WorkspaceAdmission.rejected();
      }
      int maxPlacement = FastPlaceSettings.load(player).maxPlacement();
      if (operationBusy(player)) {
         FastPlaceMessages.actionBar(player, FastPlaceMessages.text("fastformer.message.operation_task_running"));
         return io.github.fastformer.fastplace.WorkspaceAdmission.rejected();
      }
      ClientWorkspacePlacementTask workspaceTask = new ClientWorkspacePlacementTask(
         transferId,
         plan,
         FastPlaceSettings.load(player).placementUpdateMode(),
         maxPlacement,
         player.serverLevel().dimension()
      );
      // The task enters the queue first. The world tick loop walks TASKS, so a task that
      // reaches the map runs even when scheduling its first resume fails. Every step after
      // this point reports that fact, and none of them may undo it.
      TASKS.put(player.getUUID(), workspaceTask);
      recordAdmission(server, player.getUUID(), dimension, transferId);
      scheduleResume(player, workspaceTask);
      reportAdmissionFeedback(player);
      return io.github.fastformer.fastplace.WorkspaceAdmission.newQueued();
   }

   /**
    * Records that one transfer was accepted and now runs.
    *
    * <p>The task is already in the queue here, so a failed record must not travel out of
    * {@link #applyWorkspace}. The task reports its own result when it settles, and that
    * report writes the outcome through the ledger. A query in the meantime answers
    * {@code UNKNOWN}, which keeps the client draft without a resend.</p>
    */
   private static void recordAdmission(
      net.minecraft.server.MinecraftServer server, UUID owner,
      net.minecraft.resources.ResourceLocation dimension, UUID transferId
   ) {
      try {
         io.github.fastformer.fastplace.world.WorkspaceSubmissionLedger.begin(
            server, owner, dimension, transferId
         );
      } catch (RuntimeException exception) {
         LOGGER.warn("FastFormer queued a workspace submission but could not record it", exception);
      }
   }

   /** Schedules the first resume of one queued task. A scheduling fault keeps the task. */
   private static void scheduleResume(ServerPlayer player, WorldOperationTask task) {
      try {
         WorldTaskContext context = new WorldTaskContext(player.getServer(), player.getUUID());
         context.withResume(() -> resumeTask(context, task)).enqueueResume();
      } catch (RuntimeException exception) {
         LOGGER.warn("FastFormer queued a workspace submission but could not schedule its resume", exception);
      }
   }

   /**
    * Reports an accepted admission to the player.
    *
    * <p>The admission is already complete here: the task is queued and the ledger holds it.
    * A fault in the feedback must not travel out of {@link #applyWorkspace}, because a
    * caller that catches it would report a failure for work that is running.</p>
    */
   private static void reportAdmissionFeedback(ServerPlayer player) {
      try {
         admissionFeedback.accept(player);
      } catch (RuntimeException exception) {
         LOGGER.warn("FastFormer queued a workspace submission but could not report it", exception);
      }
   }

   /** Announces an accepted admission. A test replaces this to force a feedback fault. */
   private static java.util.function.Consumer<ServerPlayer> admissionFeedback = OperationManager::announceAdmission;

   private static void announceAdmission(ServerPlayer player) {
      cancel(player);
      FastPlaceMessages.actionBar(player, FastPlaceMessages.text("fastformer.message.operation_scanning"));
   }

   /**
    * Replaces the admission feedback of one test run.
    *
    * @param feedback the replacement, or null to restore the real feedback
    */
   static void setAdmissionFeedbackForTest(java.util.function.Consumer<ServerPlayer> feedback) {
      admissionFeedback = feedback == null ? OperationManager::announceAdmission : feedback;
   }

   /**
    * Hands every write-blocked operation task to the recovery path.
    *
    * <p>The scheduler calls this instead of {@link #tickWorld} while the global write gate
    * is closed. A task must not run again, and it must not be dropped either: the queue
    * slot leaves only after the recovery path accepts the snapshot. A refused or failed
    * handover keeps the task, so a later tick can try again.
    */
   public static void handOverBlockedTasks(net.minecraft.server.MinecraftServer server) {
      for (UUID owner : List.copyOf(TASKS.keySet())) {
         handOverBlockedTask(new WorldTaskContext(server, owner));
      }
   }

   private static void handOverBlockedTask(WorldTaskContext context) {
      TaskCancellationResult handover = settleCancelledTask(context);
      if (handover.handled()) {
         context.actionBar(FastPlaceMessages.text("fastformer.message.recovery_journal_blocked"));
      }
   }

   /**
    * Stops one queued task and records its workspace outcome when recovery accepts it.
    *
    * <p>This is the only owner of that result packet. A later tick, a cancel, or a
    * closed-gate handover all reach it, so one transfer cannot receive two answers.
    * Recovery does not finish the ledger: an {@code IN_PROGRESS} record never ages out,
    * and a missing finish would leave the client waiting.</p>
    *
    * @return {@link TaskCancellationResult#NOT_ACTIVE} when no task exists
    */
   private static TaskCancellationResult settleCancelledTask(WorldTaskContext context) {
      WorldOperationTask task = TASKS.get(context.owner());
      if (task == null) {
         return TaskCancellationResult.NOT_ACTIVE;
      }
      TaskCancellationResult handover;
      try {
         handover = transferToRecovery(context, task);
      } catch (RuntimeException | OutOfMemoryError failure) {
         // transferToRecovery already treats its own failures as "not accepted".
         // A later throw here must not drop the queue slot or invent a result.
         LOGGER.error("FastFormer kept an operation task of {} after a cancel fault",
            context.owner(), failure);
         return TaskCancellationResult.RECOVERY_BLOCKED;
      }
      if (handover != TaskCancellationResult.CANCELLED_BEFORE_WRITE
         && handover != TaskCancellationResult.ROLLBACK_STARTED) {
         return handover;
      }
      TASKS.remove(context.owner(), task);
      if (task instanceof ClientWorkspacePlacementTask workspaceTask) {
         // No writes: the draft may go again with a new transfer. Writes that reached
         // recovery report that state, so a retry cannot repeat the same work.
         FastPlaceNetwork.sendWorkspaceResult(
            context.server(),
            context.owner(),
            workspaceTask.dimension().location(),
            context.onlinePlayer(),
            workspaceTask.transferId(),
            false,
            handover == TaskCancellationResult.CANCELLED_BEFORE_WRITE,
            handover == TaskCancellationResult.ROLLBACK_STARTED,
            workspaceTask.failedPartIds(),
            workspaceTask.failedTargetPositions()
         );
      }
      return handover;
   }

   /**
    * Moves one task into the recovery path, or keeps both the task and the extracted
    * snapshot when the recovery path does not accept them.
    *
    * <p>The snapshot is extracted once. A retry reuses the held snapshot, because the
    * extraction already removed the before/after record from the task.
    */
   private static TaskCancellationResult transferToRecovery(WorldTaskContext context, WorldOperationTask task) {
      UUID owner = context.owner();
      try {
         WorldRecoverySnapshot held = HANDOVERS.get(owner);
         if (held == null) {
            held = task.stopAndTransferRecovery();
            HANDOVERS.put(owner, held);
         }
         TaskCancellationResult result = WorldHistoryManager.acceptTransferredRecovery(
            context,
            task.dimension(),
            held,
            task.journal(),
            () -> task.releaseAfterCancelledJournal(context),
            task::releaseMemoryReservation
         );
         if (result == TaskCancellationResult.CANCELLED_BEFORE_WRITE
            || result == TaskCancellationResult.ROLLBACK_STARTED) {
            HANDOVERS.remove(owner);
         }
         return result;
      } catch (RuntimeException | OutOfMemoryError failure) {
         // The acceptance boundary accepts a snapshot only by returning a handled result,
         // and the boundary performs no throwing work after a transfer. An exception
         // therefore means "not accepted": keep the task and the extracted snapshot.
         LOGGER.error("FastFormer kept a blocked operation task of {} with its recovery snapshot",
            owner, failure);
         return TaskCancellationResult.RECOVERY_BLOCKED;
      }
   }

   public static void tickWorld(net.minecraft.server.MinecraftServer server) {
      for (UUID owner : List.copyOf(TASKS.keySet())) {
         if (!PersistentRecoveryJournal.writesAllowed()) {
            break;
         }
         if (HANDOVERS.containsKey(owner)) {
            // A pending handover holds the only record of this task's writes. Ticking the
            // task would empty that record, so the handover owns the owner until it ends.
            continue;
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
      WorldTaskBudget budget = null;
      long batchStartedAt = 0L;
      boolean recoveryCreated = false;
      try {
      ServerLevel level = context.level(task.dimension());
      if (level == null) {
         task.markWorldUnloaded();
         task.releaseMemoryReservation();
         LOGGER.warn("FastFormer operation {} is waiting for unloaded dimension {} (phase={})",
            task.operationId(), task.dimension().location(), task.phaseName());
         context.actionBar(FastPlaceMessages.text("fastformer.message.history_dimension_failed"));
         return;
      }
      var reservation = task.reserveWorkingSet();
      if (reservation == MemoryReservationAttempt.RETRY) {
         context.actionBar(FastPlaceMessages.text("fastformer.message.world_write_waiting"));
         return;
      }
      if (reservation == MemoryReservationAttempt.ACQUIRED && !task.acquireLease(context)) {
         context.actionBar(FastPlaceMessages.text("fastformer.message.world_write_waiting"));
         return;
      }
      batchStartedAt = System.nanoTime();
      budget = WorldTaskBudget.forServerTick(
         task.memoryThrottled(), task.previousBatchCells(), task.previousBatchNanos()
      );
      OperationTaskResult result = reservation == MemoryReservationAttempt.REJECTED
         ? OperationTaskResult.MEMORY_UNSAFE : task.tick(
         context.withResume(() -> resumeTask(context, task)),
         level,
         budget
      );
      task.recordBatch(budget.consumed(), System.nanoTime() - batchStartedAt);
      if (result != OperationTaskResult.ACTIVE) {
         boolean retryableWorkspaceFailure = !task.hasWrites();
         boolean failureTransferred = result == OperationTaskResult.FAILED
            || result == OperationTaskResult.JOURNAL_FAILED
            || result == OperationTaskResult.MEMORY_UNSAFE
            || result == OperationTaskResult.EXCEEDED;
         if (failureTransferred) {
            recoveryCreated = settleFailedTask(context, task);
         } else if (task.hasWrites()) {
            JournalPreparation historyCommit = WorldHistoryManager.pollPreparedOperation(
               context, task.preparedBatch(), task.journal()
            );
            if (historyCommit == JournalPreparation.PENDING) {
               context.actionBar(FastPlaceMessages.text("fastformer.message.history_saving"));
               return;
            }
            if (historyCommit == JournalPreparation.FAILED) {
               result = OperationTaskResult.FAILED;
               failureTransferred = true;
               recoveryCreated = settleFailedTask(context, task);
            } else {
               task.markComplete();
               task.releaseCommittedTransactionState();
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
               context.server(),
               owner,
               workspaceTask.dimension().location(),
               context.onlinePlayer(),
               workspaceTask.transferId(),
               result == OperationTaskResult.COMPLETE || result == OperationTaskResult.EMPTY,
               retryableWorkspaceFailure,
               recoveryCreated,
               workspaceTask.failedPartIds(), workspaceTask.failedTargetPositions()
            );
         }
         if (result == OperationTaskResult.COMPLETE) {
            task.markComplete();
         }
         LOGGER.info("FastFormer operation {} finished with result {}: {}",
            task.operationId(), result, task.metricsSummary());
         if (!failureTransferred) {
            task.releaseMemoryReservation();
         }
         TASKS.remove(owner, task);
         context.actionBar(
            result == OperationTaskResult.COMPLETE
               ? FastPlaceMessages.text("fastformer.message.operation_complete")
               : result == OperationTaskResult.EMPTY
                  ? FastPlaceMessages.text("fastformer.message.operation_empty")
                  : result == OperationTaskResult.FAILED
                     ? operationFailureStatus(task, recoveryCreated)
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
         // Preserve the failed tick in latency metrics before transferring
         // ownership to recovery. This keeps adaptive scheduling evidence
         // available even when the task exits through an exception.
         if (budget != null) {
            task.recordBatch(budget.consumed(), System.nanoTime() - batchStartedAt);
         }
         boolean retryableWorkspaceFailure = !task.hasWrites();
         if (TASKS.remove(owner, task)) {
            recoveryCreated = settleFailedTask(context, task);
         }
         if (task instanceof ClientWorkspacePlacementTask workspaceTask) {
            FastPlaceNetwork.sendWorkspaceResult(
               context.server(),
               owner,
               workspaceTask.dimension().location(),
               context.onlinePlayer(),
               workspaceTask.transferId(), false, retryableWorkspaceFailure, recoveryCreated,
               workspaceTask.failedPartIds(), workspaceTask.failedTargetPositions()
            );
         }
         LOGGER.error("FastFormer operation task failed for {} and was transferred to recovery", owner, exception);
         LOGGER.error("FastFormer operation metrics: {}", task.metricsSummary());
         context.actionBar(operationFailureStatus(task, recoveryCreated));
      }
   }

   private static void enqueueTask(ServerPlayer player, WorldOperationTask task) {
      // A new task cannot own a snapshot extracted from an earlier one.
      HANDOVERS.remove(player.getUUID());
      TASKS.put(player.getUUID(), task);
      WorldTaskContext context = new WorldTaskContext(player.getServer(), player.getUUID());
      context.withResume(() -> resumeTask(context, task)).enqueueResume();
   }

   private static void resumeTask(WorldTaskContext context, WorldOperationTask task) {
      if (TASKS.get(context.owner()) == task
         && !HANDOVERS.containsKey(context.owner())
         && PersistentRecoveryJournal.writesAllowed()
         && !WorldHistoryManager.busy(context.owner())
         && !FastPlaceManager.taskActive(context.owner())) {
         tickTask(context);
      }
   }


   private static boolean settleFailedTask(WorldTaskContext context, WorldOperationTask task) {
      return WorldHistoryManager.acceptStoppedTask(context, task).recoveryCreated();
   }

   private static net.minecraft.network.chat.MutableComponent operationFailureStatus(
      WorldOperationTask task, boolean recoveryCreated
   ) {
      String key = recoveryCreated
         ? "fastformer.message.operation_failed_rollback"
         : "fastformer.message.operation_failed_no_recovery";
      return FastPlaceMessages.text(key, task.phaseName(), task.operationId(), task.metricsSummary());
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
      PARKED_SESSIONS.forget(player.getUUID());
   }

   /** Drops server-bound operation sessions/tasks before a world instance is replaced. */
   public static void clearServer() {
      for (WorldOperationTask task : TASKS.values()) {
         task.cancelJournalPreparation();
         task.releaseMemoryReservation();
      }
      TASKS.clear();
      HANDOVERS.clear();
      SESSIONS.clear();
      PARKED_SESSIONS.clear();
   }

   /**
    * Moves the operation session of a dimension aside. A selection is
    * dimension-bound because its coordinates are absolute world positions.
    */
   static void parkDimensionSession(UUID owner, ResourceKey<Level> dimension) {
      if (owner == null || dimension == null) {
         return;
      }
      PARKED_SESSIONS.park(owner, dimension, SESSIONS.remove(owner));
   }

   /** Restores the operation session that belongs to the target dimension. */
   static void restoreDimensionSession(UUID owner, ResourceKey<Level> dimension) {
      if (owner == null || dimension == null) {
         return;
      }
      OperationSession session = PARKED_SESSIONS.take(owner, dimension);
      if (session == null) {
         return;
      }
      if (SESSIONS.putIfAbsent(owner, session) != null) {
         PARKED_SESSIONS.park(owner, dimension, session);
      }
   }

   public static TaskCancellationResult cancelTask(ServerPlayer player) {
      return cancelTask(new WorldTaskContext(player.getServer(), player.getUUID()));
   }

   static TaskCancellationResult cancelTask(WorldTaskContext context) {
      // Accept first, then drop the queue slot. A refused or failed handover keeps both
      // the task and the extracted snapshot, so the only record of a partial write stays.
      // The shared settler also finishes the workspace ledger. Recovery never does that.
      return settleCancelledTask(context);
   }

   static void addTaskForTest(UUID owner, WorldOperationTask task) {
      TASKS.put(owner, task);
   }

   public static boolean undoLast(ServerPlayer player) {
      return WorldHistoryManager.requestUndo(player, 1);
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

   /**
    * True when the running task of one owner is the given workspace transfer.
    *
    * <p>The owner alone is not enough. A player can start a new task while an older
    * transfer is still recorded, so a query must match the transfer id.</p>
    */
   public static boolean transferActive(UUID owner, UUID transferId) {
      if (owner == null || transferId == null) {
         return false;
      }
      WorldOperationTask task = TASKS.get(owner);
      return task instanceof io.github.fastformer.fastplace.task.ClientWorkspacePlacementTask workspace
         && workspace.transferId().equals(transferId);
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
