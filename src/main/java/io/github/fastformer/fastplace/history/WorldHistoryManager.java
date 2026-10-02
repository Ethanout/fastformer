package io.github.fastformer.fastplace.history;

import com.mojang.logging.LogUtils;
import io.github.fastformer.fastplace.recovery.JournalPreparation;
import io.github.fastformer.fastplace.recovery.PersistentRecoveryJournal;
import io.github.fastformer.fastplace.recovery.WorldRecoverySnapshot;
import io.github.fastformer.fastplace.settings.FastPlaceSettings;
import io.github.fastformer.fastplace.task.PlacementTask;
import io.github.fastformer.fastplace.task.TaskCancellationResult;
import io.github.fastformer.fastplace.task.WorldOperationTask;
import io.github.fastformer.fastplace.text.FastPlaceMessages;
import io.github.fastformer.fastplace.world.WorldTaskBudget;
import io.github.fastformer.fastplace.world.WorldTaskContext;
import io.github.fastformer.fastplace.world.WorldWriteCoordinator;
import io.github.fastformer.fastplace.world.memory.WorldOperationMemory;
import io.github.fastformer.fastplace.world.snapshot.ReversibleBlockSnapshot;
import io.github.fastformer.server.input.ServerInputDispatcher;
import io.github.fastformer.server.session.FastPlaceManager;
import io.github.fastformer.server.session.GeometryManager;
import io.github.fastformer.server.session.OperationManager;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;

/**
 * Owns the world-operation history for a player.  Building and operation
 * workflows submit completed batches here; neither workflow owns an undo
 * stack anymore.  The manager also serializes undo/redo/recovery writes and
 * performs an expected-state check before every write.
 */
public final class WorldHistoryManager {
   /** Restores a missing history index entry after a committed journal is found at startup. */
   public static java.util.concurrent.CompletableFuture<Void> reconcileCommittedJournal(
      net.minecraft.server.MinecraftServer server, java.util.UUID owner, java.util.UUID operation
   ) {
      return WorldHistoryPersistence.reconcileCommittedJournal(server, owner, operation);
   }

   private static final Logger LOGGER = LogUtils.getLogger();
   public static final int DEFAULT_LIMIT = 200;
   public static final int MAX_LIMIT = 800;
   /** A second budget prevents 800 large batches from retaining unbounded NBT. */
   private static final long MAX_BYTES_PER_PLAYER = 256L * 1024L * 1024L;
   private static final long HISTORY_PAGE_BYTES = 64L * 1024L * 1024L;
   /** Global guard against many disconnected players retaining large histories. */
   static final long MAX_BYTES_GLOBAL = 1024L * 1024L * 1024L;
   static final int MAX_IDLE_OWNERS = 128;
   private static final Map<UUID, OwnerState> OWNERS = new LinkedHashMap<>();


   private static final HistoryReplayTask.HistoryAccess HISTORY_ACCESS = new HistoryReplayTask.HistoryAccess() {
      @Override
      public HistoryReplayTask.PageState requestPage(WorldTaskContext context, boolean undo, int remaining) {
         OwnerState owner = ownerState(context.owner());
         if (owner.scheduler().hasPendingPageLoad()) {
            return HistoryReplayTask.PageState.WAITING;
         }
         if (owner.scheduler().failedPagePlan().isPresent()) {
            owner.scheduler().clearFailedPagePlan();
            return HistoryReplayTask.PageState.FAILED;
         }
         if (remaining > 0) {
            Optional<HistoryPageLoadPlan> nextPage = HistoryPageLoadPlan.create(
               undo, remaining, List.of(), owner.historyLimit, historyOrder(owner)
            );
            if (nextPage.isPresent()) {
               HistoryPageLoadPlan plan = nextPage.orElseThrow();
               owner.scheduler().startPageLoad(plan, WorldHistoryPersistence.loadPage(
                  context.server(), context.owner(), plan.operationIds(), plan.operationIds().size(), HISTORY_PAGE_BYTES
               ));
               return HistoryReplayTask.PageState.WAITING;
            }
         }
         return HistoryReplayTask.PageState.EXHAUSTED;
      }

      @Override
      public void commit(WorldTaskContext context, HistoryMemoryCache history, boolean undo) {
         WorldChangeBatch committed = history.commit(undo);
         OwnerState owner = ownerState(context.owner());
         historyOrder(owner).commit(undo, committed.operationId());
         ServerPlayer player = context.onlinePlayer();
         if (player == null) {
            trim(owner.historyLimit, history);
         } else {
            trimSafely(player, history);
         }
         historyOrder(owner).trim(owner.historyLimit);
         scheduleIndex(context.server(), context.owner(), owner, history);
      }
   };

   private WorldHistoryManager() {
   }

   public static boolean record(ServerPlayer player, ArrayDeque<ReversibleBlockSnapshot> changes) {
      return record(player, player.serverLevel(), changes);
   }

   public static boolean record(ServerPlayer player, ServerLevel level, ArrayDeque<ReversibleBlockSnapshot> changes) {
      if (changes == null || changes.isEmpty()) {
         return false;
      }
      Optional<WorldChangeBatch> captured;
      try {
         captured = WorldChangeBatch.capture(level, changes);
      } catch (RuntimeException | OutOfMemoryError exception) {
         ownerState(player.getUUID()).pendingRecords.addLast(pendingRecord(level.dimension(), changes, Map.of()));
         return true;
      }
      if (captured.isEmpty()) {
         return false;
      }
      addBatch(player, captured.orElseThrow());
      return true;
   }

   /** Records a completed write using snapshots only; the dimension may be unloaded. */
   public static boolean record(
      ServerPlayer player,
      ResourceKey<Level> dimension,
      ArrayDeque<ReversibleBlockSnapshot> changes,
      Map<BlockPos, ReversibleBlockSnapshot> after
   ) {
      if (dimension == null || changes == null || changes.isEmpty()) {
         return false;
      }
      // Task owners detach their maps before calling this boundary. Avoid a
      // second multi-million-entry copy during large completion/recovery.
      Map<BlockPos, ReversibleBlockSnapshot> safeAfter = after == null ? Map.of() : after;
      ServerLevel level = player.getServer() == null ? null : player.getServer().getLevel(dimension);
      Optional<WorldChangeBatch> captured = Optional.empty();
      try {
         captured = WorldChangeBatch.capturePairsByPos(dimension, changes, safeAfter);
      } catch (RuntimeException | OutOfMemoryError ignored) {
         // Incomplete after maps are completed from the live level below.
      }
      if (captured.isEmpty() && level != null) {
         try {
            captured = WorldChangeBatch.capture(level, changes);
         } catch (RuntimeException | OutOfMemoryError ignored) {
            captured = Optional.empty();
         }
      }
      if (captured.isPresent()) {
         addBatch(player, captured.orElseThrow());
         return true;
      }
      if (!completeAfter(changes, safeAfter) || (level != null && !allBefore(level, changes))) {
         ownerState(player.getUUID()).pendingRecords.addLast(pendingRecord(dimension, changes, safeAfter));
         return true;
      }
      return false;
   }

   public static boolean record(
      ServerPlayer player,
      ServerLevel level,
      ArrayDeque<ReversibleBlockSnapshot> changes,
      Map<BlockPos, ReversibleBlockSnapshot> after
   ) {
      if (level == null || changes == null || changes.isEmpty()) {
         return false;
      }
      if (after == null || after.isEmpty()) {
         return record(player, level.dimension(), changes, Map.of());
      }
      Optional<WorldChangeBatch> captured;
      try {
         captured = WorldChangeBatch.capturePairsByPos(level, changes, after);
      } catch (RuntimeException exception) {
         return record(player, level.dimension(), changes, after);
      }
      if (captured.isEmpty()) {
         return record(player, level.dimension(), changes, after);
      }
      WorldChangeBatch batch = captured.orElseThrow();
      addBatch(player, batch);
      return true;
   }

   public static boolean commitPreparedOperation(
      ServerPlayer player,
      Optional<WorldChangeBatch> captured,
      PersistentRecoveryJournal journal
   ) {
      return commitPreparedOperation(new WorldTaskContext(player.getServer(), player.getUUID()), captured, journal);
   }

   public static boolean commitPreparedOperation(
      WorldTaskContext context,
      Optional<WorldChangeBatch> captured,
      PersistentRecoveryJournal journal
   ) {
      if (captured == null || captured.isEmpty()) {
         return journal == null || journal.resolveAfterRollback(context.server());
      }
      if (journal != null && !journal.completeFinalized()) {
         return false;
      }
      addBatch(context, captured.orElseThrow());
      return true;
   }

   public static JournalPreparation pollPreparedOperation(
      WorldTaskContext context,
      Optional<WorldChangeBatch> captured,
      PersistentRecoveryJournal journal
   ) {
      if (captured == null || captured.isEmpty()) {
         return journal == null || journal.resolveAfterRollback(context.server())
            ? JournalPreparation.READY : JournalPreparation.FAILED;
      }
      WorldChangeBatch batch = captured.orElseThrow();
      if (batch.operationId() == null) batch = batch.withOperationId(UUID.randomUUID());
      OwnerState owner = ownerState(context.owner());
      DurableCommit pending = owner.durableCommit;
      if (pending == null) {
         owner.durableCommit = new DurableCommit(
            batch,
            journal,
            WorldHistoryPersistence.publishBatchOnly(context.server(), context.owner(), batch)
         );
         return JournalPreparation.PENDING;
      }
      if (!pending.batch.operationId().equals(batch.operationId()) || pending.journal != journal) {
         return JournalPreparation.FAILED;
      }
      if (!pending.future.isDone()) {
         return JournalPreparation.PENDING;
      }
      if (pending.phase == DurableCommitPhase.BATCH) {
         if (futureFailed(pending.future)) {
            owner.durableCommit = null;
            return JournalPreparation.FAILED;
         }
         if (journal != null && !journal.sealFinalizedForHistory()) {
            owner.durableCommit = null;
            return JournalPreparation.FAILED;
         }
         HistoryOrderCatalog prospective = historyOrder(owner).copy();
         prospective.addNew(batch.operationId());
         prospective.trim(owner.historyLimit);
         pending.phase = DurableCommitPhase.INDEX;
         pending.future = WorldHistoryPersistence.publishIndex(
            context.server(), context.owner(), prospective.order(true), prospective.order(false)
         );
         return JournalPreparation.PENDING;
      }
      if (futureFailed(pending.future)) {
         if (pending.retryTicks-- > 0) return JournalPreparation.PENDING;
         pending.retryTicks = 100;
         HistoryOrderCatalog prospective = historyOrder(owner).copy();
         prospective.addNew(batch.operationId());
         prospective.trim(owner.historyLimit);
         pending.future = WorldHistoryPersistence.publishIndex(
            context.server(), context.owner(), prospective.order(true), prospective.order(false)
         );
         return JournalPreparation.PENDING;
      }
      addBatchInMemory(owner, batch);
      WorldHistoryEvents.send(context, batch, io.github.fastformer.network.payload.world.WorldHistoryEventPayload.Kind.RECORD, true);
      if (journal != null) journal.historyPublished();
      owner.durableCommit = null;
      return JournalPreparation.READY;
   }

   private static boolean futureFailed(CompletableFuture<Void> future) {
      try {
         future.join();
         return false;
      } catch (RuntimeException failure) {
         return true;
      }
   }

   private static void addBatchInMemory(OwnerState owner, WorldChangeBatch batch) {
      HistoryMemoryCache history = owner.history();
      history.addNew(batch);
      trim(owner.historyLimit, history);
      HistoryOrderCatalog order = historyOrder(owner);
      order.addNew(batch.operationId());
      order.trim(owner.historyLimit);
   }

   private static void addBatch(ServerPlayer player, WorldChangeBatch batch) {
      addBatch(new WorldTaskContext(player.getServer(), player.getUUID()), batch);
   }

   private static void addBatch(WorldTaskContext context, WorldChangeBatch batch) {
      if (batch.operationId() == null) batch = batch.withOperationId(UUID.randomUUID());
      OwnerState owner = ownerState(context.owner());
      HistoryMemoryCache history = owner.history();
      history.addNew(batch);
      ServerPlayer player = context.onlinePlayer();
      // Keep all batches in memory while an earlier save is unresolved. The
      // recovery snapshot must still contain the failed batch and any newer work.
      if (owner.pendingPersistence == 0 && !owner.persistenceDirty) {
         if (player == null) {
            trim(owner.historyLimit, history);
         } else {
            trimSafely(player, history);
         }
      }
      scheduleNewBatch(context.server(), context.owner(), owner, batch, history);
      WorldHistoryEvents.send(context, batch, io.github.fastformer.network.payload.world.WorldHistoryEventPayload.Kind.RECORD, true);
   }

   public static boolean requestUndo(ServerPlayer player, int count) {
      return request(player, true, count);
   }

   public static boolean respondToConflict(ServerPlayer player,
      io.github.fastformer.network.payload.world.HistoryConflictResponsePayload response) {
      if (response.choice() != io.github.fastformer.network.payload.world.HistoryConflictResponsePayload.Choice.CANCEL
         && !ServerInputDispatcher.canOperate(player)) return false;
      var owner = OWNERS.get(player.getUUID());
      return owner != null && owner.active != null && owner.active.respondToConflict(
         new WorldTaskContext(player.getServer(), player.getUUID()), response.token(), response.choice());
   }

   public static void resumeDiskCleanup(MinecraftServer server) {
      WorldHistoryPersistence.resumeCleanup(server, java.util.Set.copyOf(OWNERS.keySet()));
   }

   public static void awaitDiskWritesOnShutdown(MinecraftServer server) {
      WorldHistoryPersistence.awaitShutdown(server, saveDirtyHistoriesOnShutdown(server));
   }

   static CompletableFuture<Void> saveDirtyHistoriesOnShutdown(MinecraftServer server) {
      java.util.List<CompletableFuture<Void>> saves = new java.util.ArrayList<>();
      for (var entry : OWNERS.entrySet()) {
         OwnerState owner = entry.getValue();
         if (owner.history == null || (!owner.persistenceDirty && owner.pendingPersistence == 0)) continue;
         // Capture before clearServer releases the only in-memory undo/redo stacks.
         HistoryOrderCatalog order = historyOrder(owner);
         saves.add(WorldHistoryPersistence.publishSnapshot(server, entry.getKey(),
            owner.history.snapshot(true), owner.history.snapshot(false),
            order.order(true), order.order(false)));
      }
      return CompletableFuture.allOf(saves.toArray(CompletableFuture[]::new));
   }

   public static boolean requestRedo(ServerPlayer player, int count) {
      return request(player, false, count);
   }

   public static void deferUndoAfterRecovery(ServerPlayer player, int count) {
      if (player == null || count <= 0) {
         return;
      }
      deferUndoAfterRecovery(player.getUUID(), count);
   }

   static void deferUndoAfterRecovery(UUID id, int count) {
      if (id == null || count <= 0) {
         return;
      }
      OwnerState owner = ownerState(id);
      owner.deferredUndo = Math.min(MAX_LIMIT, owner.deferredUndo + Math.clamp(count, 1, MAX_LIMIT));
   }

   public static int remainingAfterUncommittedUndo(int requestedCount) {
      return Math.max(0, Math.min(MAX_LIMIT, requestedCount) - 1);
   }

   private static boolean request(ServerPlayer player, boolean undo, int count) {
      UUID id = player.getUUID();
      OwnerState owner = ownerState(id);
      int requested = Math.clamp(count, 1, MAX_LIMIT);
      if (owner.historyLoadFailed) {
         if (owner.historyLoad == null && ServerInputDispatcher.canOperate(player)) {
            startHistorySnapshotLoad(player, owner);
            FastPlaceMessages.actionBar(player, FastPlaceMessages.text("fastformer.message.history_loading_older"));
         } else {
            FastPlaceMessages.actionBar(player, FastPlaceMessages.text("fastformer.message.history_load_failed"));
         }
         return false;
      }
      if (!ServerInputDispatcher.canOperate(player)
         || busy(player)
         || FastPlaceManager.taskActive(player)
         || OperationManager.taskActive(player)
         || FastPlaceManager.active(player)
         || OperationManager.active(player)
         || GeometryManager.active(player)) {
         FastPlaceMessages.actionBar(player, FastPlaceMessages.text("fastformer.message.history_wait_task"));
         return false;
      }
      HistoryMemoryCache history = owner.history;
      if (history == null) {
         FastPlaceMessages.actionBar(player, FastPlaceMessages.text(
            undo ? "fastformer.message.history_no_undo" : "fastformer.message.history_no_redo"
         ));
         return false;
      }
      owner.scheduler().clearFailedPagePlan();
      Optional<HistoryPageLoadPlan> pagePlan = history.size(undo) == 0
         ? HistoryPageLoadPlan.create(undo, requested, history.operationIds(undo), owner.historyLimit, historyOrder(owner))
         : Optional.empty();
      if (pagePlan.isPresent()) {
         startHistoryPageLoad(player, owner, pagePlan.orElseThrow());
         return true;
      }
      if (history.size(undo) == 0) {
         FastPlaceMessages.actionBar(player, FastPlaceMessages.text(
            undo ? "fastformer.message.history_no_undo" : "fastformer.message.history_no_redo"
         ));
         return false;
      }
      WorldChangeBatch next = history.peek(undo);
      // A history batch is owned by its target dimension, not by the
      // player's current dimension.  Accept the single undo request even
      // after a dimension change; tickOwner will retain it in WORLD_UNLOADED
      // state until that level is available and then continue automatically.
      if (next != null && WorldWriteCoordinator.busy(player.getServer(), next.dimension())) {
         FastPlaceMessages.actionBar(player, FastPlaceMessages.text("fastformer.message.world_write_waiting"));
         return false;
      }
      owner.active = new HistoryReplayTask(
         HISTORY_ACCESS,
         history,
         undo,
         requested,
         FastPlaceSettings.load(player).placementUpdateMode()
      );
      FastPlaceMessages.actionBar(
         player,
         FastPlaceMessages.text(undo ? "fastformer.message.history_start_undo" : "fastformer.message.history_start_redo", requested)
      );
      return true;
   }

   /**
    * Starts a non-history rollback for a task that failed after partial
    * writes.  It uses the same expected-after checks as user-facing undo but
    * never moves a history stack.
    */
   public static boolean startRollback(ServerPlayer player, ArrayDeque<ReversibleBlockSnapshot> changes) {
      return startRollback(player, player.serverLevel(), changes);
   }

   public static boolean startRollback(ServerPlayer player, ServerLevel level, ArrayDeque<ReversibleBlockSnapshot> changes) {
      if (level == null) {
         return false;
      }
      return startRollback(
         new WorldTaskContext(player.getServer(), player.getUUID()), level.dimension(), changes, Map.of(), level, null
      );
   }

   /**
    * Starts a rollback when the target level may not currently be loaded.
    * Complete after snapshots can be compacted immediately; otherwise the
    * capture is retained until the level is available and then completed from
    * the live world.
    */
   public static boolean startRollback(
      ServerPlayer player,
      ResourceKey<Level> dimension,
      ArrayDeque<ReversibleBlockSnapshot> changes
   ) {
      return startRollback(player, dimension, changes, Map.of());
   }

   public static boolean startRollback(
      ServerPlayer player,
      ServerLevel level,
      ArrayDeque<ReversibleBlockSnapshot> changes,
      Map<BlockPos, ReversibleBlockSnapshot> after
   ) {
      ResourceKey<Level> dimension = level == null ? null : level.dimension();
      if (dimension == null) {
         return false;
      }
      return startRollback(new WorldTaskContext(player.getServer(), player.getUUID()), dimension, changes, after, level, null);
   }

   public static boolean startRollback(
      ServerPlayer player,
      ServerLevel level,
      ArrayDeque<ReversibleBlockSnapshot> changes,
      Map<BlockPos, ReversibleBlockSnapshot> after,
      PersistentRecoveryJournal journal
   ) {
      ResourceKey<Level> dimension = level == null ? null : level.dimension();
      if (dimension == null) {
         return false;
      }
      return startRollback(new WorldTaskContext(player.getServer(), player.getUUID()), dimension, changes, after, level, journal);
   }

   public static boolean startRollback(
      ServerPlayer player,
      ResourceKey<Level> dimension,
      ArrayDeque<ReversibleBlockSnapshot> changes,
      Map<BlockPos, ReversibleBlockSnapshot> after
   ) {
      ServerLevel level = player.getServer() == null || dimension == null
         ? null
         : player.getServer().getLevel(dimension);
      return startRollback(new WorldTaskContext(player.getServer(), player.getUUID()), dimension, changes, after, level, null);
   }

   public static boolean startRollback(
      ServerPlayer player,
      ResourceKey<Level> dimension,
      ArrayDeque<ReversibleBlockSnapshot> changes,
      Map<BlockPos, ReversibleBlockSnapshot> after,
      PersistentRecoveryJournal journal
   ) {
      ServerLevel level = player.getServer() == null || dimension == null
         ? null
         : player.getServer().getLevel(dimension);
      return startRollback(new WorldTaskContext(player.getServer(), player.getUUID()), dimension, changes, after, level, journal);
   }

   public static boolean startRollback(
      WorldTaskContext context,
      ResourceKey<Level> dimension,
      WorldRecoverySnapshot recovery,
      PersistentRecoveryJournal journal
   ) {
      if (recovery == null) {
         return false;
      }
      if (!recovery.ready()) {
         // The queue takes the capture first. Nothing after that point may throw, or the
         // caller cannot tell an accepted snapshot from a refused one and hands it over
         // a second time.
         enqueueCapture(
            context.owner(),
            recoveryCapture(
               dimension, recovery.before(), recovery.after(), journal, recovery.readyForRecovery()
            )
         );
         notifyQuietly(context, "fastformer.message.task_recovery_blocked");
         return true;
      }
      return startRollback(context, dimension, recovery.before(), recovery.after(), journal);
   }

   /**
    * Takes ownership after a writer is detached. Recovery waits for commit
    * compression. The writer's memory is released here. Its world lease stays
    * with the owner until recovery finishes or unused journal cleanup succeeds.
    */
   public static TaskCancellationResult acceptStoppedTask(WorldTaskContext context, PlacementTask task) {
      return acceptTransferredRecovery(
         context,
         task.dimension(),
         task.stopAndTransferRecovery(),
         task.journal(),
         () -> task.releaseAfterCancelledJournal(context),
         task::releaseMemoryReservation
      );
   }

   public static TaskCancellationResult acceptStoppedTask(WorldTaskContext context, WorldOperationTask task) {
      return acceptTransferredRecovery(
         context,
         task.dimension(),
         task.stopAndTransferRecovery(),
         task.journal(),
         () -> task.releaseAfterCancelledJournal(context),
         task::releaseMemoryReservation
      );
   }

   /**
    * Takes ownership of an already extracted snapshot.
    *
    * <p>The caller keeps the snapshot until this call accepts it. A caller that must retry
    * after a failure passes the same snapshot again instead of extracting a second one,
    * because the extraction already moved the record out of the task.</p>
    */
   public static TaskCancellationResult acceptTransferredRecovery(
      WorldTaskContext context,
      ResourceKey<Level> dimension,
      WorldRecoverySnapshot recovery,
      PersistentRecoveryJournal journal,
      Runnable releaseUnusedJournal,
      Runnable releaseTaskMemory
   ) {
      try {
         if (!recovery.hasWrites()) {
            recovery.readyForRecovery().thenRun(releaseUnusedJournal);
            return TaskCancellationResult.CANCELLED_BEFORE_WRITE;
         }
         return startRollback(context, dimension, recovery, journal)
            ? TaskCancellationResult.ROLLBACK_STARTED
            : TaskCancellationResult.RECOVERY_BLOCKED;
      } finally {
         releaseTaskMemoryQuietly(releaseTaskMemory);
      }
   }

   /**
    * Releases the detached task's memory after the hand-off.
    *
    * <p>The release must never decide whether the hand-off was accepted. A failure here
    * would replace the result with an exception, and a caller that reads an exception as
    * "not accepted" would hand the same snapshot over a second time.
    */
   private static void releaseTaskMemoryQuietly(Runnable releaseTaskMemory) {
      if (releaseTaskMemory == null) {
         return;
      }
      try {
         releaseTaskMemory.run();
      } catch (RuntimeException | OutOfMemoryError exception) {
         LOGGER.error("FastFormer could not release task memory after a recovery hand-off", exception);
      }
   }

   public static boolean startRollback(
      WorldTaskContext context,
      ResourceKey<Level> dimension,
      ArrayDeque<ReversibleBlockSnapshot> changes,
      Map<BlockPos, ReversibleBlockSnapshot> after,
      PersistentRecoveryJournal journal
   ) {
      return startRollback(context, dimension, changes, after, context.level(dimension), journal);
   }

   /**
    * Takes ownership of a failed short transaction's capture.
    *
    * <p>The caller must not use the containers after this call, because the
    * manager stores them in the owner's recovery queue. A queued capture keeps
    * its block positions, its dimension and its operation id until a later tick
    * materializes the recovery task or resolves it as already restored.</p>
    *
    * @return {@code true} when this manager owns the capture, {@code false}
    *         when the caller still owns it and must keep it with the write lease
    */
   public static boolean acceptShortTransactionRecovery(
      WorldTaskContext context,
      ResourceKey<Level> dimension,
      ArrayDeque<ReversibleBlockSnapshot> changes,
      Map<BlockPos, ReversibleBlockSnapshot> after
   ) {
      if (context == null || dimension == null || changes == null || changes.isEmpty()) {
         return false;
      }
      if (startRollback(context, dimension, changes, after, null)) {
         return true;
      }
      // startRollback declines only for input it cannot own. The snapshots
      // already belong to this call, so queue them instead of returning them to
      // a caller that would drop the only record of a partial write.
      enqueueCapture(context.owner(), recoveryCapture(dimension, changes, after, null));
      // The queue owns the capture now, so the announcement must stay harmless.
      notifyQuietly(context, "fastformer.message.task_recovery_blocked");
      return true;
   }

   private static boolean startRollback(
      WorldTaskContext context,
      ResourceKey<Level> dimension,
      ArrayDeque<ReversibleBlockSnapshot> changes,
      Map<BlockPos, ReversibleBlockSnapshot> after,
      ServerLevel level,
      PersistentRecoveryJournal journal
   ) {
      if (dimension == null || changes == null) {
         return false;
      }
      // Callers transfer a detached snapshot map; another full copy here can
      // double the peak memory of a large cancellation.
      Map<BlockPos, ReversibleBlockSnapshot> safeAfter = after == null ? Map.of() : after;
      if (busy(context.owner())) {
         // A previous history/recovery task owns this player. Keep this
         // rollback in order instead of reporting success without a task.
         if (!changes.isEmpty() || journal != null) {
            if (!enqueueCaptureQuietly(context.owner(), dimension, changes, safeAfter, journal)) {
               // Nothing was transferred, so the caller still owns the snapshot.
               return false;
            }
         }
         return true;
      }
      if (changes.isEmpty()) {
         return resolveAlreadyRestored(context, dimension, changes, safeAfter, journal);
      }
      if (!WorldOperationMemory.snapshotAdmission(changes.size(), 0L).fitsCurrentHeap()) {
         if (!enqueueCaptureQuietly(context.owner(), dimension, changes, safeAfter, journal)) {
            return false;
         }
         notifyQuietly(context, "fastformer.message.operation_memory_unsafe");
         return true;
      }
      Optional<WorldChangeBatch> captured;
      try {
         captured = level == null
            ? WorldChangeBatch.capturePairsByPos(dimension, changes, safeAfter)
            : WorldChangeBatch.capturePairsByPos(level, changes, safeAfter);
      } catch (RuntimeException | OutOfMemoryError exception) {
         captured = Optional.empty();
      }
      if (captured.isEmpty() && level != null) {
         if (allBefore(level, changes)) {
            return resolveAlreadyRestored(context, dimension, changes, safeAfter, journal);
         }
         // Incomplete paired snapshots can fall back to live capture. Complete
         // expected-after data remains authoritative so external replacements
         // are never adopted as if they belonged to the cancelled task.
         try {
            captured = WorldChangeBatch.capture(level, changes);
         } catch (RuntimeException | OutOfMemoryError exception) {
            captured = Optional.empty();
         }
      }
      if (captured.isPresent()) {
         ownerState(context.owner()).active = HistoryReplayTask.recovery(HISTORY_ACCESS, captured.orElseThrow(), journal);
         // The ownership transfer happened above. Nothing after it may throw, or the
         // caller cannot tell an accepted snapshot from a refused one.
         notifyQuietly(context, "fastformer.message.restore_cancelled_task");
         return true;
      }
      if (level == null && completeAfter(changes, safeAfter)) {
         // A complete pair capture with no entries means nothing was changed;
         // do not create a recovery lock merely because the level is absent.
         return resolveAlreadyRestored(context, dimension, changes, safeAfter, journal);
      }
      if (level != null) {
         // A live read can still fail (for example while a block entity is
         // being unloaded). Keep a recovery capture when the world differs
         // from the original snapshots; only a true no-op is discarded.
         if (allBefore(level, changes)) {
            return resolveAlreadyRestored(context, dimension, changes, safeAfter, journal);
         }
      }
      // Do not drop a partially-written task merely because its dimension is
      // unloaded. The world task retries on later server ticks.
      if (!enqueueCaptureQuietly(context.owner(), dimension, changes, safeAfter, journal)) {
         return false;
      }
      notifyQuietly(context, "fastformer.message.task_recovery_blocked");
      return true;
   }

   /**
    * Queues one recovery capture, or reports that it was not queued.
    *
    * <p>A failed enqueue transfers nothing, so the caller keeps ownership of the snapshot.
    */
   private static boolean enqueueCaptureQuietly(
      UUID owner,
      ResourceKey<Level> dimension,
      ArrayDeque<ReversibleBlockSnapshot> changes,
      Map<BlockPos, ReversibleBlockSnapshot> after,
      PersistentRecoveryJournal journal
   ) {
      try {
         enqueueCapture(owner, recoveryCapture(dimension, changes, after, journal));
         return true;
      } catch (RuntimeException | OutOfMemoryError exception) {
         LOGGER.error("FastFormer could not queue a recovery capture for {}", owner, exception);
         return false;
      }
   }

   /** Progress feedback must never decide ownership, so a failed message stays harmless. */
   private static void notifyQuietly(WorldTaskContext context, String key) {
      try {
         context.actionBar(FastPlaceMessages.text(key));
      } catch (RuntimeException | OutOfMemoryError exception) {
         LOGGER.warn("FastFormer could not show the recovery message {}", key, exception);
      }
   }

   private static boolean resolveAlreadyRestored(
      WorldTaskContext context,
      ResourceKey<Level> dimension,
      ArrayDeque<ReversibleBlockSnapshot> changes,
      Map<BlockPos, ReversibleBlockSnapshot> after,
      PersistentRecoveryJournal journal
   ) {
      if (journal == null || journal.resolveAfterRollback(context.server())) {
         releaseResolvedLease(context.server(), dimension, context.owner());
         return true;
      }
      // The blocks are already correct, but the durable save/journal cleanup
      // is still part of the transaction. Retain it as a world task so a
      // transient IO failure cannot become a permanent invisible lease.
      enqueueCapture(context.owner(), recoveryCapture(dimension, changes, after, journal));
      return true;
   }

   private static boolean completeAfter(
      ArrayDeque<ReversibleBlockSnapshot> changes,
      Map<BlockPos, ReversibleBlockSnapshot> after
   ) {
      if (after == null || after.isEmpty()) {
         return false;
      }
      for (ReversibleBlockSnapshot change : changes) {
         if (change == null) {
            continue;
         }
         ReversibleBlockSnapshot snapshot = after.get(change.pos());
         if (snapshot == null || !snapshot.pos().equals(change.pos())) {
            return false;
         }
      }
      return true;
   }

   public static boolean busy(ServerPlayer player) {
      return busy(player.getUUID());
   }

   public static boolean busy(UUID id) {
      OwnerState owner = OWNERS.get(id);
      return owner != null && owner.busy();
   }

   /** Waits for the login-triggered history snapshot before a GameTest dispatches input. */
   public static void awaitInitialHistoryLoadForTest(ServerPlayer player, long timeoutMillis) {
      if (player == null || player.getServer() == null || timeoutMillis <= 0L) {
         throw new IllegalArgumentException("A player with a server and a positive timeout is required");
      }
      OwnerState owner = OWNERS.get(player.getUUID());
      if (owner == null) {
         throw new IllegalStateException("Player history initialization did not start for " + player.getUUID());
      }
      CompletableFuture<WorldHistoryPersistence.LoadedHistory> load = owner.historyLoad;
      if (load != null) {
         try {
            load.get(timeoutMillis, TimeUnit.MILLISECONDS);
         } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for player history initialization", exception);
         } catch (TimeoutException exception) {
            throw new IllegalStateException("Timed out after " + timeoutMillis
               + " ms waiting for player history initialization", exception);
         } catch (ExecutionException exception) {
            throw new IllegalStateException("Player history initialization failed", exception.getCause());
         }
         attachLoadedHistory(new WorldTaskContext(player.getServer(), player.getUUID()));
      }
      if (owner.historyLoad != null || owner.historyLoadFailed || owner.history == null) {
         throw new IllegalStateException("Player history initialization did not become ready");
      }
   }

   public static boolean snapshotRetryAvailable(ServerPlayer player) {
      OwnerState owner = OWNERS.get(player.getUUID());
      return owner != null && owner.historyLoadFailed && owner.historyLoad == null;
   }

   public static boolean restoreActive(ServerPlayer player) {
      return busy(player);
   }

   /** Cancels an ordinary undo/redo task. Recovery is never user-paused. */
   public static boolean cancel(ServerPlayer player) {
      return cancel(player.getUUID());
   }

   static boolean cancel(UUID id) {
      OwnerState owner = ownerState(id);
      HistoryReplayTask task = owner.active;
      if (task == null) {
         task = owner.pendingTask;
      }
      if (task == null) {
         return owner.scheduler != null && owner.scheduler.cancelPageLoad();
      }
      if (task.recovery()) {
         return false;
      }
      if (owner.scheduler != null) {
         owner.scheduler.cancelPageLoad();
      }
      task.requestCancel();
      return true;
   }

   public static void remove(ServerPlayer player) {
      detachOwner(player.getUUID());
   }

   static void detachOwner(UUID id) {
      OwnerState owner = ownerState(id);
      if (owner.scheduler != null) {
         owner.scheduler.cancelPageLoad();
      }
      HistoryReplayTask active = owner.active;
      if (active != null && !active.recovery()) {
         active.requestCancel();
      }
      HistoryReplayTask pending = owner.pendingTask;
      if (pending != null && !pending.recovery()) {
         pending.requestCancel();
      }
      // Completed history and deferred undo work belong to the owner UUID for
      // the lifetime of this server instance. Reconnect timing must not drop
      // an undo that was accepted while a recovery task was still running.
      owner.detached = true;
      pruneDetachedOwners();
   }

   /** Clears all in-memory state when a server instance is stopping. */
   public static void clearServer() {
      for (OwnerState owner : OWNERS.values()) {
         if (owner.active != null) {
            owner.active.cancelJournalPreparation();
            owner.active.releaseMemoryReservation();
         }
         if (owner.pendingTask != null) {
            owner.pendingTask.cancelJournalPreparation();
            owner.pendingTask.releaseMemoryReservation();
         }
      }
      OWNERS.clear();
   }

   public static void trimToSetting(ServerPlayer player, int limit) {
      UUID id = player.getUUID();
      OwnerState owner = ownerState(id);
      owner.historyLimit = boundedLimit(limit);
      if ((owner.history == null || owner.historyLoadFailed) && owner.historyLoad == null) {
         startHistorySnapshotLoad(player, owner);
      }
      // Do not evict the batch currently being applied; HistoryReplayTask keeps a
      // reference to it until commit. The completion path trims again after
      // the active task is removed.
      if (owner.active != null || owner.pendingTask != null) {
         return;
      }
      HistoryMemoryCache history = owner.history;
      if (history != null) {
         trim(limit, history);
         historyOrder(owner).trim(owner.historyLimit);
         scheduleIndex(player.getServer(), id, owner, history);
      }
   }

   /**
    * Advances a pending history/recovery task.  Returning true means the
    * caller must not tick another world-writing task for this player in the
    * same server tick.
    */
   /** Advances world-owned history/recovery tasks even when their owner is offline. */
   public static void tickWorld(MinecraftServer server) {
      for (Map.Entry<UUID, OwnerState> entry : OWNERS.entrySet()) {
         if (entry.getValue().needsTick()) {
            tickOwner(new WorldTaskContext(server, entry.getKey()));
         }
      }
   }

   private static boolean tickOwner(WorldTaskContext context) {
      UUID owner = context.owner();
      OwnerState ownerState = ownerState(owner);
      if (!PersistentRecoveryJournal.writesAllowed() && ownerState.scheduler != null) {
         // Discard the continuation before a completed page can resume it.
         ownerState.scheduler.cancelPageLoad();
      }
      attachLoadedHistory(context);
      attachHistoryPage(context);
      attachPending(context);
      if (context.onlinePlayer() != null) {
         ownerState.detached = false;
      }
      HistoryReplayTask task = ownerState.active;
      if (task == null) {
         retryPersistence(context, ownerState);
         ServerPlayer player = context.onlinePlayer();
         return player != null && runDeferredUndo(player);
      }
      if (!PersistentRecoveryJournal.writesAllowed() && !task.recovery()) {
         // The global gate may close after an undo/redo was accepted. Do not
         // apply more history targets; requestCancel either discards its
         // unused journal or rolls its already-applied cells back.
         task.requestCancel();
      }
      boolean finished;
      WorldTaskBudget budget = WorldTaskBudget.forServerTick(
         task.memoryThrottled(), task.previousBatchCells(), task.previousBatchNanos()
      );
      long batchStartedAt = System.nanoTime();
      try {
         finished = task.tick(context, budget);
      } catch (RuntimeException | OutOfMemoryError exception) {
         // Never let a malformed block entity or callback crash the server.
         // Roll back any cells already touched by this task on the next tick.
         task.requestCancel();
         task.releaseMemoryReservation();
         ownerState.active = null;
         ownerState.pendingTask = task;
         context.actionBar(FastPlaceMessages.text("fastformer.message.restore_failed_retry"));
          LOGGER.error(
             "FastFormer history task failed for {} (operation={}) and was retained for recovery: {}",
             owner,
             task.operationId(),
             task.metricsSummary(),
             exception
          );
          return true;
       } finally {
          task.recordBatch(budget.consumed(), System.nanoTime() - batchStartedAt);
       }
      if (finished) {
         task.clearConflictPrompt(context);
         if (task.completedSuccessfully()) {
            task.markMetricsComplete();
         }
         LOGGER.info("FastFormer history operation {} finished: {}", task.operationId(), task.metricsSummary());
         // Every terminal tick owns the lease cleanup.  Most phases release
         // it on their way to RESOLVE, but cancellation/conflict exits can
         // finish directly and must not strand the dimension lock.
         task.releaseLease(context);
         task.releaseMemoryReservation();
         ownerState.active = null;
         HistoryRecoveryPolicy.RetentionAction retention = HistoryRecoveryPolicy.retentionAction(
            task.recovery() && task.retainRecovery(),
            task.batch() != null
         );
         if (retention == HistoryRecoveryPolicy.RetentionAction.RETRY_AUTOMATICALLY) {
            ownerState.active = HistoryReplayTask.recovery(HISTORY_ACCESS, task.batch(), task.journal());
         }
         HistoryMemoryCache history = ownerState.history;
         if (history != null) {
            ServerPlayer player = context.onlinePlayer();
            if (player == null) {
               trim(ownerState.historyLimit, history);
            } else {
               trimSafely(player, history);
            }
         }
         ServerPlayer player = context.onlinePlayer();
         if (player != null) {
            runDeferredUndo(player);
         }
      }
      return true;
   }

   private static boolean runDeferredUndo(ServerPlayer player) {
      UUID id = player.getUUID();
      OwnerState owner = ownerState(id);
      int count = owner.deferredUndo;
      if (count <= 0) {
         return false;
      }
      HistoryMemoryCache history = owner.history;
      WorldChangeBatch next = history == null ? null : history.peek(true);
      if (next != null && WorldWriteCoordinator.busy(player.getServer(), next.dimension())) {
         return true;
      }
      count = takeDeferredUndo(id);
      if (count == 0) {
         return true;
      }
      if (!requestUndo(player, count) && next != null) {
         owner.deferredUndo = count;
      }
      return true;
   }

   static int takeDeferredUndo(UUID id) {
      OwnerState owner = ownerState(id);
      if (owner.active != null
         || owner.pendingTask != null
         || !owner.pendingCaptures.isEmpty()
         || !owner.pendingRecords.isEmpty()) {
         return 0;
      }
      int count = owner.deferredUndo;
      owner.deferredUndo = 0;
      return count;
   }

   private static void attachPending(WorldTaskContext context) {
      UUID id = context.owner();
      OwnerState owner = ownerState(id);
      if (owner.active != null) {
         return;
      }
      HistoryReplayTask pendingTask = owner.pendingTask;
      owner.pendingTask = null;
      if (pendingTask != null) {
         owner.active = pendingTask;
         return;
      }
      ArrayDeque<RecoveryCapture> captures = owner.pendingCaptures;
      RecoveryCapture capture = captures.peekFirst();
      if (capture != null) {
         if (!capture.readyForRecovery().isDone()) {
            return;
         }
         if (!WorldOperationMemory.snapshotAdmission(capture.changes().size(), 0L).fitsCurrentHeap()) {
            return;
         }
         Optional<WorldChangeBatch> batch = materializeRecovery(context.server(), capture);
         if (batch.isPresent()) {
            removeCapture(captures);
            owner.active = HistoryReplayTask.recovery(HISTORY_ACCESS, batch.orElseThrow(), capture.journal());
            return;
         } else if (context.level(capture.dimension()) == null) {
            return;
         } else {
            ServerLevel level = context.level(capture.dimension());
            if (!allBefore(level, capture.changes())) {
               // A failed NBT/world read must not be mistaken for a no-op;
               // retain the recovery and retry instead of dropping blocks.
               return;
            }
            if (capture.journal() != null && !capture.journal().resolveAfterRollback(context.server())) {
               return;
            }
            removeCapture(captures);
            releaseResolvedLease(context.server(), capture.dimension(), context.owner());
            context.actionBar(FastPlaceMessages.text("fastformer.message.restore_complete"));
         }
      }
      PendingRecord pendingRecord = owner.pendingRecords.peekFirst();
      if (pendingRecord != null) {
         if (!WorldOperationMemory.snapshotAdmission(pendingRecord.changes().size(), 0L).fitsCurrentHeap()) {
            return;
         }
         Optional<WorldChangeBatch> batch = materializeRecord(context.server(), pendingRecord);
         if (batch.isPresent()) {
            owner.pendingRecords.removeFirst();
            addBatch(context, batch.orElseThrow());
         } else if (context.level(pendingRecord.dimension()) == null) {
            return;
         } else {
            ServerLevel level = context.level(pendingRecord.dimension());
            if (!allBefore(level, pendingRecord.changes())) {
               return;
            }
            owner.pendingRecords.removeFirst();
         }
      }
   }

   private static void attachLoadedHistory(WorldTaskContext context) {
      OwnerState owner = ownerState(context.owner());
      CompletableFuture<WorldHistoryPersistence.LoadedHistory> load = owner.historyLoad;
      if (load == null || !load.isDone()) {
         return;
      }
      owner.historyLoad = null;
      try {
         WorldHistoryPersistence.LoadedHistory loaded = load.join();
         applyLoadedHistory(owner, loaded);
         owner.historyLoadFailed = false;
      } catch (RuntimeException failure) {
         owner.historyLoadFailed = true;
         context.chat(FastPlaceMessages.text("fastformer.message.history_load_failed"));
         LOGGER.error("Could not load durable history for {}", context.owner(), failure);
      }
   }

   private static void startHistorySnapshotLoad(ServerPlayer player, OwnerState owner) {
      owner.historyLoad = WorldHistoryPersistence.loadSnapshot(
         player.getServer(), player.getUUID(), owner.historyLimit, MAX_BYTES_PER_PLAYER
      );
   }

   private static void attachHistoryPage(WorldTaskContext context) {
      OwnerState owner = ownerState(context.owner());
      Optional<HistoryTaskScheduler.CompletedPageLoad> completed;
      try {
         completed = owner.scheduler().takeCompletedPageLoad();
      } catch (RuntimeException failure) {
         // Keep decoded history and the durable order. A later undo/redo may retry
         // the same missing page. Do not mark the owner as permanently blocked.
         notifyHistoryLoadFailed(context, "Could not load an older history page for {}", failure);
         return;
      }
      if (completed.isEmpty()) {
         return;
      }
      HistoryTaskScheduler.CompletedPageLoad pageLoad = completed.orElseThrow();
      HistoryPageLoadPlan plan = pageLoad.plan();
      try {
         HistoryMemoryCache history = owner.history();
         history.merge(plan.undoDirection(), pageLoad.batches());
         trim(owner.historyLimit, history);
      } catch (RuntimeException failure) {
         owner.scheduler().pageMergeFailed(plan);
         notifyHistoryLoadFailed(context, "Could not merge an older history page for {}", failure);
         return;
      }
      ServerPlayer player = context.onlinePlayer();
      if (player != null && owner.active == null) {
         request(player, plan.undoDirection(), plan.requestedCount());
      }
   }

   /**
    * Starts one older-page load for an undo or redo that memory cannot satisfy.
    *
    * <p>A later player request may send the same plan again after a failed load.
    * The scheduler holds at most one in-flight page, so this call never stacks IO.</p>
    */
   private static void startHistoryPageLoad(
      ServerPlayer player, OwnerState owner, HistoryPageLoadPlan plan
   ) {
      owner.scheduler().startPageLoad(
         plan,
         WorldHistoryPersistence.loadPage(
            player.getServer(), player.getUUID(), plan.operationIds(), plan.operationIds().size(), HISTORY_PAGE_BYTES
         )
      );
      FastPlaceMessages.actionBar(player, FastPlaceMessages.text("fastformer.message.history_loading_older"));
   }

   private static void notifyHistoryLoadFailed(WorldTaskContext context, String log, RuntimeException failure) {
      context.chat(FastPlaceMessages.text("fastformer.message.history_load_failed"));
      LOGGER.error(log, context.owner(), failure);
   }

   static void mergeLoaded(ArrayDeque<WorldChangeBatch> target, java.util.List<WorldChangeBatch> loaded) {
      java.util.Set<UUID> present = target.stream().map(WorldChangeBatch::operationId).collect(
         java.util.stream.Collectors.toSet()
      );
      for (WorldChangeBatch batch : loaded) {
         if (present.add(batch.operationId())) target.addLast(batch);
      }
   }

   private static void applyLoadedHistory(
      OwnerState owner, WorldHistoryPersistence.LoadedHistory loaded
   ) {
      HistoryMemoryCache history = owner.history();
      history.merge(true, loaded.undo());
      history.merge(false, loaded.redo());
      owner.historyOrder = HistoryOrderCatalog.merge(
         history.operationIds(true), history.operationIds(false),
         loaded.undoOrder(), loaded.redoOrder()
      );
      owner.historyOrder.trim(owner.historyLimit);
      trim(owner.historyLimit, history);
   }

   static void installLoadedHistoryForTest(
      UUID ownerId, WorldHistoryPersistence.LoadedHistory loaded, int limit
   ) {
      OwnerState owner = ownerState(ownerId);
      owner.historyLimit = boundedLimit(limit);
      owner.historyLoad = null;
      owner.historyLoadFailed = false;
      applyLoadedHistory(owner, loaded);
   }

   static List<UUID> historyOrderForTest(UUID ownerId, boolean undo) {
      OwnerState owner = OWNERS.get(ownerId);
      return owner == null ? List.of() : historyOrder(owner).order(undo);
   }

   private static void enqueueCapture(UUID owner, RecoveryCapture capture) {
      ownerState(owner).pendingCaptures.addLast(capture);
   }

   private static PendingRecord pendingRecord(
      ResourceKey<Level> dimension,
      ArrayDeque<ReversibleBlockSnapshot> changes,
      Map<BlockPos, ReversibleBlockSnapshot> after
   ) {
      return new PendingRecord(dimension, changes, after, UUID.randomUUID());
   }

   private static RecoveryCapture recoveryCapture(
      ResourceKey<Level> dimension,
      ArrayDeque<ReversibleBlockSnapshot> changes,
      Map<BlockPos, ReversibleBlockSnapshot> after,
      PersistentRecoveryJournal journal
   ) {
      return recoveryCapture(dimension, changes, after, journal, CompletableFuture.completedFuture(null));
   }

   private static RecoveryCapture recoveryCapture(
      ResourceKey<Level> dimension,
      ArrayDeque<ReversibleBlockSnapshot> changes,
      Map<BlockPos, ReversibleBlockSnapshot> after,
      PersistentRecoveryJournal journal,
      CompletableFuture<Void> readyForRecovery
   ) {
      UUID operationId = journal == null || journal.operationId() == null
         ? UUID.randomUUID()
         : journal.operationId();
      return new RecoveryCapture(dimension, changes, after, journal, operationId, readyForRecovery);
   }

   private static void removeCapture(ArrayDeque<RecoveryCapture> captures) {
      if (captures == null) {
         return;
      }
      captures.removeFirst();
   }

   private static boolean allBefore(ServerLevel level, ArrayDeque<ReversibleBlockSnapshot> changes) {
      if (level == null) {
         return false;
      }
      LongOpenHashSet checked = new LongOpenHashSet();
      var iterator = changes.descendingIterator();
      while (iterator.hasNext()) {
         ReversibleBlockSnapshot change = iterator.next();
         if (change != null && checked.add(change.pos().asLong()) && !change.matches(level, change.pos())) {
            return false;
         }
      }
      return true;
   }

   private static Optional<WorldChangeBatch> materializeRecovery(MinecraftServer server, RecoveryCapture capture) {
      try {
         Optional<WorldChangeBatch> paired = WorldChangeBatch.capturePairsByPos(
            capture.dimension(), capture.changes(), capture.after()
         ).map(batch -> batch.withOperationId(capture.operationId()));
         if (paired.isPresent()) {
            return paired;
         }
      } catch (RuntimeException | OutOfMemoryError ignored) {
         // Fall through to the live-state fallback below.
      }
      ServerLevel level = server == null ? null : server.getLevel(capture.dimension());
      if (level == null) {
         return Optional.empty();
      }
      try {
         return WorldChangeBatch.capture(level, capture.changes())
            .map(batch -> batch.withOperationId(capture.operationId()));
      } catch (RuntimeException | OutOfMemoryError exception) {
         return Optional.empty();
      }
   }

   private static Optional<WorldChangeBatch> materializeRecord(MinecraftServer server, PendingRecord pending) {
      ServerLevel level = server == null ? null : server.getLevel(pending.dimension());
      if (level == null) {
         return Optional.empty();
      }
      try {
         Optional<WorldChangeBatch> paired = WorldChangeBatch.capturePairsByPos(level, pending.changes(), pending.after())
            .map(batch -> batch.withOperationId(pending.operationId()));
         if (paired.isPresent()) {
            return paired;
         }
      } catch (RuntimeException | OutOfMemoryError ignored) {
         // Fall through to the live-state fallback below.
      }
      try {
         return WorldChangeBatch.capture(level, pending.changes())
            .map(batch -> batch.withOperationId(pending.operationId()));
      } catch (RuntimeException | OutOfMemoryError exception) {
         return Optional.empty();
      }
   }

   static void addBatchForTest(UUID owner, WorldChangeBatch batch) {
      addBatch(new WorldTaskContext(null, owner), batch);
   }

   static int undoSizeForTest(UUID owner) {
      OwnerState state = OWNERS.get(owner);
      HistoryMemoryCache history = state == null ? null : state.history;
      return history == null ? 0 : history.size(true);
   }

   static void setOwnerLimitForTest(UUID owner, int limit) {
      ownerState(owner).historyLimit = boundedLimit(limit);
   }

   static int recoveryCaptureCountForTest(UUID owner) {
      OwnerState state = OWNERS.get(owner);
      return state == null ? 0 : state.pendingCaptures.size();
   }

   static WorldChangeBatch activeRecoveryBatchForTest(UUID owner) {
      OwnerState state = OWNERS.get(owner);
      HistoryReplayTask active = state == null ? null : state.active;
      return active != null && active.recovery() ? active.batch() : null;
   }

   static void releaseResolvedLease(Object server, ResourceKey<Level> dimension, UUID owner) {
      WorldWriteCoordinator.releaseCurrentLease(server, dimension, owner);
   }

   private record RecoveryCapture(
      ResourceKey<Level> dimension,
      ArrayDeque<ReversibleBlockSnapshot> changes,
      Map<BlockPos, ReversibleBlockSnapshot> after,
      PersistentRecoveryJournal journal,
      UUID operationId,
      CompletableFuture<Void> readyForRecovery
   ) {
      private RecoveryCapture {
         // startRollback receives collections detached from the writer task.
         // Transfer them directly so an exceptional large recovery does not
         // double its peak heap before any block can be restored.
         changes = changes == null ? new ArrayDeque<>() : changes;
         after = after == null ? Map.of() : after;
         operationId = operationId == null ? UUID.randomUUID() : operationId;
         readyForRecovery = readyForRecovery == null
            ? CompletableFuture.completedFuture(null)
            : readyForRecovery;
      }
   }

   private record PendingRecord(
      ResourceKey<Level> dimension,
      ArrayDeque<ReversibleBlockSnapshot> changes,
      Map<BlockPos, ReversibleBlockSnapshot> after,
      UUID operationId
   ) {
      private PendingRecord {
         changes = changes == null ? new ArrayDeque<>() : changes;
         after = after == null ? Map.of() : after;
         operationId = operationId == null ? UUID.randomUUID() : operationId;
      }
   }

   private static void trim(ServerPlayer player, HistoryMemoryCache history) {
      int limit = boundedLimit(FastPlaceSettings.load(player).worldUndoHistoryLimit());
      ownerState(player.getUUID()).historyLimit = limit;
      trim(limit, history);
   }

   private static void trimSafely(ServerPlayer player, HistoryMemoryCache history) {
      try {
         trim(player, history);
      } catch (RuntimeException exception) {
         // The newly committed batch is more important than immediate limit
         // enforcement. Keep it undoable and retry trimming later.
         LOGGER.error("FastFormer history trimming failed for {}", player.getUUID(), exception);
      }
   }

   private static void trim(int limit, HistoryMemoryCache history) {
      history.trim(boundedLimit(limit), MAX_BYTES_PER_PLAYER);
   }

   private static void scheduleNewBatch(
      MinecraftServer server, UUID ownerId, OwnerState owner, WorldChangeBatch batch, HistoryMemoryCache history
   ) {
      HistoryOrderCatalog order = historyOrder(owner);
      order.addNew(batch.operationId());
      order.trim(owner.historyLimit);
      trackPersistence(server, ownerId, owner, false, WorldHistoryPersistence.publishNewBatch(
         server, ownerId, batch, order.order(true), order.order(false)
      ));
   }

   private static void scheduleIndex(MinecraftServer server, UUID ownerId, OwnerState owner, HistoryMemoryCache history) {
      trackPersistence(server, ownerId, owner, false, WorldHistoryPersistence.publishIndex(
         server, ownerId, historyOrder(owner).order(true), historyOrder(owner).order(false)
      ));
   }

   private static void retryPersistence(WorldTaskContext context, OwnerState owner) {
      if (!owner.persistenceDirty || owner.pendingPersistence > 0 || owner.history == null || context.server() == null) return;
      if (owner.persistenceRetryTicks > 0) {
         owner.persistenceRetryTicks--;
         return;
      }
      HistoryMemoryCache history = owner.history;
      HistoryOrderCatalog order = historyOrder(owner);
      trackPersistence(context.server(), context.owner(), owner, true, WorldHistoryPersistence.publishSnapshot(
         context.server(), context.owner(), history.snapshot(true), history.snapshot(false),
         order.order(true), order.order(false)
      ));
   }

   private static void trackPersistence(
      MinecraftServer server, UUID ownerId, OwnerState owner, boolean clearsDirty,
      CompletableFuture<Void> persistence
   ) {
      if (server == null) return;
      // Each write gets its own generation. A later failure must not be
      // cleared by an older snapshot completing successfully afterwards.
      long generation = ++owner.persistenceGeneration;
      owner.pendingPersistence++;
      persistence.whenComplete((ignored, failure) -> server.execute(() -> {
         OwnerState current = OWNERS.get(ownerId);
         if (current != owner) return;
         current.pendingPersistence = Math.max(0, current.pendingPersistence - 1);
         boolean previouslyFailed = current.persistenceDirty;
         if (failure != null) {
            current.persistenceDirty = true;
         } else if (clearsDirty && current.pendingPersistence == 0
            && generation == current.persistenceGeneration) {
            current.persistenceDirty = false;
         }
         // Retry soon after the storage fault clears. History remains in memory
         // while the bounded backoff prevents a busy retry loop.
         if (failure != null) current.persistenceRetryTicks = 100;
         if (previouslyFailed != current.persistenceDirty) {
            ServerPlayer player = server.getPlayerList().getPlayer(ownerId);
            if (player != null) {
               FastPlaceMessages.chat(player, FastPlaceMessages.text(current.persistenceDirty
                  ? "fastformer.message.history_save_retry"
                  : "fastformer.message.history_save_recovered"));
            }
         }
      }));
   }

   static boolean persistenceFailedForTest(UUID ownerId) {
      OwnerState owner = OWNERS.get(ownerId);
      return owner != null && owner.persistenceDirty && owner.pendingPersistence == 0;
   }

   static void trackPersistenceForTest(
      MinecraftServer server, UUID ownerId, boolean snapshot, CompletableFuture<Void> future
   ) {
      trackPersistence(server, ownerId, ownerState(ownerId), snapshot, future);
   }

   static boolean durableIndexFailedForTest(UUID ownerId) {
      OwnerState owner = OWNERS.get(ownerId);
      DurableCommit commit = owner == null ? null : owner.durableCommit;
      return commit != null
         && commit.phase == DurableCommitPhase.INDEX
         && commit.future.isCompletedExceptionally();
   }

   private static HistoryOrderCatalog historyOrder(OwnerState owner) {
      if (owner.historyOrder == null) {
         HistoryMemoryCache history = owner.history();
         owner.historyOrder = HistoryOrderCatalog.merge(
            history.operationIds(true), history.operationIds(false), List.of(), List.of()
         );
      }
      return owner.historyOrder;
   }

   private static OwnerState ownerState(UUID owner) {
      return OWNERS.computeIfAbsent(owner, ignored -> new OwnerState());
   }

   static int ownerCountForTest() {
      return OWNERS.size();
   }

   static void enqueuePendingRecordForTest(
      UUID owner,
      ResourceKey<Level> dimension,
      ArrayDeque<ReversibleBlockSnapshot> changes,
      Map<BlockPos, ReversibleBlockSnapshot> after
   ) {
      ownerState(owner).pendingRecords.addLast(pendingRecord(dimension, changes, after));
   }

   static void setPersistenceRetentionForTest(
      UUID ownerId, int pendingPersistence, boolean persistenceDirty, boolean durableCommit
   ) {
      OwnerState owner = ownerState(ownerId);
      owner.pendingPersistence = pendingPersistence;
      owner.persistenceDirty = persistenceDirty;
      owner.durableCommit = durableCommit
         ? new DurableCommit(null, null, CompletableFuture.completedFuture(null))
         : null;
   }

   static boolean ownerPresentForTest(UUID owner) {
      return OWNERS.containsKey(owner);
   }

   private static void pruneDetachedOwners() {
      long total = 0L;
      for (OwnerState state : OWNERS.values()) {
         total = saturatedAdd(total, state.historyBytes());
      }
      if (OWNERS.size() <= MAX_IDLE_OWNERS && total <= MAX_BYTES_GLOBAL) return;
      var iterator = OWNERS.entrySet().iterator();
      while (iterator.hasNext() && (OWNERS.size() > MAX_IDLE_OWNERS || total > MAX_BYTES_GLOBAL)) {
         OwnerState state = iterator.next().getValue();
         if (!state.canEvict()) continue;
         // Until disk-backed history is attached, these stacks are the only
         // undo/redo copy. Only empty owners can be evicted safely.
         if (state.history != null && !state.history.isEmpty()) continue;
         total -= state.historyBytes();
         iterator.remove();
      }
   }

   private static long saturatedAdd(long left, long right) {
      return right > Long.MAX_VALUE - left ? Long.MAX_VALUE : left + right;
   }

   private static int boundedLimit(int limit) {
      return Math.clamp(limit, 1, MAX_LIMIT);
   }

   /** All mutable history and recovery state owned by one player UUID. */
   private static final class OwnerState {
      private HistoryMemoryCache history;
      private HistoryReplayTask active;
      private HistoryReplayTask pendingTask;
      private final ArrayDeque<RecoveryCapture> pendingCaptures = new ArrayDeque<>();
      /** Records deferred after a memory failure; preserve arrival order. */
      private final ArrayDeque<PendingRecord> pendingRecords = new ArrayDeque<>();
      private int deferredUndo;
      private int historyLimit = DEFAULT_LIMIT;
      private boolean detached;
      private int pendingPersistence;
      private boolean persistenceDirty;
      private long persistenceGeneration;
      private int persistenceRetryTicks;
      private CompletableFuture<WorldHistoryPersistence.LoadedHistory> historyLoad;
      private HistoryTaskScheduler scheduler;
      private HistoryOrderCatalog historyOrder;
      private boolean historyLoadFailed;
      private DurableCommit durableCommit;

      private long historyBytes() {
         return history == null ? 0L : history.estimatedBytes();
      }

      private HistoryMemoryCache history() {
         if (history == null) {
            history = new HistoryMemoryCache();
         }
         return history;
      }

      private HistoryTaskScheduler scheduler() {
         if (scheduler == null) {
            scheduler = new HistoryTaskScheduler();
         }
         return scheduler;
      }

      private boolean busy() {
         return historyLoad != null
            || scheduler != null && scheduler.hasPendingPageLoad()
            || snapshotLoadFailed()
            || active != null
            || pendingTask != null
            || !pendingCaptures.isEmpty()
            || !pendingRecords.isEmpty()
            || deferredUndo > 0;
      }

      private boolean needsTick() {
         return historyLoad != null
            || scheduler != null && scheduler.hasPendingPageLoad()
            || active != null
            || pendingTask != null
            || !pendingCaptures.isEmpty()
            || !pendingRecords.isEmpty()
            || deferredUndo > 0
            || persistenceDirty;
      }

      /**
       * A failed snapshot remains blocked even if its merge allocated a partial cache.
       *
       * <p>An older-page failure must not use this flag. That owner already holds
       * decoded batches and a durable order, so a later undo can retry the page.</p>
       */
      private boolean snapshotLoadFailed() {
         return historyLoadFailed;
      }

      private boolean canEvict() {
         return detached
            && !busy()
            && pendingPersistence == 0
            && !persistenceDirty
            && durableCommit == null;
      }
   }

   private enum DurableCommitPhase { BATCH, INDEX }

   private static final class DurableCommit {
      private final WorldChangeBatch batch;
      private final PersistentRecoveryJournal journal;
      private DurableCommitPhase phase = DurableCommitPhase.BATCH;
      private CompletableFuture<Void> future;
      private int retryTicks = 100;

      private DurableCommit(
         WorldChangeBatch batch, PersistentRecoveryJournal journal, CompletableFuture<Void> future
      ) {
         this.batch = batch;
         this.journal = journal;
         this.future = future;
      }
   }

}
