package io.github.fastformer.client.input.state;

import java.util.Objects;
import java.util.UUID;

/** Lifecycle events consumed by the client input owner. */
public sealed interface ClientSemanticEvent {
   record Observe(ClientInputStateMachine.State state) implements ClientSemanticEvent {
      public Observe {
         Objects.requireNonNull(state, "state");
         if (!state.serverObservable()) {
            throw new IllegalArgumentException("Only server-observable states can be observed");
         }
      }
   }

   enum Cancel implements ClientSemanticEvent { INSTANCE }
   enum Reset implements ClientSemanticEvent { INSTANCE }
   record SuspendIdle(boolean withinFar) implements ClientSemanticEvent { }
   record ObserveReach(boolean withinFar) implements ClientSemanticEvent { }
   enum ResumeIdle implements ClientSemanticEvent { INSTANCE }
   record ConfigureActivation(io.github.fastformer.fastplace.session.SessionActivationMode mode) implements ClientSemanticEvent {
      public ConfigureActivation { Objects.requireNonNull(mode); }
   }
   enum EnterManual implements ClientSemanticEvent { INSTANCE }

   record StartPlacement(long requestId) implements ClientSemanticEvent { }

   sealed interface Submit extends ClientSemanticEvent {
      record Placement(long requestId) implements Submit { }
      record Workspace(UUID transferId) implements Submit {
         public Workspace {
            Objects.requireNonNull(transferId, "transferId");
         }
      }
   }

   record SubmissionCompleted(
      Submit request,
      ClientInputStateMachine.SubmissionEvent outcome,
      ClientInputStateMachine.State observed
   ) implements ClientSemanticEvent {
      public SubmissionCompleted {
         Objects.requireNonNull(request, "request");
         Objects.requireNonNull(outcome, "outcome");
         if (outcome == ClientInputStateMachine.SubmissionEvent.SUCCEEDED) {
            Objects.requireNonNull(observed, "observed");
            if (!observed.serverObservable()) {
               throw new IllegalArgumentException("Successful submission requires an observed server state");
            }
         }
      }
   }
}
