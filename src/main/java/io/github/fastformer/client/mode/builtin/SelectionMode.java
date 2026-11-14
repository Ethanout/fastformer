package io.github.fastformer.client.mode.builtin;

import static io.github.fastformer.client.input.state.ClientInputStateMachine.Dispatch.*;

import io.github.fastformer.client.input.state.ClientInputStateMachine.State;
import io.github.fastformer.client.mode.ClientMode;
import io.github.fastformer.client.mode.ModeInputPolicy;
import io.github.fastformer.client.mode.ModeObservation;
import java.util.Map;
import java.util.Optional;

/** Declares selection ownership and its phase input rules. */
public final class SelectionMode implements ClientMode {
   private static final Map<State, ModeInputPolicy> PHASES = Map.of(
      State.SELECTING, new ModeInputPolicy(OPERATION, BLOCKED, OPERATION, OPERATION, CANCEL, false, true, false),
      State.ADJUSTING, new ModeInputPolicy(OPERATION, OPERATION, OPERATION, OPERATION, CANCEL, true, true, false));

   @Override public String id() { return "fastformer:selection"; }
   @Override public int priority() { return 300; }
   @Override public Map<State, ModeInputPolicy> phases() { return PHASES; }
   @Override public Optional<State> observe(ModeObservation observation) {
      return !observation.operationSession() ? Optional.empty()
         : Optional.of(observation.workspaceHasParts() || observation.selectionConfirmed() ? State.ADJUSTING : State.SELECTING);
   }
}
