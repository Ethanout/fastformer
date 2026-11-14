package io.github.fastformer.client.mode.builtin;

import static io.github.fastformer.client.input.state.ClientInputStateMachine.Dispatch.*;

import io.github.fastformer.client.input.state.ClientInputStateMachine.State;
import io.github.fastformer.client.mode.ClientMode;
import io.github.fastformer.client.mode.ModeInputPolicy;
import io.github.fastformer.client.mode.ModeObservation;
import java.util.Map;
import java.util.Optional;

/** Declares quick shape ownership and its phase input rules. */
public final class QuickShapeMode implements ClientMode {
   private static final Map<State, ModeInputPolicy> PHASES = Map.of(State.BUILDING, new ModeInputPolicy(BUILDING, BUILDING, BLOCKED, BLOCKED, CANCEL, true, true, true));

   @Override public String id() { return "fastformer:quick_shape"; }
   @Override public int priority() { return 100; }
   @Override public Map<State, ModeInputPolicy> phases() { return PHASES; }
   @Override public Optional<State> observe(ModeObservation observation) {
      return observation.buildingSession() ? Optional.of(State.BUILDING) : Optional.empty();
   }
}
