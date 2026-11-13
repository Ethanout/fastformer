package io.github.fastformer.server.session.execution;

import io.github.fastformer.core.lifecycle.Scoped;
import io.github.fastformer.fastplace.text.FastPlaceMessages;
import io.github.fastformer.server.history.HistoryService;
import io.github.fastformer.server.history.HistorySystem;
import io.github.fastformer.server.task.TaskCancellationResult;
import io.github.fastformer.server.task.WorldTask;
import io.github.fastformer.server.task.WorldTaskContext;
import io.github.fastformer.server.task.WorldTaskQueue;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Shared admission, wakeup arbitration, cancellation and shutdown of a server-owned queue. */
abstract class TaskExecution<T extends WorldTask> implements Scoped {
   final WorldTaskQueue<T> queue = new WorldTaskQueue<>();
   private final BooleanSupplier closed;
   private final Predicate<UUID> prerequisiteBusy;

   TaskExecution(BooleanSupplier closed, Predicate<UUID> prerequisiteBusy) {
      this.closed = Objects.requireNonNull(closed);
      this.prerequisiteBusy = Objects.requireNonNull(prerequisiteBusy);
   }

   /** Establish ownership before publishing admission or scheduling callbacks. */
   public final void admit(UUID owner, T task) {
      if (closed.getAsBoolean()) throw new IllegalStateException("task runtime is closed");
      queue.put(owner, task);
   }

   public final boolean active(UUID owner) { return queue.containsKey(owner); }
   public final boolean active(ServerPlayer player) { return active(player.getUUID()); }
   public final boolean isCurrent(UUID owner, T task) { return queue.get(owner) == task; }

   public final void enqueue(ServerPlayer player, T task) {
      admit(player.getUUID(), task);
      schedule(new WorldTaskContext(player.getServer(), player.getUUID()), task, HistorySystem.of(player));
   }

   final void schedule(WorldTaskContext context, T task, HistoryService history) {
      context.withResume(() -> resume(context, task, history)).enqueueResume();
   }

   /** A queued completion cannot advance a successor, a cancelled task, or a retired runtime. */
   public final void resume(WorldTaskContext context, T task, HistoryService history) {
      if (!closed.getAsBoolean() && isCurrent(context.owner(), task)
         && !queue.handingOver(context.owner()) && context.writesAllowed()
         && !history.busy(context.owner()) && !prerequisiteBusy.test(context.owner())) {
         service(context, history);
      }
   }

   public final void tickWorld(MinecraftServer server, HistoryService history) {
      if (closed.getAsBoolean()) return;
      for (UUID owner : queue.owners()) {
         WorldTaskContext context = new WorldTaskContext(server, owner);
         if (!context.writesAllowed()) break;
         if (queue.handingOver(owner)) {
            handOver(context, history);
         } else if (!history.busy(owner) && !prerequisiteBusy.test(owner)) {
            service(context, history);
         }
      }
   }

   /** Recovery acceptance is retried even when editing and normal execution are blocked. */
   public final void handOverBlockedTasks(MinecraftServer server, HistoryService history) {
      if (closed.getAsBoolean()) return;
      for (UUID owner : queue.owners()) handOver(new WorldTaskContext(server, owner), history);
   }

   private void handOver(WorldTaskContext context, HistoryService history) {
      if (cancel(context, history).handled()) {
         context.actionBar(FastPlaceMessages.text("fastformer.message.recovery_journal_blocked"));
      }
   }

   public final TaskCancellationResult cancel(ServerPlayer player) {
      return cancel(new WorldTaskContext(player.getServer(), player.getUUID()), HistorySystem.of(player));
   }

   public final TaskCancellationResult cancel(WorldTaskContext context, HistoryService history) {
      T task = queue.get(context.owner());
      if (task == null) return TaskCancellationResult.NOT_ACTIVE;
      TaskCancellationResult result = queue.transfer(context, task, history::acceptTransferredTask);
      if (handoverAccepted(result)) {
         queue.remove(context.owner(), task);
         onCancellationAccepted(context, task, result);
      }
      return result;
   }

   void onCancellationAccepted(WorldTaskContext context, T task, TaskCancellationResult result) {}

   static boolean handoverAccepted(TaskCancellationResult result) {
      return result == TaskCancellationResult.CANCELLED_BEFORE_WRITE || result == TaskCancellationResult.ROLLBACK_STARTED;
   }

   public abstract void service(WorldTaskContext context, HistoryService history);

   @Override public final void onDiscard() { queue.close(); }
}
