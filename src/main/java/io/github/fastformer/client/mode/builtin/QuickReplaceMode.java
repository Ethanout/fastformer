package io.github.fastformer.client.mode.builtin;

import static io.github.fastformer.client.input.state.ClientInputStateMachine.Dispatch.*;

import io.github.fastformer.client.input.state.ClientInputStateMachine.State;
import io.github.fastformer.client.mode.ClientMode;
import io.github.fastformer.common.mode.BuiltinModes;
import io.github.fastformer.common.mode.ModeId;
import io.github.fastformer.client.mode.ModeInputPolicy;
import io.github.fastformer.client.mode.ModeObservation;
import java.util.Map;
import java.util.Optional;

/** Declares quick replace ownership and its phase input rules. */
public final class QuickReplaceMode implements ClientMode {
   private static final Map<State, ModeInputPolicy> PHASES = Map.of(State.QUICK_REPLACE, new ModeInputPolicy(VANILLA, BLOCKED, BLOCKED, BLOCKED, BLOCKED, false, false, true));

   @Override public ModeId id() { return BuiltinModes.QUICK_REPLACE; }
   @Override public int priority() { return 400; }
   @Override public Map<State, ModeInputPolicy> phases() { return PHASES; }
   @Override public Optional<State> observe(ModeObservation observation) {
      return observation.quickReplaceSession() ? Optional.of(State.QUICK_REPLACE) : Optional.empty();
   }
}
