package io.github.fastformer.client.operation.selection;

import java.util.Objects;

/** Owns the coarse lifetime of one selection session. Draft details stay in the session. */
final class SelectionSessionLifecycle {
   private Phase phase = Phase.IDLE;

   Phase phase() {
      return this.phase;
   }

   Transition enter(Phase target) {
      Objects.requireNonNull(target, "target");
      if (target == this.phase) return Transition.stay();
      Phase previous = this.phase;
      this.phase = target;
      return new Transition(previous, target, Cause.ENTER);
   }

   Transition onEvent(Event event) {
      Objects.requireNonNull(event, "event");
      return switch (event) {
         case BEGIN -> enter(Phase.POINTING);
         case FOCUS -> enter(Phase.FOCUSED);
         case SUBMIT -> enter(Phase.SUBMITTING);
         case COMPLETE -> enter(Phase.FOCUSED);
         case CANCEL, ENVIRONMENT_CHANGED -> exit(Cause.CANCEL);
      };
   }

   Transition exit(Cause cause) {
      Objects.requireNonNull(cause, "cause");
      if (this.phase == Phase.IDLE) return Transition.stay();
      Phase previous = this.phase;
      this.phase = Phase.IDLE;
      return new Transition(previous, Phase.IDLE, cause);
   }

   enum Phase { IDLE, POINTING, FOCUSED, SUBMITTING }
   enum Event { BEGIN, FOCUS, SUBMIT, COMPLETE, CANCEL, ENVIRONMENT_CHANGED }
   enum Cause { ENTER, CANCEL, ENVIRONMENT_CHANGED }

   record Transition(Phase previous, Phase target, Cause cause) {
      static Transition stay() { return new Transition(null, null, null); }
      boolean changed() { return this.target != null; }
   }
}
