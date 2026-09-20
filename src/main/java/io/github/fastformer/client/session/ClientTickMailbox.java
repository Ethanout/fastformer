package io.github.fastformer.client.session;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.function.Consumer;

/** Producers enqueue data; one consumer takes a fixed batch at each tick boundary. */
public final class ClientTickMailbox<E> {
   private final Consumer<E> discard;
   private ArrayDeque<Entry<E>> pending = new ArrayDeque<>();
   private long epoch;
   private boolean draining;

   public ClientTickMailbox(Consumer<E> discard) {
      this.discard = Objects.requireNonNull(discard, "discard");
   }

   public synchronized long epoch() {
      return this.epoch;
   }

   public synchronized void post(E event) {
      this.pending.addLast(new Entry<>(this.epoch, Objects.requireNonNull(event, "event")));
   }

   /** Background work must retain the epoch from before it started. */
   public boolean post(long ownerEpoch, E event) {
      Objects.requireNonNull(event, "event");
      synchronized (this) {
         if (ownerEpoch == this.epoch) {
            this.pending.addLast(new Entry<>(ownerEpoch, event));
            return true;
         }
      }
      this.discard.accept(event);
      return false;
   }

   public synchronized int pendingCount() {
      return this.pending.size();
   }

   public synchronized boolean hasUnfinishedEvents() {
      return this.draining || !this.pending.isEmpty();
   }

   public void drain(Consumer<E> consumer) {
      Objects.requireNonNull(consumer, "consumer");
      ArrayDeque<Entry<E>> batch;
      synchronized (this) {
         if (this.draining) {
            throw new IllegalStateException("Client event dispatch cannot be reentrant");
         }
         this.draining = true;
         batch = this.pending;
         this.pending = new ArrayDeque<>();
      }
      Throwable dispatchFailure = null;
      try {
         while (!batch.isEmpty()) {
            Entry<E> entry = batch.removeFirst();
            if (entry.epoch() == epoch()) {
               // The consumer owns this event, including cleanup if it throws.
               consumer.accept(entry.event());
            } else {
               this.discard.accept(entry.event());
            }
         }
      } catch (RuntimeException | Error exception) {
         dispatchFailure = exception;
         throw exception;
      } finally {
         try {
            discardAll(batch);
         } catch (RuntimeException | Error cleanupFailure) {
            if (dispatchFailure == null) throw cleanupFailure;
            if (dispatchFailure != cleanupFailure) dispatchFailure.addSuppressed(cleanupFailure);
         } finally {
            synchronized (this) {
               this.draining = false;
            }
         }
      }
   }

   /** Invalidates both queued events and the unprocessed part of an active batch. */
   public void invalidate() {
      ArrayDeque<Entry<E>> abandoned;
      synchronized (this) {
         this.epoch++;
         abandoned = this.pending;
         this.pending = new ArrayDeque<>();
      }
      discardAll(abandoned);
   }

   private void discardAll(ArrayDeque<Entry<E>> events) {
      Throwable failure = null;
      while (!events.isEmpty()) {
         try {
            this.discard.accept(events.removeFirst().event());
         } catch (RuntimeException | Error exception) {
            if (failure == null) failure = exception;
            else if (failure != exception) failure.addSuppressed(exception);
         }
      }
      if (failure instanceof RuntimeException exception) throw exception;
      if (failure instanceof Error error) throw error;
   }

   private record Entry<E>(long epoch, E event) { }
}
