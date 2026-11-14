package io.github.fastformer.client.mode.builtin;

import static io.github.fastformer.client.input.state.ClientInputStateMachine.Dispatch.*;

import io.github.fastformer.client.input.state.ClientInputStateMachine.State;
import io.github.fastformer.client.mode.ClientMode;
import io.github.fastformer.client.mode.ModeInputPolicy;
import io.github.fastformer.client.mode.ModeObservation;
import java.util.Map;
import java.util.Optional;

/** Declares geometry ownership and its phase input rules. */
public final class GeometryMode implements ClientMode {
   private static final Map<State, ModeInputPolicy> PHASES = Map.of(State.GEOMETRY, new ModeInputPolicy(GEOMETRY, GEOMETRY, BLOCKED, BLOCKED, CANCEL, true, true, true));

   @Override public String id() { return "fastformer:geometry"; }
   @Override public int priority() { return 200; }
   @Override public Map<State, ModeInputPolicy> phases() { return PHASES; }
   @Override public Optional<State> observe(ModeObservation observation) {
      return observation.geometrySession() ? Optional.of(State.GEOMETRY) : Optional.empty();
   }
}
