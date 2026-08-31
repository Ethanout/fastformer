package io.github.fastformer.fastplace.world;

import io.github.fastformer.fastplace.FastPlaceManager;
import io.github.fastformer.fastplace.FastPlaceMessages;
import io.github.fastformer.fastplace.FastPlaceSettings;
import io.github.fastformer.fastplace.GeometryManager;
import io.github.fastformer.fastplace.OperationManager;
import io.github.fastformer.fastplace.PlacementUpdateMode;
import java.util.ArrayDeque;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import com.mojang.logging.LogUtils;
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
   private static final Logger LOGGER = LogUtils.getLogger();
   public static final int DEFAULT_LIMIT = 200;
   public static final int MAX_LIMIT = 800;
   /** A second budget prevents 800 large batches from retaining unbounded NBT. */
   private static final long MAX_BYTES_PER_PLAYER = 256L * 1024L * 1024L;
   private static final Map<UUID, History> HISTORIES = new HashMap<>();
   private static final Map<UUID, HistoryTask> ACTIVE = new HashMap<>();
   /** Tasks retained after a transient tick failure and retried by the world scheduler. */
   private static final Map<UUID, HistoryTask> PENDING_TASKS = new HashMap<>();
   private static final Map<UUID, PendingRecovery> PENDING_RECOVERY = new HashMap<>();
   /** Recovery captures waiting for a dimension, writer, or memory budget. */
   private static final Map<UUID, ArrayDeque<RecoveryCapture>> PENDING_CAPTURES = new HashMap<>();
   /** Completed writes waiting to be recorded because their level is unloaded. */
   private static final Map<UUID, PendingRecord> PENDING_RECORDS = new HashMap<>();
   /** Remaining command undo count after an in-flight writer is cancelled. */
   private static final Map<UUID, Integer> DEFERRED_UNDO = new HashMap<>();
   /** Last configured history limit for each owner while this server instance lives. */
   private static final Map<UUID, Integer> OWNER_LIMITS = new HashMap<>();

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
         PENDING_RECORDS.put(player.getUUID(), new PendingRecord(level.dimension(), changes, Map.of()));
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
         PENDING_RECORDS.put(player.getUUID(), new PendingRecord(dimension, changes, safeAfter));
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

   private static void addBatch(ServerPlayer player, WorldChangeBatch batch) {
      addBatch(new WorldTaskContext(player.getServer(), player.getUUID()), batch);
   }

   private static void addBatch(WorldTaskContext context, WorldChangeBatch batch) {
      History history = HISTORIES.computeIfAbsent(context.owner(), ignored -> new History());
      history.undo.addFirst(batch);
      history.undoBytes += batch.estimatedBytes();
      history.clearRedo();
      ServerPlayer player = context.onlinePlayer();
      if (player == null) {
         trim(ownerLimit(context.owner()), history);
      } else {
         trimSafely(player, history);
      }
   }

   public static boolean requestUndo(ServerPlayer player, int count) {
      return request(player, true, count);
   }

   public static boolean requestRedo(ServerPlayer player, int count) {
      return request(player, false, count);
   }

   public static void deferUndoAfterRecovery(ServerPlayer player, int count) {
      if (player == null || count <= 0) {
         return;
      }
      DEFERRED_UNDO.merge(
         player.getUUID(),
         Math.clamp(count, 1, MAX_LIMIT),
         (left, right) -> Math.min(MAX_LIMIT, left + right)
      );
   }

   public static int remainingAfterUncommittedUndo(int requestedCount) {
      return Math.max(0, Math.min(MAX_LIMIT, requestedCount) - 1);
   }

   private static boolean request(ServerPlayer player, boolean undo, int count) {
      UUID id = player.getUUID();
      int requested = Math.clamp(count, 1, MAX_LIMIT);
      if (undo
         && !FastPlaceManager.active(player)
         && !OperationManager.active(player)
         && !GeometryManager.active(player)
         && !FastPlaceManager.taskActive(player)
         && !OperationManager.taskActive(player)
         && resumeRecovery(player)) {
         deferUndoAfterRecovery(player, remainingAfterUncommittedUndo(requested));
         return true;
      }
      if (busy(player)
         || FastPlaceManager.taskActive(player)
         || OperationManager.taskActive(player)
         || FastPlaceManager.active(player)
         || OperationManager.active(player)
         || GeometryManager.active(player)) {
         FastPlaceMessages.actionBar(player, FastPlaceMessages.text("fastformer.message.history_wait_task"));
         return false;
      }
      History history = HISTORIES.get(id);
      if (history == null) {
         FastPlaceMessages.actionBar(player, FastPlaceMessages.text(
            undo ? "fastformer.message.history_no_undo" : "fastformer.message.history_no_redo"
         ));
         return false;
      }
      ArrayDeque<WorldChangeBatch> source = undo ? history.undo : history.redo;
      if (source.isEmpty()) {
         FastPlaceMessages.actionBar(player, FastPlaceMessages.text(
            undo ? "fastformer.message.history_no_undo" : "fastformer.message.history_no_redo"
         ));
         return false;
      }
      WorldChangeBatch next = source.peekFirst();
      if (next != null && !player.serverLevel().dimension().equals(next.dimension())) {
         FastPlaceMessages.actionBar(player, FastPlaceMessages.text("fastformer.message.history_return_dimension"));
         return false;
      }
      if (next != null && WorldWriteCoordinator.busy(player.getServer(), next.dimension())) {
         FastPlaceMessages.actionBar(player, FastPlaceMessages.text("fastformer.message.world_write_waiting"));
         return false;
      }
      ACTIVE.put(id, new HistoryTask(history, undo, requested));
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
      ArrayDeque<ReversibleBlockSnapshot> changes,
      Map<BlockPos, ReversibleBlockSnapshot> after,
      PersistentRecoveryJournal journal
   ) {
      return startRollback(context, dimension, changes, after, context.level(dimension), journal);
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
            enqueueCapture(context.owner(), new RecoveryCapture(dimension, changes, safeAfter, journal));
         }
         return true;
      }
      if (changes.isEmpty()) {
         return resolveAlreadyRestored(context, dimension, changes, safeAfter, journal);
      }
      if (!WorldOperationMemory.canPrepare(changes.size())) {
         enqueueCapture(context.owner(), new RecoveryCapture(dimension, changes, safeAfter, journal));
         context.actionBar(FastPlaceMessages.text("fastformer.message.operation_memory_unsafe"));
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
         ACTIVE.put(context.owner(), HistoryTask.recovery(captured.orElseThrow(), journal));
         context.actionBar(FastPlaceMessages.text("fastformer.message.restore_cancelled_task"));
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
      enqueueCapture(context.owner(), new RecoveryCapture(dimension, changes, safeAfter, journal));
      context.actionBar(FastPlaceMessages.text("fastformer.message.restore_cancelled_task"));
      return true;
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
      enqueueCapture(context.owner(), new RecoveryCapture(dimension, changes, after, journal));
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
      return ACTIVE.containsKey(id)
         || PENDING_TASKS.containsKey(id)
         || PENDING_RECOVERY.containsKey(id)
         || PENDING_CAPTURES.containsKey(id)
         || PENDING_RECORDS.containsKey(id)
         || DEFERRED_UNDO.containsKey(id);
   }

   public static boolean restoreActive(ServerPlayer player) {
      return busy(player);
   }

   /** Cancels a recovery/undo task when the user explicitly quits it. */
   public static boolean cancel(ServerPlayer player) {
      UUID id = player.getUUID();
      HistoryTask task = ACTIVE.get(id);
      if (task == null) {
         task = PENDING_TASKS.get(id);
      }
      if (task == null) {
         // A recovery with no loaded dimension cannot be safely discarded:
         // it may represent blocks already removed from the world. Keep the
         // queue paused until the player (or the dimension) is available.
         boolean pending = PENDING_RECOVERY.containsKey(id)
            || PENDING_CAPTURES.containsKey(id)
            || PENDING_RECORDS.containsKey(id);
         if (pending) {
            FastPlaceMessages.actionBar(player, FastPlaceMessages.text("fastformer.message.restore_paused"));
         }
         return pending;
      }
      task.requestCancel();
      return true;
   }

   public static boolean resumeRecovery(ServerPlayer player) {
      UUID id = player.getUUID();
      if (ACTIVE.containsKey(id)) {
         return false;
      }
      PendingRecovery pending = PENDING_RECOVERY.remove(id);
      if (pending == null) {
         return false;
      }
      ACTIVE.put(id, HistoryTask.recovery(pending.batch(), pending.journal()));
      FastPlaceMessages.actionBar(player, FastPlaceMessages.text("fastformer.message.restore_cancelled_task"));
      return true;
   }

   public static void remove(ServerPlayer player) {
      detachOwner(player.getUUID());
   }

   static void detachOwner(UUID id) {
      HistoryTask active = ACTIVE.get(id);
      if (active != null && !active.recovery) {
         active.requestCancel();
      }
      HistoryTask pending = PENDING_TASKS.get(id);
      if (pending != null && !pending.recovery) {
         pending.requestCancel();
      }
      PendingRecovery pausedRecovery = PENDING_RECOVERY.remove(id);
      if (pausedRecovery != null && !ACTIVE.containsKey(id)) {
         ACTIVE.put(id, HistoryTask.recovery(pausedRecovery.batch(), pausedRecovery.journal()));
      }
      // Completed world history belongs to the owner UUID for the lifetime of
      // this server instance. Keep it across logout so reconnect timing cannot
      // decide whether an already-finished operation remains undoable.
      DEFERRED_UNDO.remove(id);
   }

   /** Clears all in-memory state when a server instance is stopping. */
   public static void clearServer() {
      for (HistoryTask task : ACTIVE.values()) {
         task.cancelJournalPreparation();
      }
      for (HistoryTask task : PENDING_TASKS.values()) {
         task.cancelJournalPreparation();
      }
      HISTORIES.clear();
      ACTIVE.clear();
      PENDING_TASKS.clear();
      PENDING_RECOVERY.clear();
      PENDING_CAPTURES.clear();
      PENDING_RECORDS.clear();
      DEFERRED_UNDO.clear();
      OWNER_LIMITS.clear();
   }

   public static void trimToSetting(ServerPlayer player, int limit) {
      UUID id = player.getUUID();
      OWNER_LIMITS.put(id, boundedLimit(limit));
      // Do not evict the batch currently being applied; HistoryTask keeps a
      // reference to it until commit. The completion path trims again after
      // the active task is removed.
      if (ACTIVE.containsKey(id) || PENDING_TASKS.containsKey(id)) {
         return;
      }
      History history = HISTORIES.get(player.getUUID());
      if (history != null) {
         trim(limit, history);
      }
   }

   /**
    * Advances a pending history/recovery task.  Returning true means the
    * caller must not tick another world-writing task for this player in the
    * same server tick.
    */
   /** Advances world-owned history/recovery tasks even when their owner is offline. */
   public static void tickWorld(MinecraftServer server) {
      HashSet<UUID> owners = new HashSet<>();
      owners.addAll(ACTIVE.keySet());
      owners.addAll(PENDING_TASKS.keySet());
      owners.addAll(PENDING_CAPTURES.keySet());
      owners.addAll(PENDING_RECORDS.keySet());
      owners.addAll(DEFERRED_UNDO.keySet());
      for (UUID owner : owners) {
         tickOwner(new WorldTaskContext(server, owner));
      }
   }

   private static boolean tickOwner(WorldTaskContext context) {
      attachPending(context);
      UUID owner = context.owner();
      HistoryTask task = ACTIVE.get(owner);
      if (task == null) {
         ServerPlayer player = context.onlinePlayer();
         return player != null && runDeferredUndo(player);
      }
      if (!PersistentRecoveryJournal.writesAllowed() && !task.recovery) {
         // The global gate may close after an undo/redo was accepted. Do not
         // apply more history targets; requestCancel either discards its
         // unused journal or rolls its already-applied cells back.
         task.requestCancel();
      }
      boolean finished;
      try {
         finished = task.tick(context, WorldTaskBudget.forServerTick());
      } catch (RuntimeException | OutOfMemoryError exception) {
         // Never let a malformed block entity or callback crash the server.
         // Roll back any cells already touched by this task on the next tick.
         task.requestCancel();
         ACTIVE.remove(owner);
         PENDING_TASKS.put(owner, task);
         context.actionBar(FastPlaceMessages.text("fastformer.message.restore_failed_retry"));
         LOGGER.error("FastFormer history task failed for {} and was retained for recovery", owner, exception);
         return true;
      }
      if (finished) {
         ACTIVE.remove(owner);
         if (task.recovery && task.retainRecovery() && task.batch() != null) {
            if (retainRecoveryForManualResume(context.onlinePlayer() != null)) {
               PENDING_RECOVERY.put(owner, new PendingRecovery(task.batch(), task.journal()));
            } else {
               ACTIVE.put(owner, HistoryTask.recovery(task.batch(), task.journal()));
            }
         }
         History history = HISTORIES.get(owner);
         if (history != null) {
            ServerPlayer player = context.onlinePlayer();
            if (player == null) {
               trim(DEFAULT_LIMIT, history);
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
      Integer count = DEFERRED_UNDO.get(id);
      if (count == null || count <= 0) {
         return false;
      }
      if (ACTIVE.containsKey(id)
         || PENDING_TASKS.containsKey(id)
         || PENDING_RECOVERY.containsKey(id)
         || PENDING_CAPTURES.containsKey(id)
         || PENDING_RECORDS.containsKey(id)) {
         return true;
      }
      History history = HISTORIES.get(id);
      WorldChangeBatch next = history == null ? null : history.undo.peekFirst();
      if (next != null && WorldWriteCoordinator.busy(player.getServer(), next.dimension())) {
         return true;
      }
      DEFERRED_UNDO.remove(id);
      requestUndo(player, count);
      return true;
   }

   private static void attachPending(WorldTaskContext context) {
      UUID id = context.owner();
      if (ACTIVE.containsKey(id)) {
         return;
      }
      // Explicitly paused recovery batches are resumed only by Ctrl+Z or
      // /ff undo while the owner remains online. A queued rollback has
      // priority because it was created by a failed write and must finish
      // before any manually resumed history batch.
      if (PENDING_RECOVERY.containsKey(id) && !PENDING_CAPTURES.containsKey(id)) {
         return;
      }
      HistoryTask pendingTask = PENDING_TASKS.remove(id);
      if (pendingTask != null) {
         ACTIVE.put(id, pendingTask);
         return;
      }
      ArrayDeque<RecoveryCapture> captures = PENDING_CAPTURES.get(id);
      RecoveryCapture capture = captures == null ? null : captures.peekFirst();
      if (capture != null) {
         if (!WorldOperationMemory.canPrepare(capture.changes().size())) {
            return;
         }
         Optional<WorldChangeBatch> batch = materializeRecovery(context.server(), capture);
         if (batch.isPresent()) {
            removeCapture(id, captures);
            ACTIVE.put(id, HistoryTask.recovery(batch.orElseThrow(), capture.journal()));
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
            removeCapture(id, captures);
            releaseResolvedLease(context.server(), capture.dimension(), context.owner());
            context.actionBar(FastPlaceMessages.text("fastformer.message.restore_complete"));
         }
      }
      PendingRecord pendingRecord = PENDING_RECORDS.remove(id);
      if (pendingRecord != null) {
         if (!WorldOperationMemory.canPrepare(pendingRecord.changes().size())) {
            PENDING_RECORDS.put(id, pendingRecord);
            return;
         }
         Optional<WorldChangeBatch> batch = materializeRecord(context.server(), pendingRecord);
         if (batch.isPresent()) {
            addBatch(context, batch.orElseThrow());
         } else if (context.level(pendingRecord.dimension()) == null) {
            PENDING_RECORDS.put(id, pendingRecord);
         } else {
            ServerLevel level = context.level(pendingRecord.dimension());
            if (!allBefore(level, pendingRecord.changes())) {
               PENDING_RECORDS.put(id, pendingRecord);
            }
         }
      }
   }

   private static void enqueueCapture(UUID owner, RecoveryCapture capture) {
      PENDING_CAPTURES.computeIfAbsent(owner, ignored -> new ArrayDeque<>()).addLast(capture);
   }

   private static void removeCapture(UUID owner, ArrayDeque<RecoveryCapture> captures) {
      if (captures == null) {
         return;
      }
      captures.removeFirst();
      if (captures.isEmpty()) {
         PENDING_CAPTURES.remove(owner);
      }
   }

   private static boolean allBefore(ServerLevel level, ArrayDeque<ReversibleBlockSnapshot> changes) {
      if (level == null) {
         return false;
      }
      HashSet<Long> checked = new HashSet<>();
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
      ServerLevel level = server == null ? null : server.getLevel(capture.dimension());
      if (level == null) {
         return Optional.empty();
      }
      try {
         Optional<WorldChangeBatch> paired = WorldChangeBatch.capturePairsByPos(level, capture.changes(), capture.after());
         if (paired.isPresent()) {
            return paired;
         }
      } catch (RuntimeException | OutOfMemoryError ignored) {
         // Fall through to the live-state fallback below.
      }
      try {
         return WorldChangeBatch.capture(level, capture.changes());
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
         Optional<WorldChangeBatch> paired = WorldChangeBatch.capturePairsByPos(level, pending.changes(), pending.after());
         if (paired.isPresent()) {
            return paired;
         }
      } catch (RuntimeException | OutOfMemoryError ignored) {
         // Fall through to the live-state fallback below.
      }
      try {
         return WorldChangeBatch.capture(level, pending.changes());
      } catch (RuntimeException | OutOfMemoryError exception) {
         return Optional.empty();
      }
   }

   static void addBatchForTest(UUID owner, WorldChangeBatch batch) {
      addBatch(new WorldTaskContext(null, owner), batch);
   }

   static int undoSizeForTest(UUID owner) {
      History history = HISTORIES.get(owner);
      return history == null ? 0 : history.undo.size();
   }

   static void setOwnerLimitForTest(UUID owner, int limit) {
      OWNER_LIMITS.put(owner, boundedLimit(limit));
   }

   static void releaseResolvedLease(Object server, ResourceKey<Level> dimension, UUID owner) {
      WorldWriteCoordinator.release(server, dimension, owner);
   }

   private record RecoveryCapture(
      ResourceKey<Level> dimension,
      ArrayDeque<ReversibleBlockSnapshot> changes,
      Map<BlockPos, ReversibleBlockSnapshot> after,
      PersistentRecoveryJournal journal
   ) {
      private RecoveryCapture {
         // startRollback receives collections detached from the writer task.
         // Transfer them directly so an exceptional large recovery does not
         // double its peak heap before any block can be restored.
         changes = changes == null ? new ArrayDeque<>() : changes;
         after = after == null ? Map.of() : after;
      }
   }

   private record PendingRecovery(WorldChangeBatch batch, PersistentRecoveryJournal journal) {
   }

   private record PendingRecord(
      ResourceKey<Level> dimension,
      ArrayDeque<ReversibleBlockSnapshot> changes,
      Map<BlockPos, ReversibleBlockSnapshot> after
   ) {
      private PendingRecord {
         changes = changes == null ? new ArrayDeque<>() : changes;
         after = after == null ? Map.of() : after;
      }
   }

   private static void trim(ServerPlayer player, History history) {
      int limit = boundedLimit(FastPlaceSettings.load(player).worldUndoHistoryLimit());
      OWNER_LIMITS.put(player.getUUID(), limit);
      trim(limit, history);
   }

   private static void trimSafely(ServerPlayer player, History history) {
      try {
         trim(player, history);
      } catch (RuntimeException exception) {
         // The newly committed batch is more important than immediate limit
         // enforcement. Keep it undoable and retry trimming later.
         LOGGER.error("FastFormer history trimming failed for {}", player.getUUID(), exception);
      }
   }

   private static void trim(int limit, History history) {
      int bounded = boundedLimit(limit);
      while (history.undo.size() > bounded) {
         WorldChangeBatch removed = history.undo.removeLast();
         history.undoBytes -= removed.estimatedBytes();
      }
      while (history.redo.size() > bounded) {
         WorldChangeBatch removed = history.redo.removeLast();
         history.redoBytes -= removed.estimatedBytes();
      }
      // Prefer retaining the newest batch. If a single batch exceeds the
      // budget it is kept; otherwise evict the oldest batch from whichever
      // stack currently contributes more bytes. The budget covers both undo
      // and redo so an undo/redo cycle cannot silently retain ~512 MiB.
      while (history.undoBytes + history.redoBytes > MAX_BYTES_PER_PLAYER
         && (history.undo.size() > 1 || history.redo.size() > 1)) {
         if (history.redo.isEmpty() || (history.undoBytes >= history.redoBytes && history.undo.size() > 1)) {
            WorldChangeBatch removed = history.undo.removeLast();
            history.undoBytes -= removed.estimatedBytes();
         } else {
            WorldChangeBatch removed = history.redo.removeLast();
            history.redoBytes -= removed.estimatedBytes();
         }
      }
   }

   private static int ownerLimit(UUID owner) {
      return OWNER_LIMITS.getOrDefault(owner, DEFAULT_LIMIT);
   }

   private static int boundedLimit(int limit) {
      return Math.clamp(limit, 1, MAX_LIMIT);
   }

   private static final class History {
      private final ArrayDeque<WorldChangeBatch> undo = new ArrayDeque<>();
      private final ArrayDeque<WorldChangeBatch> redo = new ArrayDeque<>();
      private long undoBytes;
      private long redoBytes;

      private void clearRedo() {
         this.redo.clear();
         this.redoBytes = 0L;
      }
   }

   private static final class HistoryTask {
      private final History history;
      private final boolean undo;
      private final int requested;
      private final boolean recovery;
      private PersistentRecoveryJournal journal;
      private CompletableFuture<Optional<PersistentRecoveryJournal>> journalFuture;
      private volatile boolean journalPreparationCancelled;
      private WorldChangeBatch batch;
      private int completed;
      private int index;
      private Phase phase = Phase.CHECK;
      private BitSet applied;
      private boolean cancelRequested;
      private boolean dimensionNoticeSent;
      private boolean retainRecovery;
      private int skippedConflicts;
      private int durabilityRetryTicks;
      private ResourceKey<Level> leasedDimension;
      private int partialApplyIndex = -1;
      private ReversibleBlockSnapshot partialApplyState;
      private int blockedApplyIndex = -1;
      private boolean blockedDuringRollback;
      private int blockedNoticeTicks;

      private HistoryTask(History history, boolean undo, int requested) {
         this.history = history;
         this.undo = undo;
         this.requested = requested;
         this.recovery = false;
         this.journal = null;
      }

      private HistoryTask(WorldChangeBatch batch, PersistentRecoveryJournal journal) {
         this.history = null;
         this.undo = true;
         this.requested = 1;
         this.recovery = true;
         this.batch = batch;
         this.journal = journal;
      }

      static HistoryTask recovery(WorldChangeBatch batch) {
         return recovery(batch, null);
      }

      static HistoryTask recovery(WorldChangeBatch batch, PersistentRecoveryJournal journal) {
         return new HistoryTask(batch, journal);
      }

      /** returns true when the task is finished and can be removed. */
      boolean tick(WorldTaskContext context, WorldTaskBudget budget) {
         while (budget.hasRemaining()) {
            if (this.recovery && this.batch == null) {
               this.retainRecovery = true;
               context.actionBar(FastPlaceMessages.text("fastformer.message.history_conflict"));
               return true;
            }
            if (this.batch == null) {
               ArrayDeque<WorldChangeBatch> source = this.undo ? this.history.undo : this.history.redo;
               this.batch = source.peekFirst();
               if (this.batch == null) {
                  if (!this.recovery) {
                     this.releaseLease(context);
                     context.actionBar(
                        FastPlaceMessages.text(
                           this.undo ? "fastformer.message.history_complete_undo" : "fastformer.message.history_complete_redo", this.completed
                        )
                     );
                  }
                  return true;
               }
               this.index = 0;
               this.phase = Phase.CHECK;
               this.applied = null;
            }
            if (cancellationCanFinishImmediately(
               this.cancelRequested,
               this.phase == Phase.ROLLBACK,
               this.phase == Phase.RESOLVE,
               this.phase == Phase.FAILED
            )) {
               this.cancelJournalPreparation();
               if (!this.recovery) {
                  this.releaseAfterCancelledJournal(context);
               }
               context.actionBar(FastPlaceMessages.text("fastformer.message.restore_paused"));
               return true;
            }
            ServerLevel level = context.level(this.batch.dimension());
            if (level == null) {
               if (!this.dimensionNoticeSent) {
                  context.actionBar(FastPlaceMessages.text("fastformer.message.history_dimension_failed"));
                  this.dimensionNoticeSent = true;
               }
               return false;
            }
            this.dimensionNoticeSent = false;
            if (!this.acquireLease(context)) {
               context.actionBar(FastPlaceMessages.text("fastformer.message.world_write_waiting"));
               return false;
            }
            if (this.phase == Phase.FAILED) {
               if (this.resumeBlockedApply(level)) {
                  continue;
               }
               this.retainRecovery = true;
               if (this.blockedNoticeTicks-- <= 0) {
                  this.blockedNoticeTicks = 100;
                  context.actionBar(FastPlaceMessages.text("fastformer.message.task_recovery_blocked"));
               }
               return false;
            }
            if (this.phase == Phase.RESOLVE) {
               if (this.durabilityRetryTicks > 0) {
                  this.durabilityRetryTicks--;
                  return false;
               }
               if (this.journal != null && !this.journal.resolveAfterRollback(context.server())) {
                  this.durabilityRetryTicks = 100;
                  context.actionBar(FastPlaceMessages.text("fastformer.message.restore_failed_retry"));
                  return false;
               }
               this.releaseLease(context);
               this.journal = null;
               this.journalFuture = null;
               this.journalPreparationCancelled = false;
               if (this.recovery && this.skippedConflicts > 0) {
                  context.actionBar(FastPlaceMessages.text("fastformer.message.restore_complete_conflicts", this.skippedConflicts));
               } else {
                  context.actionBar(
                     FastPlaceMessages.text(
                        this.recovery
                           ? "fastformer.message.restore_complete"
                           : this.cancelRequested ? "fastformer.message.restore_paused" : "fastformer.message.history_conflict"
                     )
                  );
               }
               return true;
            }
            if (this.recovery && this.phase == Phase.CHECK) {
               // Recovery is best-effort per cell: another player may have
               // intentionally changed one position after our write. Restore
               // every cell still owned by this task and preserve conflicts.
               this.index = 0;
               this.applied = new BitSet(this.batch.size());
               this.phase = Phase.JOURNAL;
               continue;
            }
            if (this.phase == Phase.JOURNAL) {
               JournalPreparation preparation = this.prepareJournal(context);
               if (preparation == JournalPreparation.PENDING) {
                  context.actionBar(FastPlaceMessages.text("fastformer.message.recovery_journal_preparing"));
                  return false;
               }
               if (preparation == JournalPreparation.FAILED) {
                  this.releaseLease(context);
                  context.actionBar(FastPlaceMessages.text("fastformer.message.recovery_journal_failed"));
                  return true;
               }
               this.phase = Phase.APPLY;
               continue;
            }
            if (this.phase == Phase.CHECK) {
               while (this.index < this.batch.size() && budget.tryConsume()) {
                  if (this.batch.match(level, this.index, this.undo) == 0) {
                     this.retainRecovery = this.recovery;
                     if (!this.recovery) {
                        this.releaseLease(context);
                     }
                     context.actionBar(FastPlaceMessages.text("fastformer.message.history_conflict"));
                     return true;
                  }
                  this.index++;
               }
               if (this.index < this.batch.size()) {
                  continue;
               }
               this.index = 0;
               this.applied = new BitSet(this.batch.size());
               this.phase = Phase.JOURNAL;
               continue;
            }
            if (this.phase == Phase.APPLY) {
               while (this.index < this.batch.size() && budget.tryConsume()) {
                  int match = this.batch.match(level, this.index, this.undo);
                  if (match == 2) {
                     this.index++;
                     continue;
                  }
                  if (match != 1) {
                     if (recoveryCellAction(this.recovery, match) == RecoveryCellAction.PRESERVE_EXTERNAL) {
                        this.skippedConflicts++;
                        this.index++;
                        continue;
                     }
                     this.retainRecovery = this.recovery;
                     this.phase = Phase.ROLLBACK;
                     this.index = this.batch.size() - 1;
                     break;
                  }
                  this.applied.set(this.index);
                  if (!this.batch.apply(level, this.index, this.undo, PlacementUpdateMode.CLIENT_ONLY.flags())) {
                     Optional<ReversibleBlockSnapshot> partial = ReversibleBlockSnapshot.capture(
                        level, this.batch.position(this.index)
                     );
                     if (partial.isEmpty()) {
                        this.blockApplyFailure(this.index, false);
                        break;
                     }
                     this.partialApplyIndex = this.index;
                     this.partialApplyState = partial.orElseThrow();
                     this.retainRecovery = this.recovery;
                     this.phase = Phase.ROLLBACK;
                     this.index = this.batch.size() - 1;
                     break;
                  }
                  this.index++;
               }
               if (this.phase == Phase.ROLLBACK) {
                  continue;
               }
               if (this.index < this.batch.size()) {
                  continue;
               }
               if (!this.recovery) {
                  if (this.journal != null && !this.journal.complete()) {
                     this.phase = Phase.ROLLBACK;
                     this.index = this.batch.size() - 1;
                     continue;
                  }
                  commitBatch(context);
                  this.journal = null;
                  this.journalFuture = null;
                  this.journalPreparationCancelled = false;
               }
               this.completed++;
               if (this.recovery || this.completed >= this.requested) {
                  if (this.recovery) {
                     this.phase = Phase.RESOLVE;
                     this.durabilityRetryTicks = 0;
                     continue;
                  }
                  this.releaseLease(context);
                  context.actionBar(
                     FastPlaceMessages.text(
                        this.undo ? "fastformer.message.history_complete_undo" : "fastformer.message.history_complete_redo",
                        this.completed
                     )
                  );
                  return true;
               }
               this.batch = null;
               continue;
            }

            // A race during APPLY is handled without overwriting the external
            // write: restore only entries that still contain our target state.
            while (this.index >= 0 && budget.tryConsume()) {
               if (this.applied == null) {
                  this.phase = Phase.FAILED;
                  break;
               }
               int appliedIndex = this.applied.previousSetBit(this.index);
               if (appliedIndex < 0) {
                  // A conflict can occur before the first write after CHECK.
                  // An empty applied set is already fully rolled back.
                  this.index = -1;
                  break;
               }
               boolean inverseUndo = !this.undo;
               boolean partialIndexMatches = appliedIndex == this.partialApplyIndex;
               boolean partialFingerprintMatches = partialIndexMatches
                  && this.partialApplyState != null
                  && this.partialApplyState.matches(level, this.batch.position(appliedIndex));
               boolean normalTargetMatches = this.batch.matchesExpected(level, appliedIndex, inverseUndo);
               if (rollbackOwnsPartial(partialIndexMatches, partialFingerprintMatches, normalTargetMatches)) {
                  if (!this.batch.apply(level, appliedIndex, inverseUndo, PlacementUpdateMode.CLIENT_ONLY.flags())) {
                     Optional<ReversibleBlockSnapshot> partial = ReversibleBlockSnapshot.capture(
                        level, this.batch.position(appliedIndex)
                     );
                     if (partial.isEmpty()) {
                        this.blockApplyFailure(appliedIndex, true);
                        break;
                     }
                     this.partialApplyIndex = appliedIndex;
                     this.partialApplyState = partial.orElseThrow();
                     context.actionBar(FastPlaceMessages.text("fastformer.message.restore_failed_retry"));
                     return false;
                  }
               }
               if (appliedIndex == this.partialApplyIndex) {
                  this.partialApplyIndex = -1;
                  this.partialApplyState = null;
               }
               this.index = appliedIndex - 1;
            }
            if (this.phase == Phase.FAILED) {
               this.retainRecovery = true;
               context.actionBar(FastPlaceMessages.text("fastformer.message.task_recovery_blocked"));
               return false;
            }
            if (this.index < 0) {
               if (!this.recovery) {
                  this.phase = Phase.RESOLVE;
                  this.durabilityRetryTicks = 0;
                  continue;
               }
               this.retainRecovery = this.recovery;
               context.actionBar(
                  FastPlaceMessages.text(this.cancelRequested ? "fastformer.message.restore_paused" : "fastformer.message.history_conflict")
               );
               return true;
            }
         }
         this.reportProgress(context);
         return false;
      }

      private void reportProgress(WorldTaskContext context) {
         if (this.batch == null || this.batch.size() <= 0) {
            return;
         }
         int processed = switch (this.phase) {
            case CHECK, JOURNAL, APPLY -> Math.clamp(this.index, 0, this.batch.size());
            case ROLLBACK, FAILED, RESOLVE -> Math.clamp(this.batch.size() - 1 - this.index, 0, this.batch.size());
         };
         if (this.recovery) {
            context.actionBar(FastPlaceMessages.text(
               "fastformer.message.recovery_progress", processed, this.batch.size()
            ));
         } else {
            context.actionBar(FastPlaceMessages.text(
               this.undo ? "fastformer.message.history_progress_undo" : "fastformer.message.history_progress_redo",
               Math.min(this.completed + 1, this.requested),
               this.requested,
               processed,
               this.batch.size()
            ));
         }
      }

      private void commitBatch(WorldTaskContext context) {
         ArrayDeque<WorldChangeBatch> source = this.undo ? this.history.undo : this.history.redo;
         ArrayDeque<WorldChangeBatch> target = this.undo ? this.history.redo : this.history.undo;
         WorldChangeBatch committed = source.removeFirst();
         target.addFirst(committed);
         if (this.undo) {
            this.history.undoBytes -= committed.estimatedBytes();
            this.history.redoBytes += committed.estimatedBytes();
         } else {
            this.history.redoBytes -= committed.estimatedBytes();
            this.history.undoBytes += committed.estimatedBytes();
         }
         ServerPlayer player = context.onlinePlayer();
         if (player == null) {
            trim(ownerLimit(context.owner()), this.history);
         } else {
            trimSafely(player, this.history);
         }
      }

      private JournalPreparation prepareJournal(WorldTaskContext context) {
         if (this.recovery || this.journal != null) {
            return JournalPreparation.READY;
         }
         if (this.journalFuture == null) {
            var server = context.server();
            UUID owner = context.owner();
            this.journalFuture = CompletableFuture.supplyAsync(() -> PersistentRecoveryJournal.begin(
               server,
               owner,
               this.batch.dimension(),
               this.batch.sourceSnapshots(this.undo),
               this.batch.targetSnapshots(this.undo)
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

      private void cancelJournalPreparation() {
         this.journalPreparationCancelled = true;
         if (this.journalFuture != null) {
            this.journalFuture.whenComplete((created, exception) -> {
               if (this.journalPreparationCancelled && this.journal == null && exception == null && created != null) {
                  created.ifPresent(PersistentRecoveryJournal::discardUnused);
               }
            });
         }
      }

      private void requestCancel() {
         this.cancelRequested = true;
         this.retainRecovery = this.recovery;
         if (this.phase == Phase.APPLY && this.applied != null && !this.applied.isEmpty()) {
            this.phase = Phase.ROLLBACK;
            this.index = this.batch.size() - 1;
         }
      }

      private void blockApplyFailure(int failedIndex, boolean duringRollback) {
         this.blockedApplyIndex = failedIndex;
         this.blockedDuringRollback = duringRollback;
         this.blockedNoticeTicks = 0;
         this.phase = Phase.FAILED;
      }

      /**
       * A failed snapshot read leaves no trustworthy partial-write fingerprint.
       * Resume only when the complete live cell is provably one of the batch's
       * two states; an arbitrary third state remains blocked as an external or
       * unidentifiable partial write.
       */
      private boolean resumeBlockedApply(ServerLevel level) {
         if (this.batch == null || this.applied == null || this.blockedApplyIndex < 0) {
            return false;
         }
         int failedIndex = this.blockedApplyIndex;
         boolean direction = this.blockedDuringRollback ? !this.undo : this.undo;
         int match = this.batch.match(level, failedIndex, direction);
         if (match == 0) {
            return false;
         }

         this.blockedApplyIndex = -1;
         this.blockedNoticeTicks = 0;
         if (this.blockedDuringRollback) {
            this.blockedDuringRollback = false;
            this.phase = Phase.ROLLBACK;
            if (match == 2) {
               // The inverse target is already complete, so this cell no
               // longer needs another rollback attempt.
               this.applied.clear(failedIndex);
               this.index = failedIndex - 1;
            } else {
               // The operation target is still intact; retry its inverse.
               this.index = failedIndex;
            }
            return true;
         }

         // APPLY failed before its fingerprint could be captured. If its
         // target nevertheless completed, continue. If the source is intact,
         // exclude this untouched cell and atomically roll back earlier cells.
         this.blockedDuringRollback = false;
         if (match == 2) {
            this.phase = Phase.APPLY;
            this.index = failedIndex + 1;
         } else {
            this.applied.clear(failedIndex);
            this.phase = Phase.ROLLBACK;
            this.index = this.batch.size() - 1;
         }
         return true;
      }

      private boolean retainRecovery() {
         return this.retainRecovery;
      }

      private WorldChangeBatch batch() {
         return this.batch;
      }

      private PersistentRecoveryJournal journal() {
         return this.journal;
      }

      private boolean acquireLease(WorldTaskContext context) {
         if (this.batch == null) {
            return false;
         }
         ResourceKey<Level> desired = this.batch.dimension();
         if (desired.equals(this.leasedDimension)) {
            return true;
         }
         if (this.leasedDimension != null) {
            WorldWriteCoordinator.release(context.server(), this.leasedDimension, context.owner());
            this.leasedDimension = null;
         }
         if (!WorldWriteCoordinator.tryAcquire(context.server(), desired, context.owner())) {
            return false;
         }
         this.leasedDimension = desired;
         return true;
      }

      private void releaseLease(WorldTaskContext context) {
         if (this.leasedDimension != null) {
            WorldWriteCoordinator.release(context.server(), this.leasedDimension, context.owner());
            this.leasedDimension = null;
         }
      }

      private void releaseAfterCancelledJournal(WorldTaskContext context) {
         ResourceKey<Level> dimension = this.leasedDimension != null
            ? this.leasedDimension
            : this.batch == null ? null : this.batch.dimension();
         if (dimension == null) {
            return;
         }
         WorldWriteCoordinator.releaseAfterUnusedJournal(
            context.server(), dimension, context.owner(), this.journal, this.journalFuture
         );
      }

      private enum Phase {
         CHECK,
         JOURNAL,
         APPLY,
         ROLLBACK,
         RESOLVE,
         FAILED
      }
   }

   static boolean rollbackOwnsPartial(
      boolean partialIndexMatches,
      boolean partialFingerprintMatches,
      boolean normalTargetMatches
   ) {
      return normalTargetMatches || (partialIndexMatches && partialFingerprintMatches);
   }

   static boolean cancellationCanFinishImmediately(
      boolean cancelRequested,
      boolean rollingBack,
      boolean resolving,
      boolean failedApplyState
   ) {
      return cancelRequested && !rollingBack && !resolving && !failedApplyState;
   }

   static boolean retainRecoveryForManualResume(boolean ownerOnline) {
      return ownerOnline;
   }

   static RecoveryCellAction recoveryCellAction(boolean recovery, int match) {
      if (match == 1) {
         return RecoveryCellAction.RESTORE;
      }
      if (match == 2) {
         return RecoveryCellAction.ALREADY_RESTORED;
      }
      return recovery ? RecoveryCellAction.PRESERVE_EXTERNAL : RecoveryCellAction.FAIL_ATOMIC_BATCH;
   }

   enum RecoveryCellAction {
      RESTORE,
      ALREADY_RESTORED,
      PRESERVE_EXTERNAL,
      FAIL_ATOMIC_BATCH
   }
}
