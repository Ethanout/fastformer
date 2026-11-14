package io.github.fastformer.client.mode;

import io.github.fastformer.client.input.state.ClientInputStateMachine.State;
import java.util.Objects;

/** One coherent workflow observation, including input that still owns an idle phase. */
public record ClientModeView(State observedPhase, boolean interactiveSession, boolean idleActionsAllowed) {
   public ClientModeView {
      Objects.requireNonNull(observedPhase, "observedPhase");
   }

   public static ClientModeView capture(
      ClientModeRegistry modes, ModeObservation observation, State routedPhase, boolean pendingQuickShapeStart
   ) {
      Objects.requireNonNull(modes, "modes");
      Objects.requireNonNull(routedPhase, "routedPhase");
      State observed = modes.observe(observation);
      return new ClientModeView(observed, modes.hasInteractiveSession(observation),
         routedPhase == State.IDLE && !pendingQuickShapeStart && observed == State.IDLE);
   }
}
