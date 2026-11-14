package io.github.fastformer.client.mode.builtin;

import static io.github.fastformer.client.input.state.ClientInputStateMachine.Dispatch.*;

import io.github.fastformer.client.input.state.ClientInputStateMachine.State;
import io.github.fastformer.client.mode.ClientMode;
import io.github.fastformer.client.mode.input.KeyDecision;
import io.github.fastformer.client.mode.input.KeyInput;
import io.github.fastformer.client.mode.input.KeyView;
import io.github.fastformer.client.mode.ModeInputPolicy;
import io.github.fastformer.client.mode.ModeObservation;
import java.util.Map;
import java.util.Optional;

/** Declares idle ownership and its phase input rules. */
public final class IdleMode implements ClientMode {
   private static final Map<State, ModeInputPolicy> PHASES = Map.of(State.IDLE, new ModeInputPolicy(VANILLA, BLOCKED, BLOCKED, VANILLA, BLOCKED, false, false, false));

   @Override public String id() { return "fastformer:idle"; }
   @Override public int priority() { return 0; }
   @Override public KeyDecision onKey(KeyInput input, KeyView view) {
      if (!view.playerReady() || !view.idleActionsAllowed()) return KeyDecision.PASS;
      if (view.fillKey() && view.canStartFill()) return input.press() ? KeyDecision.START_FILL : KeyDecision.CONSUME;
      return input.press() && input.key() == 82 && !input.controlDown()
         ? KeyDecision.TOGGLE_REPLACEMENT : KeyDecision.PASS;
   }
   @Override public Map<State, ModeInputPolicy> phases() { return PHASES; }
   @Override public Optional<State> observe(ModeObservation observation) {
      return Optional.of(State.IDLE);
   }

   @Override public boolean interactive() { return false; }
}
