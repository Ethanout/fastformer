package io.github.fastformer.server.history;

import io.github.fastformer.server.history.compaction.BlockHistoryCompactionRules;

import io.github.fastformer.server.history.compaction.HistoryCompactionPolicy;
import io.github.fastformer.server.history.entry.BlockEditHistoryMetadata;
import io.github.fastformer.server.history.entry.HistoryReplacement;
import io.github.fastformer.server.history.entry.WorldChangeBatch;
import io.github.fastformer.server.history.entry.HistoryEntry;
import io.github.fastformer.server.history.replay.EntryReplayRegistry;
import io.github.fastformer.server.history.replay.BlockReplayJournal;
import io.github.fastformer.server.history.memory.BlockEditHistoryGesture;
import io.github.fastformer.server.history.memory.HistoryMemoryCache;
import io.github.fastformer.server.history.memory.HistoryOrderCatalog;
import io.github.fastformer.server.history.memory.HistoryPageLoadPlan;
import io.github.fastformer.server.history.replay.HistoryReplayTask;
import io.github.fastformer.server.history.vanilla.WorldHistoryEvents;

import com.mojang.logging.LogUtils;
import io.github.fastformer.core.gate.Gate;
import io.github.fastformer.fastplace.settings.FastPlaceSettings;
import io.github.fastformer.server.task.TaskCancellationResult;
import io.github.fastformer.server.task.WorldTask;
import io.github.fastformer.server.feedback.PlayerMessages;
import io.github.fastformer.server.task.WorldTaskContext;
import io.github.fastformer.server.recovery.RecoverySystem;
import io.github.fastformer.fastplace.world.snapshot.ReversibleBlockSnapshot;
import io.github.fastformer.server.history.rollback.RollbackRequest;
import io.github.fastformer.server.history.rollback.RollbackQueue;
import io.github.fastformer.server.recovery.JournalPreparation;
import io.github.fastformer.server.recovery.PersistentRecoveryJournal;
import io.github.fastformer.server.recovery.WorldRecoverySnapshot;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
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
 * stack anymore. The service also serializes undo/redo/recovery writes and
 * performs an expected-state check before every write.
 */
public final class HistoryService implements AutoCloseable {
   private static final Logger LOGGER = LogUtils.getLogger();
   public static final int DEFAULT_LIMIT = 200;
   public static final int MAX_LIMIT = 800;
   /** Per-server guard against disconnected players retaining large histories. */
   static final long MAX_BYTES_PER_SERVER = 1024L * 1024L * 1024L;
   static final int MAX_IDLE_OWNERS = 128;
   private final Map<UUID, OwnerHistory> owners = new LinkedHashMap<>();
   private final BlockHistoryRecorder blockRecords = new BlockHistoryRecorder(this);
   private boolean closed;

   private final HistoryReplayTask.HistoryAccess replayAccess = new HistoryReplayTask.HistoryAccess() {
      @Override
      public HistoryReplayTask.PageState requestPage(WorldTaskContext context, boolean undo, int remaining) {
         OwnerHistory owner = ownerState(context.owner());
         if (owner.loading.pages().hasPendingPageLoad()) {
            return HistoryReplayTask.PageState.WAITING;
         }
         if (owner.loading.pages().failedPagePlan().isPresent()) {
            owner.loading.pages().clearFailedPagePlan();
            return HistoryReplayTask.PageState.FAILED;
         }
         if (remaining > 0) {
            Optional<HistoryPageLoadPlan> nextPage = HistoryPageLoadPlan.create(
               undo, remaining, List.of(), owner.historyLimit, owner.order()
            );
            if (nextPage.isPresent()) {
               HistoryPageLoadPlan plan = nextPage.orElseThrow();
               owner.loading.startPage(context.owner(), plan, repository);
               return HistoryReplayTask.PageState.WAITING;
            }
         }
         return HistoryReplayTask.PageState.EXHAUSTED;
      }

      @Override
      public void commit(WorldTaskContext context, HistoryMemoryCache<HistoryEntry> history, boolean undo) {
         HistoryEntry committed = history.commit(undo);
         OwnerHistory owner = ownerState(context.owner());
         owner.order().commit(undo, committed.operationId());
         ServerPlayer player = context.onlinePlayer();
         if (player == null) {
            trim(owner.historyLimit, history);
         } else {
            owner.trimToPlayerSetting(player, history);
         }
         owner.order().trim(owner.historyLimit);
         owner.publishIndex(context, repository);
      }
   };

   private final Gate<ServerPlayer> available;
   private final Gate<ServerPlayer> activity;
   private final HistoryRepository<HistoryEntry> repository;
   private final EntryReplayRegistry replayTypes;
   private final java.util.function.BiConsumer<WorldTaskContext, String> recoveryFeedback;

   public HistoryService(Gate<ServerPlayer> available, Gate<ServerPlayer> activity, HistoryRepository<HistoryEntry> repository) {
      this(available, activity, repository, EntryReplayRegistry.standard());
   }

   public HistoryService(Gate<ServerPlayer> available, Gate<ServerPlayer> activity, HistoryRepository<HistoryEntry> repository,
      EntryReplayRegistry replayTypes) {
      this(available, activity, repository, replayTypes, (context, key) -> context.actionBar(PlayerMessages.text(key)));
   }

   HistoryService(Gate<ServerPlayer> available, Gate<ServerPlayer> activity, HistoryRepository<HistoryEntry> repository,
      java.util.function.BiConsumer<WorldTaskContext, String> recoveryFeedback) {
      this(available, activity, repository, EntryReplayRegistry.standard(), recoveryFeedback);
   }

   private HistoryService(Gate<ServerPlayer> available, Gate<ServerPlayer> activity, HistoryRepository<HistoryEntry> repository,
      EntryReplayRegistry replayTypes, java.util.function.BiConsumer<WorldTaskContext, String> recoveryFeedback) {
      this.available = Objects.requireNonNull(available, "available gate");
      this.activity = Objects.requireNonNull(activity, "activity gate");
      this.repository = Objects.requireNonNull(repository, "repository");
      this.replayTypes = Objects.requireNonNull(replayTypes, "replay types");
      this.recoveryFeedback = Objects.requireNonNull(recoveryFeedback, "recoveryFeedback");
   }

   HistoryRepository<HistoryEntry> repository() {
      return repository;
   }

   /** Failed capture transfers nothing; the caller restores or hands its snapshots to recovery. */
   public boolean record(
      ServerPlayer player,
      ServerLevel level,
      ArrayDeque<ReversibleBlockSnapshot> changes,
      Map<BlockPos, ReversibleBlockSnapshot> after
   ) {
      if (player == null || level == null || changes == null || changes.isEmpty()) {
         return false;
      }
      Optional<WorldChangeBatch> captured;
      try {
         captured = WorldChangeBatch.capturePairsByPos(level, changes, after);
      } catch (RuntimeException | OutOfMemoryError exception) {
         LOGGER.error("Unable to capture world history for {} in {}; the transaction must restore its snapshots",
            player.getUUID(), level.dimension().location(), exception);
         return false;
      }
      if (captured.isEmpty()) {
         LOGGER.warn("World history capture for {} in {} produced no batch; the transaction must restore its snapshots",
            player.getUUID(), level.dimension().location());
         return false;
      }
      WorldChangeBatch batch = captured.orElseThrow();
      recordEntry(player, batch);
      return true;
   }

   public boolean commitPreparedOperation(
      ServerPlayer player,
      Optional<WorldChangeBatch> captured,
      PersistentRecoveryJournal journal
   ) {
      return commitPreparedOperation(new WorldTaskContext(player.getServer(), player.getUUID()), captured, journal);
   }

   public boolean commitPreparedOperation(
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

   public JournalPreparation pollPreparedOperation(
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
      OwnerHistory owner = ownerState(context.owner());
      return owner.pollCommit(context, batch, journal, repository);
   }

   /** Records an already completed, admitted edit. Its codec and replay implementation must be registered. */
   public void recordEntry(ServerPlayer player, HistoryEntry batch) {
      ownerState(player.getUUID()).compactionLimits = FastPlaceSettings.load(player).tinkerHistoryMergeLimits();
      addBatch(new WorldTaskContext(player.getServer(), player.getUUID()), batch);
   }

   void addBatch(WorldTaskContext context, HistoryEntry batch) {
      replayTypes.validate(batch);
      if (batch.operationId() == null) batch = batch.withOperationId(UUID.randomUUID());
      OwnerHistory owner = ownerState(context.owner());
      HistoryMemoryCache<HistoryEntry> history = owner.history();
      history.addNew(batch);
      ServerPlayer player = context.onlinePlayer();
      // Keep all batches in memory while an earlier save is unresolved. The
      // recovery snapshot must still contain the failed batch and any newer work.
      if (owner.persistence.settled()) {
         if (player == null) {
            trim(owner.historyLimit, history);
         } else {
            owner.trimToPlayerSetting(player, history);
         }
      }
      owner.publishNew(context, batch, repository);
      WorldHistoryEvents.send(context, batch, io.github.fastformer.protocol.s2c.world.WorldHistoryEventPayload.Kind.RECORD, true);
   }

   public boolean requestUndo(ServerPlayer player, int count) {
      return request(player, true, count);
   }

   public UUID latestOperationId(ServerPlayer player) {
      var batch = ownerState(player.getUUID()).history().peek(true);
      return batch == null ? null : batch.operationId();
   }

   public boolean recordTinker(ServerPlayer player, ServerLevel level,
      ArrayDeque<ReversibleBlockSnapshot> before, Map<BlockPos, ReversibleBlockSnapshot> after,
      BlockEditHistoryMetadata metadata) {
      return blockRecords.tinker(player, level, before, after, metadata);
   }

   /** Extends only this held gesture's newest record; each step uses normal persistence. */
   public boolean recordGesture(ServerPlayer player, ServerLevel level,
      ArrayDeque<ReversibleBlockSnapshot> before, Map<BlockPos, ReversibleBlockSnapshot> after,
      BlockEditHistoryGesture gesture) {
      return blockRecords.gesture(player, level, before, after, gesture);
   }

   /** Cancels an exact inverse of the latest write without adding an undoable reversal. */
   public boolean recordRestoration(ServerPlayer player, ServerLevel level,
      ArrayDeque<ReversibleBlockSnapshot> before, Map<BlockPos, ReversibleBlockSnapshot> after, UUID operationId) {
      return blockRecords.restoration(player, level, before, after, operationId);
   }

   public boolean respondToConflict(ServerPlayer player,
      io.github.fastformer.protocol.c2s.world.HistoryConflictResponsePayload response) {
      if (response.choice() != io.github.fastformer.protocol.c2s.world.HistoryConflictResponsePayload.Choice.CANCEL
         && !available.test(player).allowed()) return false;
      var owner = ownerIfPresent(player.getUUID());
      return owner != null && owner.active != null && owner.active.respondToConflict(
         new WorldTaskContext(player.getServer(), player.getUUID()), response.token(), response.choice());
   }

   public void resumeDiskCleanup(MinecraftServer server) {
      repository.resumeCleanup(java.util.Set.copyOf(owners.keySet()));
   }

   public void awaitDiskWritesOnShutdown(MinecraftServer server) {
      try {
         CompletableFuture<Void> finalSnapshots = saveDirtyHistoriesOnShutdown(server);
         CompletableFuture.allOf(finalSnapshots, repository.pendingWrites()).get(30, TimeUnit.SECONDS);
      } catch (InterruptedException failure) {
         Thread.currentThread().interrupt();
         LOGGER.error("Server shutdown was interrupted while waiting for history writes", failure);
      } catch (ExecutionException | TimeoutException failure) {
         LOGGER.error("History writes did not finish before the shutdown deadline", failure);
      }
   }

   CompletableFuture<Void> saveDirtyHistoriesOnShutdown(MinecraftServer server) {
      java.util.List<CompletableFuture<Void>> saves = new java.util.ArrayList<>();
      for (var entry : owners.entrySet()) {
         OwnerHistory owner = entry.getValue();
         if (owner.history == null || owner.persistence.settled()) continue;
         // Capture before clearServer releases the only in-memory undo/redo stacks.
         HistoryOrderCatalog order = owner.order();
         saves.add(repository.publishSnapshot(entry.getKey(),
            owner.history.snapshot(true), owner.history.snapshot(false),
            order.order(true), order.order(false)));
      }
      return CompletableFuture.allOf(saves.toArray(CompletableFuture[]::new));
   }

   public boolean requestRedo(ServerPlayer player, int count) {
      return request(player, false, count);
   }

   public void deferUndoAfterRecovery(ServerPlayer player, int count) {
      if (player == null || count <= 0) {
         return;
      }
      deferUndoAfterRecovery(player.getUUID(), count);
   }

   void deferUndoAfterRecovery(UUID id, int count) {
      if (id == null || count <= 0) {
         return;
      }
      OwnerHistory owner = ownerState(id);
      owner.deferredUndo = Math.min(MAX_LIMIT, owner.deferredUndo + Math.clamp(count, 1, MAX_LIMIT));
   }

   public static int remainingAfterUncommittedUndo(int requestedCount) {
      return Math.max(0, Math.min(MAX_LIMIT, requestedCount) - 1);
   }

   private boolean request(ServerPlayer player, boolean undo, int count) {
      UUID id = player.getUUID();
      OwnerHistory owner = ownerState(id);
      int requested = Math.clamp(count, 1, MAX_LIMIT);
      if (owner.loading.snapshotFailed()) {
         if (!owner.loading.snapshotPending() && available.test(player).allowed()) {
            startHistorySnapshotLoad(player, owner);
            PlayerMessages.actionBar(player, PlayerMessages.text("fastformer.message.history_loading_older"));
         } else {
            PlayerMessages.actionBar(player, PlayerMessages.text("fastformer.message.history_load_failed"));
         }
         return false;
      }
      if (!activity.test(player).allowed()) {
         PlayerMessages.actionBar(player, PlayerMessages.text("fastformer.message.history_wait_task"));
         return false;
      }
      HistoryMemoryCache<HistoryEntry> history = owner.history;
      if (history == null) {
         PlayerMessages.actionBar(player, PlayerMessages.text(
            undo ? "fastformer.message.history_no_undo" : "fastformer.message.history_no_redo"
         ));
         return false;
      }
      owner.loading.pages().clearFailedPagePlan();
      Optional<HistoryPageLoadPlan> pagePlan = history.size(undo) == 0
         ? HistoryPageLoadPlan.create(undo, requested, history.operationIds(undo), owner.historyLimit, owner.order())
         : Optional.empty();
      if (pagePlan.isPresent()) {
         startHistoryPageLoad(player, owner, pagePlan.orElseThrow());
         return true;
      }
      if (history.size(undo) == 0) {
         PlayerMessages.actionBar(player, PlayerMessages.text(
            undo ? "fastformer.message.history_no_undo" : "fastformer.message.history_no_redo"
         ));
         return false;
      }
      HistoryEntry next = history.peek(undo);
      // A history batch is owned by its target dimension, not by the
      // player's current dimension.  Accept the single undo request even
      // after a dimension change; tickOwner will retain it in WORLD_UNLOADED
      // state until that level is available and then continue automatically.
      if (next != null && RecoverySystem.of(player.getServer()).writeLeases().busy(next.dimension())) {
         PlayerMessages.actionBar(player, PlayerMessages.text("fastformer.message.world_write_waiting"));
         return false;
      }
      owner.active = new HistoryReplayTask(
         replayAccess,
         history,
         undo,
         requested,
         FastPlaceSettings.load(player).placementUpdateMode(), replayTypes
      );
      PlayerMessages.actionBar(
         player,
         PlayerMessages.text(undo ? "fastformer.message.history_start_undo" : "fastformer.message.history_start_redo", requested)
      );
      return true;
   }

   /** Accepts the complete ownership transfer for one interrupted writer. */
   public boolean rollback(RollbackRequest request) {
      OwnerHistory owner = ownerState(request.context().owner());
      return owner.recovery.accept(request, busy(request.context().owner()),
         ready -> owner.active = blockRecoveryTask(ready), recoveryFeedback);
   }

   /**
    * Takes ownership after a writer is detached. Recovery waits for commit
    * compression. The writer's memory is released here. Its world lease stays
    * with the owner until recovery finishes or unused journal cleanup succeeds.
    */
   public TaskCancellationResult acceptStoppedTask(WorldTaskContext context, WorldTask task) {
      return acceptTransferredTask(context, task, task.stopAndTransferRecovery());
   }

   public TaskCancellationResult acceptTransferredTask(
      WorldTaskContext context, WorldTask task, WorldRecoverySnapshot snapshot
   ) {
      return acceptTransferredRecovery(
         context,
         task.dimension(),
         snapshot,
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
   public TaskCancellationResult acceptTransferredRecovery(
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
         return rollback(new RollbackRequest(context, dimension, recovery, journal))
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
   private void releaseTaskMemoryQuietly(Runnable releaseTaskMemory) {
      if (releaseTaskMemory == null) {
         return;
      }
      try {
         releaseTaskMemory.run();
      } catch (RuntimeException | OutOfMemoryError exception) {
         LOGGER.error("FastFormer could not release task memory after a recovery hand-off", exception);
      }
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
   public boolean acceptShortTransactionRecovery(
      WorldTaskContext context,
      ResourceKey<Level> dimension,
      ArrayDeque<ReversibleBlockSnapshot> changes,
      Map<BlockPos, ReversibleBlockSnapshot> after
   ) {
      if (context == null || dimension == null || changes == null || changes.isEmpty()) {
         return false;
      }
      if (rollback(RollbackRequest.ready(context, dimension, changes, after, null))) {
         return true;
      }
      // rollback declines only for input it cannot own. The snapshots
      // already belong to this call, so queue them instead of returning them to
      // a caller that would drop the only record of a partial write.
      ownerState(context.owner()).recovery.enqueue(RollbackRequest.ready(context, dimension, changes, after, null));
      // The queue owns the capture now, so the announcement must stay harmless.
      notifyQuietly(context, "fastformer.message.task_recovery_blocked");
      return true;
   }

   /** Progress feedback must never decide ownership, so a failed message stays harmless. */
   private void notifyQuietly(WorldTaskContext context, String key) {
      try {
         recoveryFeedback.accept(context, key);
      } catch (RuntimeException | OutOfMemoryError exception) {
         LOGGER.warn("FastFormer could not show the recovery message {}", key, exception);
      }
   }

   public boolean busy(ServerPlayer player) {
      return busy(player.getUUID());
   }

   public boolean busy(UUID id) {
      OwnerHistory owner = ownerIfPresent(id);
      return owner != null && owner.busy();
   }

   public boolean snapshotRetryAvailable(ServerPlayer player) {
      OwnerHistory owner = ownerIfPresent(player.getUUID());
      return owner != null && owner.loading.snapshotFailed() && !owner.loading.snapshotPending();
   }

   public boolean restoreActive(ServerPlayer player) {
      return busy(player);
   }

   /** Cancels an ordinary undo/redo task. Recovery is never user-paused. */
   public boolean cancel(ServerPlayer player) {
      return cancel(player.getUUID());
   }

   boolean cancel(UUID id) {
      return ownerState(id).cancelReplay();
   }

   public void remove(ServerPlayer player) {
      detachOwner(player.getUUID());
   }

   void detachOwner(UUID id) {
      ownerState(id).detach();
      pruneDetachedOwners();
   }

   /** Clears all in-memory state when a server instance is stopping. */
   public void clear() {
      for (OwnerHistory owner : owners.values()) {
         owner.discard();
      }
      owners.clear();
   }

   public void trimToSetting(ServerPlayer player, int limit) {
      UUID id = player.getUUID();
      OwnerHistory owner = ownerState(id);
      owner.historyLimit = boundedLimit(limit);
      if ((owner.history == null || owner.loading.snapshotFailed()) && !owner.loading.snapshotPending()) {
         startHistorySnapshotLoad(player, owner);
      }
      // Do not evict the batch currently being applied; HistoryReplayTask keeps a
      // reference to it until commit. The completion path trims again after
      // the active task is removed.
      if (owner.active != null || owner.pendingTask != null) {
         return;
      }
      HistoryMemoryCache<HistoryEntry> history = owner.history;
      if (history != null) {
         trim(limit, history);
         owner.order().trim(owner.historyLimit);
         owner.publishIndex(new WorldTaskContext(player.getServer(), id), repository);
      }
   }

   /**
    * Advances a pending history/recovery task.  Returning true means the
    * caller must not tick another world-writing task for this player in the
    * same server tick.
    */
   /** Advances world-owned history/recovery tasks even when their owner is offline. */
   public void tickWorld(MinecraftServer server) {
      for (Map.Entry<UUID, OwnerHistory> entry : owners.entrySet()) {
         if (entry.getValue().needsTick()) {
            tickOwner(new WorldTaskContext(server, entry.getKey()));
         }
      }
   }

   boolean tickOwner(WorldTaskContext context) {
      UUID owner = context.owner();
      OwnerHistory ownerState = ownerState(owner);
      if (!context.writesAllowed()) {
         // Discard the continuation before a completed page can resume it.
         ownerState.loading.pages().cancelPageLoad();
      }
      attachLoadedHistory(context);
      attachHistoryPage(context);
      ownerState.attachPending(context, this::blockRecoveryTask);
      if (context.onlinePlayer() != null) {
         ownerState.detached = false;
      }
      HistoryReplayTask task = ownerState.active;
      if (task == null) {
         ownerState.retryPersistence(context, repository);
         compactHistory(context, ownerState);
         ServerPlayer player = context.onlinePlayer();
         return player != null && runDeferredUndo(player);
      }
      if (ownerState.tickReplay(context, replayAccess)) {
         HistoryMemoryCache<HistoryEntry> history = ownerState.history;
         ServerPlayer player = context.onlinePlayer();
         if (history != null) {
            if (player == null) trim(ownerState.historyLimit, history);
            else ownerState.trimToPlayerSetting(player, history);
         }
         if (player != null) runDeferredUndo(player);
      }
      return true;
   }

   public void requestCompaction(ServerPlayer player) {
      OwnerHistory owner = ownerState(player.getUUID());
      owner.compactionLimits = FastPlaceSettings.load(player).tinkerHistoryMergeLimits();
      owner.compaction.request();
   }

   private void compactHistory(WorldTaskContext context, OwnerHistory owner) {
      if (owner.detached) {
         owner.compaction.clear();
         return;
      }
      HistoryCompactionPolicy policy = HistoryStorageConfig.compactionPolicy();
      boolean ready = !owner.busy() && owner.persistence.settled()
         && owner.durableCommit == null;
      try {
         owner.compaction.poll(owner.history(), owner.order(), new BlockHistoryCompactionRules(owner.compactionLimits, policy), ready, command -> io.github.fastformer.server.lifecycle.ServerBackgroundTasks.serial(context.server(), "history-compaction", 32).execute(command))
            .ifPresent(change -> applyCompaction(context, owner, change));
      } catch (java.util.concurrent.CompletionException exception) {
         LOGGER.warn("Micro-operation history compaction failed for {}; original history retained", context.owner(), exception);
      }
   }

   void applyCompaction(WorldTaskContext context, OwnerHistory owner, HistoryReplacement<HistoryEntry> change) {
      HistoryOrderCatalog replacementOrder = owner.order().copy();
      replacementOrder.replaceUndo(change);
      owner.history().replaceUndo(change);
      owner.historyOrder = replacementOrder;
      if (change.batch().isPresent()) {
         owner.trackPersistence(context, false, repository.publishNewBatch(context.owner(), change.batch().orElseThrow(),
            replacementOrder.order(true), replacementOrder.order(false)));
      } else {
         owner.publishIndex(context, repository);
      }
   }

   private boolean runDeferredUndo(ServerPlayer player) {
      UUID id = player.getUUID();
      OwnerHistory owner = ownerState(id);
      int count = owner.deferredUndo;
      if (count <= 0) {
         return false;
      }
      HistoryMemoryCache<HistoryEntry> history = owner.history;
      HistoryEntry next = history == null ? null : history.peek(true);
      if (next != null && RecoverySystem.of(player.getServer()).writeLeases().busy(next.dimension())) {
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

   int takeDeferredUndo(UUID id) {
      return ownerState(id).takeDeferredUndo();
   }

   void attachLoadedHistory(WorldTaskContext context) {
      OwnerHistory owner = ownerState(context.owner());
      try {
         owner.loading.attachSnapshot(owner::mergeSnapshot);
      } catch (RuntimeException failure) {
         context.chat(PlayerMessages.text("fastformer.message.history_load_failed"));
         LOGGER.error("Could not load durable history for {}", context.owner(), failure);
      }
   }

   private void startHistorySnapshotLoad(ServerPlayer player, OwnerHistory owner) {
      owner.compactionLimits = FastPlaceSettings.load(player).tinkerHistoryMergeLimits();
      owner.loading.startSnapshot(repository.loadSnapshot(
         player.getUUID(), owner.historyLimit, OwnerHistory.MAX_MEMORY_BYTES));
   }

   private void attachHistoryPage(WorldTaskContext context) {
      OwnerHistory owner = ownerState(context.owner());
      Optional<HistoryPageLoadPlan> completed;
      try {
         completed = owner.loading.attachPage(page -> {
            HistoryMemoryCache<HistoryEntry> history = owner.history();
            history.merge(page.plan().undoDirection(), page.batches());
            trim(owner.historyLimit, history);
         });
      } catch (RuntimeException failure) {
         context.chat(PlayerMessages.text("fastformer.message.history_load_failed"));
         LOGGER.error("Could not load or merge an older history page for {}", context.owner(), failure);
         return;
      }
      ServerPlayer player = context.onlinePlayer();
      if (completed.isPresent() && player != null && owner.active == null) {
         HistoryPageLoadPlan plan = completed.orElseThrow();
         request(player, plan.undoDirection(), plan.requestedCount());
      }
   }

   private void startHistoryPageLoad(ServerPlayer player, OwnerHistory owner, HistoryPageLoadPlan plan) {
      owner.loading.startPage(player.getUUID(), plan, repository);
      PlayerMessages.actionBar(player, PlayerMessages.text("fastformer.message.history_loading_older"));
   }
   private void trim(int limit, HistoryMemoryCache<HistoryEntry> history) {
      history.trim(boundedLimit(limit), OwnerHistory.MAX_MEMORY_BYTES);
   }

   OwnerHistory ownerIfPresent(UUID owner) {
      return owners.get(owner);
   }

   int ownerCount() {
      return owners.size();
   }

   OwnerHistory ownerState(UUID owner) {
      if (closed) throw new IllegalStateException("History service is closed");
      return owners.computeIfAbsent(owner, ignored -> new OwnerHistory());
   }

   @Override
   public void close() {
      if (closed) return;
      closed = true;
      try {
         clear();
      } finally {
         repository.close();
      }
   }

   private void pruneDetachedOwners() {
      long total = 0L;
      for (OwnerHistory state : owners.values()) {
         total = saturatedAdd(total, state.historyBytes());
      }
      if (ownerCount() <= MAX_IDLE_OWNERS && total <= MAX_BYTES_PER_SERVER) return;
      var iterator = owners.entrySet().iterator();
      while (iterator.hasNext() && (ownerCount() > MAX_IDLE_OWNERS || total > MAX_BYTES_PER_SERVER)) {
         OwnerHistory state = iterator.next().getValue();
         if (!state.canEvict()) continue;
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

   private HistoryReplayTask blockRecoveryTask(RollbackQueue.ReadyRecovery ready) {
      var batch = ready.batch();
      var journal = ready.journal();
      UUID operation = journal != null && journal.operationId() != null ? journal.operationId()
         : batch == null ? null : batch.operationId();
      return HistoryReplayTask.recovery(replayAccess, batch, BlockReplayJournal.recovery(journal), replayTypes, operation);
   }

}
