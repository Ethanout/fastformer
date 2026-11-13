package io.github.fastformer.core.async;

import java.util.ArrayList;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.Consumer;

/**
 * Owns one replaceable computation and publishes it only when the owner consumes results.
 * All methods except worker completion belong to the owner's thread. Keys need not be unique:
 * replacing a request invalidates the previous generation even when its key stays equal.
 */
public final class LatestOnlyTask<K, V> {
   private final TickMailbox<Work> results = new TickMailbox<>(ignored -> {});
   private Work current;
   private Executor executor;

   /** Replaces the request; executor rejection becomes a failed completion, never an inline callback. */
   public void start(K key, Callable<V> computation, Executor executor) {
      Objects.requireNonNull(key, "key");
      Objects.requireNonNull(computation, "computation");
      Objects.requireNonNull(executor, "executor");
      cancel();
      this.executor = executor;
      Work work = new Work(key, computation, results.epoch());
      current = work;
      try {
         executor.execute(work);
      } catch (RuntimeException failure) {
         work.rejected(failure);
      }
   }

   public boolean completed() { return results.hasUnfinishedEvents(); }

   /** Includes a completion not consumed by the owner yet. */
   public boolean pending() { return current != null; }

   /** Consumes the current result at most once; null results are allowed and still consume the request. */
   public Optional<Completion<K, V>> takeCompleted() throws InterruptedException, ExecutionException {
      if (!completed()) return Optional.empty();
      var ready = new ArrayList<Work>(1);
      results.drain(work -> { if (work == current) ready.add(work); });
      if (ready.isEmpty()) return Optional.empty();
      Work completed = ready.getFirst();
      try {
         return Optional.of(new Completion<>(completed.key, completed.get()));
      } finally {
         if (current == completed) {
            current = null;
            executor = null;
         }
      }
   }

   /**
    * Consumes on the caller's tick. The failure handler owns the policy: clear state,
    * schedule a retry with backoff, or throw. Superseded requests never invoke either handler.
    */
   public void drain(Consumer<Completion<K, V>> publish, Consumer<Throwable> failurePolicy) {
      Objects.requireNonNull(publish, "publish");
      Objects.requireNonNull(failurePolicy, "failurePolicy");
      Optional<Completion<K, V>> completion;
      try {
         completion = takeCompleted();
      } catch (InterruptedException interrupted) {
         Thread.currentThread().interrupt();
         failurePolicy.accept(interrupted);
         return;
      } catch (ExecutionException failure) {
         failurePolicy.accept(failure.getCause());
         return;
      } catch (java.util.concurrent.CancellationException cancelled) {
         failurePolicy.accept(cancelled);
         return;
      }
      completion.ifPresent(publish);
   }

   /** Invalidates late completion and removes only this request from a shared executor queue. */
   public void cancel() {
      results.invalidate();
      Work cancelled = current;
      current = null;
      if (cancelled != null) {
         cancelled.cancel(true);
         if (executor instanceof ThreadPoolExecutor pool) pool.remove(cancelled);
      }
      executor = null;
   }

   public record Completion<K, V>(K key, V value) {}

   private final class Work extends FutureTask<V> {
      private final K key;
      private final long epoch;

      private Work(K key, Callable<V> computation, long epoch) {
         super(computation);
         this.key = key;
         this.epoch = epoch;
      }

      @Override protected void done() { results.post(epoch, this); }

      private void rejected(RuntimeException failure) { setException(failure); }
   }
}
