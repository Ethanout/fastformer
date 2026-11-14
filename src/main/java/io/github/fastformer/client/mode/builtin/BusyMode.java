package io.github.fastformer.client.mode.builtin;

import static io.github.fastformer.client.input.state.ClientInputStateMachine.Dispatch.*;

import io.github.fastformer.client.input.state.ClientInputStateMachine.State;
import io.github.fastformer.client.mode.ClientMode;
import io.github.fastformer.client.mode.ModeInputPolicy;
import io.github.fastformer.client.mode.ModeObservation;
import java.util.Map;
import java.util.Optional;

/** Declares busy ownership and its phase input rules. */
public final class BusyMode implements ClientMode {
   private static final Map<State, ModeInputPolicy> PHASES = Map.of(
      State.SUBMITTING, new ModeInputPolicy(BLOCKED, BLOCKED, BLOCKED, BLOCKED, CANCEL, false, false, true),
      State.PLACING, new ModeInputPolicy(BLOCKED, BLOCKED, BLOCKED, BLOCKED, CANCEL, false, false, true),
      State.RESTORING, new ModeInputPolicy(BLOCKED, BLOCKED, BLOCKED, BLOCKED, BLOCKED, false, false, true),
      State.CANCELLING, new ModeInputPolicy(BLOCKED, BLOCKED, BLOCKED, BLOCKED, BLOCKED, false, false, true));

   @Override public String id() { return "fastformer:busy"; }
   @Override public int priority() { return 600; }
   @Override public Map<State, ModeInputPolicy> phases() { return PHASES; }
   @Override public Optional<State> observe(ModeObservation observation) {
      return observation.restoringTask() ? Optional.of(State.RESTORING)
         : observation.placementTask() ? Optional.of(State.PLACING) : Optional.empty();
   }

   @Override public boolean interactive() { return false; }
}

