package io.github.fastformer.server.task;

import io.github.fastformer.server.recovery.WorldRecoverySnapshot;
import io.github.fastformer.fastplace.task.TaskCancellationResult;
import io.github.fastformer.server.task.WorldTask;
import io.github.fastformer.fastplace.world.WorldTaskContext;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Server-thread queue retaining each task's sole recovery snapshot across rejected handovers. */
public final class WorldTaskQueue<T extends WorldTask> {
   private static final Logger LOGGER = LoggerFactory.getLogger(WorldTaskQueue.class);
   private final Map<UUID, Entry<T>> entries = new HashMap<>();
   private boolean closed;

   /** Never replaces a live writer or an extracted recovery record. */
   public void put(UUID owner, T task) {
      if (closed) throw new IllegalStateException("world task queue is closed");
      Objects.requireNonNull(owner, "owner");
      Objects.requireNonNull(task, "task");
      Entry<T> previous = entries.get(owner);
      if (previous != null) {
         if (previous.task == task) return;
         throw new IllegalStateException("world task already queued for " + owner);
      }
      entries.put(owner, new Entry<>(task));
   }

   public T get(UUID owner) {
      Entry<T> entry = entries.get(owner);
      return entry == null ? null : entry.task;
   }

   public boolean containsKey(UUID owner) { return entries.containsKey(owner); }
   public List<UUID> owners() { return List.copyOf(entries.keySet()); }
   public List<T> tasks() { return entries.values().stream().map(entry -> entry.task).toList(); }

   /** A delayed resume can advance only its original, still-active queue entry. */
   public Optional<TaskStep> tick(WorldTaskContext context, T expected) {
      Entry<T> entry = entries.get(context.owner());
      if (closed || entry == null || entry.task != expected || entry.transferring) return Optional.empty();
      return Optional.of(Objects.requireNonNull(expected.tick(context), "task step"));
   }

   /** End admission before callbacks; one failed cleanup must not retain unrelated tasks. */
   public void close() {
      if (closed) return;
      closed = true;
      var pending = tasks();
      entries.clear();
      RuntimeException failure = null;
      for (T task : pending) {
         try { task.cancel(CancelReason.SHUTDOWN); }
         catch (RuntimeException next) {
            if (failure == null) failure = next;
            else if (failure != next) failure.addSuppressed(next);
         }
      }
      if (failure != null) throw failure;
   }

   public boolean remove(UUID owner, T expected) {
      Entry<T> entry = entries.get(owner);
      return entry != null && entry.task == expected && entries.remove(owner, entry);
   }

   /** Also blocks resumes after an accepted handover, until the caller removes the slot. */
   public boolean handingOver(UUID owner) {
      Entry<T> entry = entries.get(owner);
      return entry != null && entry.transferring;
   }

   /** A stale callback cannot extract or transfer resources from a successor task. */
   public TaskCancellationResult transfer(WorldTaskContext context, T expected, RecoveryAcceptance<T> recovery) {
      Objects.requireNonNull(recovery, "recovery");
      Entry<T> entry = entries.get(context.owner());
      if (entry == null || entry.task != expected) return TaskCancellationResult.NOT_ACTIVE;
      if (entry.accepted != null) return entry.accepted;
      entry.transferring = true;
      try {
         if (entry.snapshot == null) {
            entry.snapshot = Objects.requireNonNull(expected.stopAndTransferRecovery(), "recovery snapshot");
         }
         TaskCancellationResult result = Objects.requireNonNull(
            recovery.accept(context, expected, entry.snapshot), "recovery result");
         if (result == TaskCancellationResult.CANCELLED_BEFORE_WRITE || result == TaskCancellationResult.ROLLBACK_STARTED) {
            entry.accepted = result;
         }
         return result;
      } catch (RuntimeException | OutOfMemoryError failure) {
         LOGGER.error("FastFormer retained task {} and its recovery snapshot after a failed handover", context.owner(), failure);
         return TaskCancellationResult.RECOVERY_BLOCKED;
      }
   }

   /** Acceptance must do no throwing work after taking ownership. */
   @FunctionalInterface
   public interface RecoveryAcceptance<T extends WorldTask> {
      TaskCancellationResult accept(WorldTaskContext context, T task, WorldRecoverySnapshot snapshot);
   }

   private static final class Entry<T> {
      private final T task;
      private WorldRecoverySnapshot snapshot;
      private TaskCancellationResult accepted;
      private boolean transferring;
      private Entry(T task) { this.task = task; }
   }
}
