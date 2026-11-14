package io.github.fastformer.client.mode.builtin;

import static io.github.fastformer.client.input.state.ClientInputStateMachine.Dispatch.*;

import io.github.fastformer.client.input.state.ClientInputStateMachine.State;
import io.github.fastformer.client.mode.ClientMode;
import io.github.fastformer.client.mode.ModeInputPolicy;
import io.github.fastformer.client.mode.ModeObservation;
import java.util.Map;
import java.util.Optional;

/** Declares idle ownership and its phase input rules. */
public final class IdleMode implements ClientMode {
   private static final Map<State, ModeInputPolicy> PHASES = Map.of(State.IDLE, new ModeInputPolicy(VANILLA, BLOCKED, BLOCKED, VANILLA, BLOCKED, false, false, false));

   @Override public String id() { return "fastformer:idle"; }
   @Override public int priority() { return 0; }
   @Override public Map<State, ModeInputPolicy> phases() { return PHASES; }
   @Override public Optional<State> observe(ModeObservation observation) {
      return Optional.of(State.IDLE);
   }

   @Override public boolean interactive() { return false; }
}

