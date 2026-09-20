package io.github.fastformer.client.input;

import java.util.Objects;

/** A decision contains no callback and cannot mutate the input owner. */
public sealed interface InteractionTransition {
   enum Stay implements InteractionTransition { INSTANCE }

   record Rejected(Rejection reason) implements InteractionTransition {
      public Rejected {
         Objects.requireNonNull(reason, "reason");
      }
   }

   record Switch(
      ClientInputStateMachine.State target,
      Cause cause,
      ClientSemanticEvent.Submit submission
   ) implements InteractionTransition {
      public Switch {
         Objects.requireNonNull(target, "target");
         Objects.requireNonNull(cause, "cause");
         if ((cause == Cause.SUBMIT) != (submission != null)) {
            throw new IllegalArgumentException("Only submission transitions carry a request");
         }
      }

      public Switch(ClientInputStateMachine.State target, Cause cause) {
         this(target, cause, null);
      }
   }

   enum Cause { OBSERVATION, CANCEL, SUBMIT, SUBMISSION_COMPLETED, RESET }
   enum Rejection { INPUT_BLOCKED, REQUEST_ACTIVE, INVALID_REQUEST, STALE_REQUEST }
}
