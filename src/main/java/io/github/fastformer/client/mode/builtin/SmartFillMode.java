package io.github.fastformer.client.mode.builtin;

import static io.github.fastformer.client.input.state.ClientInputStateMachine.Dispatch.*;

import io.github.fastformer.client.input.state.ClientInputStateMachine.State;
import io.github.fastformer.client.mode.ClientMode;
import io.github.fastformer.client.mode.input.ScrollDecision;
import io.github.fastformer.client.mode.input.ScrollInput;
import io.github.fastformer.client.mode.input.ScrollView;
import io.github.fastformer.client.mode.input.KeyDecision;
import io.github.fastformer.client.mode.input.KeyInput;
import io.github.fastformer.client.mode.input.KeyView;
import io.github.fastformer.client.mode.ModeInputPolicy;
import io.github.fastformer.client.mode.ModeObservation;
import java.util.Map;
import java.util.Optional;

/** Declares smart fill ownership and its phase input rules. */
public final class SmartFillMode implements ClientMode {
   private static final Map<State, ModeInputPolicy> PHASES = Map.of(State.FILL_PREVIEW, new ModeInputPolicy(BLOCKED, BLOCKED, BLOCKED, BLOCKED, CANCEL, false, false, true));

   @Override public String id() { return "fastformer:smart_fill"; }
   @Override public int priority() { return 500; }
   @Override public KeyDecision onKey(KeyInput input, KeyView view) {
      if (!view.playerReady() || !view.fillKey() && !input.enter()) return KeyDecision.PASS;
      return input.press() && view.fillReady() ? KeyDecision.CONFIRM_FILL : KeyDecision.CONSUME;
   }
   @Override public ScrollDecision onScroll(ScrollInput input, ScrollView view) { return ScrollDecision.block(); }
   @Override public Map<State, ModeInputPolicy> phases() { return PHASES; }
   @Override public Optional<State> observe(ModeObservation observation) {
      return observation.fillSession() ? Optional.of(State.FILL_PREVIEW) : Optional.empty();
   }
}
