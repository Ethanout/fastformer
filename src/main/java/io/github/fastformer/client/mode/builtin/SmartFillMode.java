package io.github.fastformer.client.mode.builtin;

import static io.github.fastformer.client.input.state.ClientInputStateMachine.Dispatch.*;
import io.github.fastformer.client.input.state.ClientInputStateMachine.State;
import io.github.fastformer.client.mode.ClientMode;
import io.github.fastformer.common.mode.BuiltinModes;
import io.github.fastformer.common.mode.ModeId;
import io.github.fastformer.client.input.state.ClientInputStateMachine.InputKind;
import io.github.fastformer.client.mode.ModeInputPolicy;
import io.github.fastformer.client.mode.ModeObservation;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import io.github.fastformer.client.mode.PreviewObservation;

/** Declares smart fill ownership and its phase input rules. */
public final class SmartFillMode implements ClientMode {
   private static final Map<State, ModeInputPolicy> PHASES = Map.of(State.FILL_PREVIEW,
      new ModeInputPolicy(BLOCKED, BLOCKED, BLOCKED, BLOCKED, CANCEL, false, false, true,
         Set.of(InputKind.POINTER, InputKind.INTERACTION, InputKind.SCROLL)));

   @Override public ModeId id() { return BuiltinModes.SMART_FILL; }
   @Override public boolean previewActive(PreviewObservation observation) { return observation.fill(); }
   @Override public int previewPriority(PreviewObservation observation) { return 600; }
   @Override public int priority() { return 500; }
   @Override public Map<State, ModeInputPolicy> phases() { return PHASES; }
   @Override public Optional<State> observe(ModeObservation observation) {
      return observation.fillSession() ? Optional.of(State.FILL_PREVIEW) : Optional.empty();
   }
}
