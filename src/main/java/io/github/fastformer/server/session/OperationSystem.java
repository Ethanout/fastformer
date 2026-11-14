package io.github.fastformer.server.session;

import io.github.fastformer.common.geometry.gizmo.AxisGizmo;

import io.github.fastformer.core.math.Axis;


import io.github.fastformer.server.history.HistorySystem;

import io.github.fastformer.server.gate.ServerOperationGates;

import io.github.fastformer.server.selection.ClientPointValidator;
import com.mojang.logging.LogUtils;
import io.github.fastformer.common.selection.geometry.OperationGeometry;
import io.github.fastformer.common.placement.PlacementUpdateMode;
import io.github.fastformer.server.recovery.RecoverySystem;
import io.github.fastformer.common.selection.OperationPointDragConstraint;
import io.github.fastformer.common.selection.OperationSelectionMode;
import io.github.fastformer.common.selection.OperationSelectionVolume;
import io.github.fastformer.server.session.slot.PlayerSessions;
import io.github.fastformer.common.selection.session.OperationSession;
import io.github.fastformer.server.settings.FastPlaceSettings;
import io.github.fastformer.server.task.block.SubmittedWorkspaceTask;
import io.github.fastformer.server.task.block.SelectionOperationTask;
import io.github.fastformer.server.feedback.PlayerMessages;
import io.github.fastformer.common.world.memory.WorldOperationMemory;
import io.github.fastformer.server.sync.PreviewSync;
import io.github.fastformer.server.input.ServerInputActions;
import io.github.fastformer.common.workspace.model.OperationConflictMode;
import io.github.fastformer.common.workspace.submission.OperationWorkspacePlan;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;

public final class OperationSystem implements io.github.fastformer.server.mode.ServerEditor<OperationSession> {
   private static final Logger LOGGER = LogUtils.getLogger();
   final PlayerSessions<OperationSession> sessions;
   private final SessionSystem runtime;
   OperationSystem(SessionSystem runtime) {
      this.runtime = runtime;
      sessions = new PlayerSessions<>(net.minecraft.resources.ResourceLocation.parse("fastformer:selection"),
         session -> io.github.fastformer.common.mode.BuiltinModes.SELECTION, runtime.eventStream()::publish);
   }

   public static OperationSystem of(ServerPlayer player) { return of(player.getServer()); }
   public static OperationSystem of(net.minecraft.server.MinecraftServer server) { return SessionSystem.of(server).operation; }

   private OperationSession createSession(ServerPlayer player) {
      FastPlaceSettings settings = FastPlaceSettings.load(player);
      return new OperationSession(settings.operationSelectionMode(), settings.sessionUndoHistoryLimit());
   }

   private OperationSession getOrOpen(ServerPlayer player) {
      return session(player).orElseGet(() -> runtime.modes().selection().open(player, createSession(player)));
   }

   @Override public void activate(ServerPlayer player, OperationSession session) {
      sessions.activate(player.getUUID(), session);
   }

   public void updateSessionHistoryLimit(ServerPlayer player, int historyLimit) {
      OperationSession session = sessions.active(player.getUUID());
      if (session != null) {
         session.updateHistoryLimit(historyLimit);
      }
   }

   private boolean recordEdit(OperationSession session, BooleanSupplier mutation) {
      session.commitEdit();
      session.beginEdit();
      boolean changed = mutation.getAsBoolean();
      session.commitEdit();
      return changed;
   }

   private void rememberSelectionMode(ServerPlayer player, OperationSession session) {
      FastPlaceSettings settings = FastPlaceSettings.load(player);
      if (settings.operationSelectionMode() != session.selectionMode()) {
         settings.setOperationSelectionMode(player, session.selectionMode());
      }
   }

   public Optional<OperationSession> session(ServerPlayer player) {
      return Optional.ofNullable(sessions.active(player.getUUID()));
   }

   public boolean active(ServerPlayer player) {
      return sessions.hasActive(player.getUUID());
   }

   public boolean needsSecond(ServerPlayer player) {
      OperationSession session = sessions.active(player.getUUID());
      return session != null && session.first() != null && session.second() == null;
   }

   public boolean needsSelectionPoint(ServerPlayer player) {
      OperationSession session = sessions.active(player.getUUID());
      return session != null && session.needsSelectionPoint();
   }

   public boolean selectionReady(ServerPlayer player) {
      OperationSession session = sessions.active(player.getUUID());
      return session != null && session.selectionReady();
   }

   public OperationSession start(ServerPlayer player, BlockPos point) {
      return startFirst(player, point);
   }

   public OperationSession startFirst(ServerPlayer player, BlockPos point) {
      OperationSession session = getOrOpen(player);
      recordEdit(session, () -> {
         session.setFirst(point);
         return true;
      });
      PlayerMessages.actionBar(player, PlayerMessages.text("fastformer.message.operation_point1"));
      PreviewSync.syncOperation(player, session);
      return session;
   }

   public OperationSession startSecond(ServerPlayer player, BlockPos point) {
      OperationSession session = getOrOpen(player);
      recordEdit(session, () -> {
         session.setSecond(point);
         return true;
      });
      PlayerMessages.actionBar(player, PlayerMessages.text("fastformer.message.operation_point2"));
      PreviewSync.syncOperation(player, session);
      return session;
   }

   public void setSecond(ServerPlayer player, BlockPos point) {
      OperationSession session = sessions.active(player.getUUID());
      if (session != null) {
         recordEdit(session, () -> {
            session.setSecond(point);
            return true;
         });
         PlayerMessages.actionBar(player, PlayerMessages.text("fastformer.message.operation_point2"));
         PreviewSync.syncOperation(player, session);
      }
   }

   public void addExtraPoint(ServerPlayer player, BlockPos point) {
      OperationSession session = sessions.active(player.getUUID());
      if (session != null && recordEdit(session, () -> session.addExtraPoint(point))) {
         PlayerMessages.actionBar(player, PlayerMessages.text("fastformer.message.operation_extra_point", session.extraPoints().size()));
         PreviewSync.syncOperation(player, session);
      }
   }

   public void expandTo(ServerPlayer player, BlockPos point) {
      OperationSession session = sessions.active(player.getUUID());
      if (session != null && recordEdit(session, () -> session.expandTo(point))) {
         PreviewSync.syncOperation(player, session);
      }
   }

   public boolean confirmSelection(ServerPlayer player) {
      OperationSession session = sessions.active(player.getUUID());
      if (session == null || !recordEdit(session, session::confirmSelection)) {
         PlayerMessages.actionBar(player, PlayerMessages.text("fastformer.message.operation_need_points"));
         return false;
      }
      PreviewSync.syncOperation(player, session);
      PlayerMessages.actionBar(player, PlayerMessages.text("fastformer.message.operation_selection_confirmed"));
      return true;
   }

   public boolean closePrismBase(ServerPlayer player) {
      OperationSession session = sessions.active(player.getUUID());
      if (session == null || !recordEdit(session, session::closePrismBase)) {
         return false;
      }
      PreviewSync.syncOperation(player, session);
      return true;
   }

   public void selectPoint(ServerPlayer player, int index) {
      OperationSession session = sessions.active(player.getUUID());
      if (session != null && session.selectPoint(index)) {
         PreviewSync.syncOperation(player, session);
      }
   }

   public void removePoint(ServerPlayer player, int index) {
      OperationSession session = sessions.active(player.getUUID());
      if (session != null && recordEdit(session, () -> session.removePoint(index))) {
         PreviewSync.syncOperation(player, session);
      }
   }

   public void dragPoint(
      ServerPlayer player,
      int index,
      BlockPos target,
      OperationPointDragConstraint constraint,
      boolean finish
   ) {
      OperationSession session = sessions.active(player.getUUID());
      if (session == null) {
         return;
      }
      session.beginEdit();
      if (session.dragPointTo(index, target, constraint, finish,
         point -> ClientPointValidator.accepts(player, point))) {
         PreviewSync.syncOperation(player, session);
      }
      if (finish) {
         session.commitEdit();
      }
   }

   public void insertPoint(ServerPlayer player, int insertionIndex, BlockPos point) {
      OperationSession session = sessions.active(player.getUUID());
      if (session != null && recordEdit(session, () -> session.insertPoint(insertionIndex, point))) {
         PreviewSync.syncOperation(player, session);
      }
   }

   public void addSelectionPoint(ServerPlayer player, BlockPos point) {
      OperationSession session = sessions.active(player.getUUID());
      if (session != null && recordEdit(session, () -> session.addSelectionPoint(point))) {
         PlayerMessages.actionBar(
            player,
            PlayerMessages.text("fastformer.message.operation_selection_point", session.points().size(), session.selectionMode().requiredPoints())
         );
         PreviewSync.syncOperation(player, session);
      }
   }

   public void undo(ServerPlayer player) {
      OperationSession session = sessions.active(player.getUUID());
      boolean changed = session != null && (session.adjustmentStarted()
         ? session.undoAdjustment()
         : session.undoStep());
      if (changed) {
         rememberSelectionMode(player, session);
         PreviewSync.syncOperation(player, session);
      }
   }

   public void redo(ServerPlayer player) {
      OperationSession session = sessions.active(player.getUUID());
      if (session != null && session.redoStep()) {
         rememberSelectionMode(player, session);
         PreviewSync.syncOperation(player, session);
      }
   }

   public void sync(ServerPlayer player) {
      OperationSession session = sessions.active(player.getUUID());
      if (session != null) {
         PreviewSync.syncOperation(player, session);
      } else {
         PreviewSync.syncSettings(player);
      }
   }

   public void extend(ServerPlayer player, int axis, boolean positive, int steps, boolean finish) {
      OperationSession session = sessions.active(player.getUUID());
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
         PreviewSync.syncOperation(player, session);
      }
   }

   public void cycleMode(ServerPlayer player) {
      OperationSession session = sessions.active(player.getUUID());
      if (session == null) {
         FastPlaceSettings settings = FastPlaceSettings.load(player);
         // Interactive tool choice belongs to the client. Server point sessions remain cuboids.
         settings.setOperationSelectionMode(player, OperationSelectionMode.CUBOID);
         PreviewSync.syncSettings(player);
         return;
      }
      if (session.operationReady()) {
         session.cycleStageMode();
      } else {
         recordEdit(session, () -> {
            session.cycleSelectionMode();
            return true;
         });
         rememberSelectionMode(player, session);
      }
      PreviewSync.syncOperation(player, session);
   }

   public boolean setSelectionMode(ServerPlayer player, OperationSelectionMode mode) {
      OperationSession session = sessions.active(player.getUUID());
      if (session == null || mode == OperationSelectionMode.CONVEX_HULL) {
         return false;
      }
      recordEdit(session, () -> {
         session.setSelectionMode(mode);
         return true;
      });
      rememberSelectionMode(player, session);
      PreviewSync.syncOperation(player, session);
      return true;
   }

   public boolean setConflictMode(ServerPlayer player, OperationConflictMode mode) {
      OperationSession session = sessions.active(player.getUUID());
      if (session == null) {
         return false;
      }
      session.setConflictMode(mode);
      PlayerMessages.actionBar(player, PlayerMessages.text("fastformer.message.operation_conflict_mode", PlayerMessages.text(mode)));
      return true;
   }

   public void scroll(ServerPlayer player, int steps) {
      OperationSession session = sessions.active(player.getUUID());
      if (session == null || !session.operationReady() || steps == 0) {
         return;
      }
      BlockPos axisStep = OperationGeometry.viewAxisStep(player.getViewVector(1.0F), steps);
      int axis = axisStep.getX() != 0 ? 0 : axisStep.getY() != 0 ? 1 : 2;
      int amount = axis == 0 ? axisStep.getX() : axis == 1 ? axisStep.getY() : axisStep.getZ();
      var operation = io.github.fastformer.common.geometry.gizmo.AxisGizmo.Operation.MOVE;
      session.beginTransform(operation, Axis.values()[axis], Integer.signum(amount));
      session.updateTransform(Math.abs(amount));
      session.finishTransform();
      PreviewSync.syncOperation(player, session);
   }

   public void adjustTransform(
      ServerPlayer player,
      io.github.fastformer.common.geometry.gizmo.AxisGizmo.Operation operation,
      Axis axis,
      int direction,
      int totalSteps,
      boolean finish
   ) {
      OperationSession session = sessions.active(player.getUUID());
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
         PreviewSync.syncOperation(player, session);
      }
   }

   public boolean applyConfirmed(ServerPlayer player, boolean copy) {
      OperationSession session = sessions.active(player.getUUID());
      if (session == null || !session.operationReady() || !session.adjustmentStarted()) {
         return false;
      }
      if (!session.stageMode().executable()) {
         PlayerMessages.actionBar(player, PlayerMessages.text("fastformer.message.operation_mode_not_implemented"));
         return false;
      }
      return apply(player, copy);
   }

   public boolean apply(ServerPlayer player, boolean copy) {
      OperationSession session = sessions.active(player.getUUID());
      if (session == null || !session.selectionReady()) {
         PlayerMessages.actionBar(player, PlayerMessages.text("fastformer.message.operation_need_points"));
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
         PlayerMessages.actionBar(player, PlayerMessages.text("fastformer.message.operation_too_large", maxPlacement));
         return false;
      }
      if (!WorldOperationMemory.snapshotAdmission(volume, 0L).allowed()) {
         PlayerMessages.actionBar(player, PlayerMessages.text("fastformer.message.operation_memory_unsafe"));
         return false;
      }
      if (operationBusy(player)) {
         PlayerMessages.actionBar(player, PlayerMessages.text("fastformer.message.operation_task_running"));
         return false;
      }
      runtime.operationTasks().enqueue(
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
      PlayerMessages.actionBar(player, PlayerMessages.text("fastformer.message.operation_scanning"));
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
   public io.github.fastformer.common.workspace.submission.OperationSubmissionOutcome recordedOutcome(
      ServerPlayer player, UUID transferId
   ) {
      if (player == null || transferId == null) {
         return io.github.fastformer.common.workspace.submission.OperationSubmissionOutcome.UNKNOWN;
      }
      return runtime.submissions().outcomeFor(
         player.getUUID(), player.serverLevel().dimension().location(), transferId
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
   public io.github.fastformer.common.workspace.submission.WorkspaceAdmission applyWorkspace(
      ServerPlayer player, UUID transferId, OperationWorkspacePlan plan
   ) {
      if (transferId == null || plan == null || plan.parts().isEmpty()) {
         return io.github.fastformer.common.workspace.submission.WorkspaceAdmission.rejected();
      }
      net.minecraft.resources.ResourceLocation dimension = player.serverLevel().dimension().location();
      var recorded = runtime.submissions().outcomeFor(
         player.getUUID(), dimension, transferId
      );
      if (recorded != io.github.fastformer.common.workspace.submission.OperationSubmissionOutcome.UNKNOWN) {
         // This transfer already reached the server. A replay can arrive when a client
         // retries an upload that the server already accepted. Running the work again
         // would write the same blocks a second time, so the server starts no new task and
         // reports the state that it already holds.
         return io.github.fastformer.common.workspace.submission.WorkspaceAdmission.replayed(recorded);
      }
      if (!RecoverySystem.of(player.getServer()).writeGate().writesAllowed()) {
         // The write gate is closed for this server session, because a disk
         // journal could not be recovered safely. Refuse before the ledger begin so no
         // record claims this transfer was accepted. A later retry uses a new transfer.
         PlayerMessages.actionBar(player, PlayerMessages.text("fastformer.message.recovery_journal_blocked"));
         return io.github.fastformer.common.workspace.submission.WorkspaceAdmission.rejected();
      }
      int maxPlacement = FastPlaceSettings.load(player).maxPlacement();
      if (operationBusy(player)) {
         PlayerMessages.actionBar(player, PlayerMessages.text("fastformer.message.operation_task_running"));
         return io.github.fastformer.common.workspace.submission.WorkspaceAdmission.rejected();
      }
      SubmittedWorkspaceTask workspaceTask = new SubmittedWorkspaceTask(
         transferId,
         plan,
         FastPlaceSettings.load(player).placementUpdateMode(),
         maxPlacement,
         player.serverLevel().dimension()
      );
      // The task enters the queue first. The world tick loop walks tasks, so a task that
      // reaches the map runs even when scheduling its first resume fails. Every step after
      // this point reports that fact, and none of them may undo it.
      runtime.operationTasks().admit(player.getUUID(), workspaceTask);
      recordAdmission(player.getUUID(), dimension, transferId);
      runtime.operationTasks().scheduleResume(player, workspaceTask);
      reportAdmissionFeedback(player);
      return io.github.fastformer.common.workspace.submission.WorkspaceAdmission.newQueued();
   }

   /**
    * Records that one transfer was accepted and now runs.
    *
    * <p>The task is already in the queue here, so a failed record must not travel out of
    * {@link #applyWorkspace}. The task reports its own result when it settles, and that
    * report writes the outcome through the ledger. A query in the meantime answers
    * {@code UNKNOWN}, which keeps the client draft without a resend.</p>
    */
   private void recordAdmission(
      UUID owner,
      net.minecraft.resources.ResourceLocation dimension, UUID transferId
   ) {
      try {
         runtime.submissions().begin(
            owner, dimension, transferId
         );
      } catch (RuntimeException exception) {
         LOGGER.warn("FastFormer queued a workspace submission but could not record it", exception);
      }
   }

   /**
    * Reports an accepted admission to the player.
    *
    * <p>The admission is already complete here: the task is queued and the ledger holds it.
    * A fault in the feedback must not travel out of {@link #applyWorkspace}, because a
    * caller that catches it would report a failure for work that is running.</p>
    */
   private void reportAdmissionFeedback(ServerPlayer player) {
      try {
         admissionFeedback.accept(player);
      } catch (RuntimeException exception) {
         LOGGER.warn("FastFormer queued a workspace submission but could not report it", exception);
      }
   }

   /** Announces an accepted admission. A test replaces this to force a feedback fault. */
   java.util.function.Consumer<ServerPlayer> admissionFeedback = this::announceAdmission;

   private void announceAdmission(ServerPlayer player) {
      cancel(player);
      PlayerMessages.actionBar(player, PlayerMessages.text("fastformer.message.operation_scanning"));
   }

   public void cancel(ServerPlayer player) {
      ServerInputActions.endOperationGestures(player);
      OperationSession removed = sessions.end(player.getUUID());
      if (removed != null) {
         rememberSelectionMode(player, removed);
         PreviewSync.syncSettings(player);
      }
   }

   public void remove(ServerPlayer player) {
      OperationSession removed = sessions.end(player.getUUID());
      if (removed != null) {
         rememberSelectionMode(player, removed);
      }
      sessions.discard(player.getUUID());
   }

   /** Drops server-bound operation sessions/tasks before a world instance is replaced. */
   @Override public void onDiscard() { sessions.clear(); }

   /**
    * Moves the operation session of a dimension aside. A selection is
    * dimension-bound because its coordinates are absolute world positions.
    */
   @Override public void parkDimensionSession(UUID owner, ResourceKey<Level> dimension) {
      sessions.park(owner, dimension);
   }

   /** Restores the operation session that belongs to the target dimension. */
   @Override public void restoreDimensionSession(UUID owner, ResourceKey<Level> dimension) {
      sessions.restore(owner, dimension);
   }

   public boolean undoLast(ServerPlayer player) {
      return HistorySystem.of(player).requestUndo(player, 1);
   }

   private boolean operationBusy(ServerPlayer player) {
      return !ServerOperationGates.START_WRITE.test(player).allowed();
   }

   private long scanCells(AABB bounds) {
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
