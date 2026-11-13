package io.github.fastformer.server.history.rollback;

import com.mojang.logging.LogUtils;
import io.github.fastformer.fastplace.text.FastPlaceMessages;
import io.github.fastformer.fastplace.world.WorldTaskContext;
import io.github.fastformer.fastplace.world.WorldWriteCoordinator;
import io.github.fastformer.fastplace.world.memory.WorldOperationMemory;
import io.github.fastformer.fastplace.world.snapshot.ReversibleBlockSnapshot;
import io.github.fastformer.server.history.entry.WorldChangeBatch;
import io.github.fastformer.server.recovery.PersistentRecoveryJournal;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;

/** Owns detached rollback captures until restored or transferred to a replay task. */
public final class RollbackQueue {
   private static final Logger LOGGER = LogUtils.getLogger();
   private final ArrayDeque<RecoveryCapture> captures = new ArrayDeque<>();

   public record ReadyRecovery(WorldChangeBatch batch, PersistentRecoveryJournal journal) {}

   public int size() { return captures.size(); }
   public boolean isEmpty() { return captures.isEmpty(); }

   /** A successful return transfers ownership; failures leave the capture with the caller. */
   public boolean accept(RollbackRequest request, boolean blocked, Consumer<ReadyRecovery> install,
      BiConsumer<WorldTaskContext, String> feedback) {
      Objects.requireNonNull(install, "recovery installer");
      Objects.requireNonNull(feedback, "recovery feedback");
      if (!request.snapshots().ready()) {
         enqueue(request);
         notifyQuietly(request.context(), "fastformer.message.task_recovery_blocked", feedback);
         return true;
      }
      return startReady(request, blocked, install, feedback);
   }

   public void enqueue(RollbackRequest request) {
      captures.addLast(recoveryCapture(request.dimension(), request.snapshots().before(),
         request.snapshots().after(), request.journal(), request.snapshots().readyForRecovery()));
   }

   /** Processes only the oldest capture, keeping ownership until installation succeeds. */
   public void poll(WorldTaskContext context, Consumer<ReadyRecovery> install) {
      RecoveryCapture capture = captures.peekFirst();
      if (capture == null || !capture.readyForRecovery().isDone()
         || !WorldOperationMemory.snapshotAdmission(capture.changes().size(), 0L).fitsCurrentHeap()) return;
      Optional<WorldChangeBatch> batch = materializeRecovery(context.server(), capture);
      if (batch.isPresent()) {
         install.accept(new ReadyRecovery(batch.orElseThrow(), capture.journal()));
         captures.removeFirst();
         return;
      }
      ServerLevel level = context.level(capture.dimension());
      if (level == null || !allBefore(level, capture.changes())) return;
      if (capture.journal() != null && !capture.journal().resolveAfterRollback(context.server())) return;
      captures.removeFirst();
      WorldWriteCoordinator.releaseCurrentLease(context.server(), capture.dimension(), context.owner());
      context.actionBar(FastPlaceMessages.text("fastformer.message.restore_complete"));
   }

   private boolean startReady(RollbackRequest request, boolean blocked, Consumer<ReadyRecovery> install,
      BiConsumer<WorldTaskContext, String> feedback) {
      WorldTaskContext context = request.context();
      ResourceKey<Level> dimension = request.dimension();
      ArrayDeque<ReversibleBlockSnapshot> changes = request.snapshots().before();
      Map<BlockPos, ReversibleBlockSnapshot> after = request.snapshots().after();
      PersistentRecoveryJournal journal = request.journal();
      ServerLevel level = context.level(dimension);
      if (dimension == null || changes == null) {
         return false;
      }
      // Callers transfer a detached snapshot map; another full copy here can
      // double the peak memory of a large cancellation.
      Map<BlockPos, ReversibleBlockSnapshot> safeAfter = after == null ? Map.of() : after;
      if (blocked) {
         // A previous history/recovery task owns this player. Keep this
         // rollback in order instead of reporting success without a task.
         if (!changes.isEmpty() || journal != null) {
            if (!enqueueQuietly(context, dimension, changes, safeAfter, journal)) {
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
         if (!enqueueQuietly(context, dimension, changes, safeAfter, journal)) {
            return false;
         }
         notifyQuietly(context, "fastformer.message.operation_memory_unsafe", feedback);
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
         install.accept(new ReadyRecovery(captured.orElseThrow(), journal));
         // The ownership transfer happened above. Nothing after it may throw, or the
         // caller cannot tell an accepted snapshot from a refused one.
         notifyQuietly(context, "fastformer.message.restore_cancelled_task", feedback);
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
      if (!enqueueQuietly(context, dimension, changes, safeAfter, journal)) {
         return false;
      }
      notifyQuietly(context, "fastformer.message.task_recovery_blocked", feedback);
      return true;
   }

   /**
    * Queues one recovery capture, or reports that it was not queued.
    *
    * <p>A failed enqueue transfers nothing, so the caller keeps ownership of the snapshot.
    */
   private boolean enqueueQuietly(
      WorldTaskContext context,
      ResourceKey<Level> dimension,
      ArrayDeque<ReversibleBlockSnapshot> changes,
      Map<BlockPos, ReversibleBlockSnapshot> after,
      PersistentRecoveryJournal journal
   ) {
      try {
         captures.addLast(recoveryCapture(dimension, changes, after, journal));
         return true;
      } catch (RuntimeException | OutOfMemoryError exception) {
         LOGGER.error("FastFormer could not queue a recovery capture for {}", context.owner(), exception);
         return false;
      }
   }

   /** Progress feedback must never decide ownership, so a failed message stays harmless. */
   private static void notifyQuietly(WorldTaskContext context, String key, BiConsumer<WorldTaskContext, String> feedback) {
      try {
         feedback.accept(context, key);
      } catch (RuntimeException | OutOfMemoryError exception) {
         LOGGER.warn("FastFormer could not show the recovery message {}", key, exception);
      }
   }

   private boolean resolveAlreadyRestored(
      WorldTaskContext context,
      ResourceKey<Level> dimension,
      ArrayDeque<ReversibleBlockSnapshot> changes,
      Map<BlockPos, ReversibleBlockSnapshot> after,
      PersistentRecoveryJournal journal
   ) {
      if (journal == null || journal.resolveAfterRollback(context.server())) {
         WorldWriteCoordinator.releaseCurrentLease(context.server(), dimension, context.owner());
         return true;
      }
      // The blocks are already correct, but the durable save/journal cleanup
      // is still part of the transaction. Retain it as a world task so a
      // transient IO failure cannot become a permanent invisible lease.
      captures.addLast(recoveryCapture(dimension, changes, after, journal));
      return true;
   }

   private boolean completeAfter(
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

   private RecoveryCapture recoveryCapture(
      ResourceKey<Level> dimension,
      ArrayDeque<ReversibleBlockSnapshot> changes,
      Map<BlockPos, ReversibleBlockSnapshot> after,
      PersistentRecoveryJournal journal
   ) {
      return recoveryCapture(dimension, changes, after, journal, CompletableFuture.completedFuture(null));
   }

   private RecoveryCapture recoveryCapture(
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

   private boolean allBefore(ServerLevel level, ArrayDeque<ReversibleBlockSnapshot> changes) {
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

   private Optional<WorldChangeBatch> materializeRecovery(MinecraftServer server, RecoveryCapture capture) {
      try {
         Optional<WorldChangeBatch> paired = WorldChangeBatch.capturePairsByPos(
            capture.dimension(), capture.changes(), capture.after()
         ).map(batch -> batch.withOperationId(capture.operationId()));
         if (paired.isPresent()) {
            return paired;
         }
      } catch (RuntimeException | OutOfMemoryError failure) {
         capture.reportFailure(failure);
      }
      ServerLevel level = server == null ? null : server.getLevel(capture.dimension());
      if (level == null) {
         return Optional.empty();
      }
      try {
         return WorldChangeBatch.capture(level, capture.changes())
            .map(batch -> batch.withOperationId(capture.operationId()));
      } catch (RuntimeException | OutOfMemoryError exception) {
         capture.reportFailure(exception);
         return Optional.empty();
      }
   }

   record RecoveryCapture(
      ResourceKey<Level> dimension,
      ArrayDeque<ReversibleBlockSnapshot> changes,
      Map<BlockPos, ReversibleBlockSnapshot> after,
      PersistentRecoveryJournal journal,
      UUID operationId,
      CompletableFuture<Void> readyForRecovery,
      AtomicBoolean failureReported
   ) {
      RecoveryCapture(ResourceKey<Level> dimension, ArrayDeque<ReversibleBlockSnapshot> changes,
         Map<BlockPos, ReversibleBlockSnapshot> after, PersistentRecoveryJournal journal,
         UUID operationId, CompletableFuture<Void> readyForRecovery) {
         this(dimension, changes, after, journal, operationId, readyForRecovery,
            new AtomicBoolean());
      }

      RecoveryCapture {
         // Rollback receives collections detached from the writer task.
         // Transfer them directly so an exceptional large recovery does not
         // double its peak heap before any block can be restored.
         changes = changes == null ? new ArrayDeque<>() : changes;
         after = after == null ? Map.of() : after;
         operationId = operationId == null ? UUID.randomUUID() : operationId;
         readyForRecovery = readyForRecovery == null
            ? CompletableFuture.completedFuture(null)
            : readyForRecovery;
      }

      void reportFailure(Throwable failure) {
         if (failureReported.compareAndSet(false, true)) {
            LOGGER.error("Unable to materialize recovery {} in {}; snapshots remain owned for retry",
               operationId, dimension.location(), failure);
         }
      }
   }

}
