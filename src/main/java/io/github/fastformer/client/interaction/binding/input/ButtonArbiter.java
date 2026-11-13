package io.github.fastformer.client.interaction.binding.input;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Owns a pending physical hold, including its original context and one-shot replay obligation. */
public final class ButtonArbiter<T> {
   private Pending<T> pending;

   public enum Kind { START, REPLAY_PRESS, RELEASE }
   public record Signal<T>(Kind kind, T snapshot, long pressedAtNanos) {}
   private record Pending<T>(int button, long pressedAt, long thresholdNanos, boolean deferred, T snapshot, boolean started) {
      Pending<T> start() { return new Pending<>(button, pressedAt, thresholdNanos, deferred, snapshot, true); }
   }

   /** Returns whether this press is owned; repeated press events never restart the timer. */
   public boolean press(int button, long occurredAt, InputPattern pattern, T snapshot) {
      if (pending != null) return pending.button == button;
      if (!pattern.gesture().equals("hold")) throw new IllegalArgumentException("Expected a hold pattern");
      pending = new Pending<>(button, occurredAt, TimeUnit.MILLISECONDS.toNanos(pattern.durationMillis()),
         pattern.holdStart().equals("deferred"), Objects.requireNonNull(snapshot), false);
      return pending.deferred;
   }

   public boolean owns(int button) { return pending != null && pending.button == button; }
   public boolean active() { return pending != null; }
   public T snapshot() { return pending == null ? null : pending.snapshot; }

   public List<Signal<T>> tick(long now) {
      if (pending == null || pending.started || !reached(now, pending)) return List.of();
      pending = pending.start();
      return List.of(signal(Kind.START, pending));
   }

   /** Release at the threshold starts and finishes the hold, even if no tick ran between events. */
   public List<Signal<T>> release(int button, long occurredAt) {
      if (!owns(button)) return List.of();
      Pending<T> captured = pending;
      pending = null;
      if (occurredAt < captured.pressedAt) return List.of();
      if (captured.started) return List.of(signal(Kind.RELEASE, captured));
      if (reached(occurredAt, captured)) return List.of(signal(Kind.START, captured), signal(Kind.RELEASE, captured));
      return captured.deferred ? List.of(signal(Kind.REPLAY_PRESS, captured)) : List.of();
   }

   /** Context loss is cancellation, never a release and never an obligation to replay vanilla input. */
   public void cancel() { pending = null; }

   private static boolean reached(long now, Pending<?> pending) {
      return now >= pending.pressedAt && now - pending.pressedAt >= pending.thresholdNanos;
   }

   private static <T> Signal<T> signal(Kind kind, Pending<T> pending) {
      return new Signal<>(kind, pending.snapshot, pending.pressedAt);
   }
}
