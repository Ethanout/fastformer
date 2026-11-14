package io.github.fastformer.client.mode.builtin;

import static io.github.fastformer.client.input.state.ClientInputStateMachine.Dispatch.*;

import io.github.fastformer.client.input.state.ClientInputStateMachine.State;
import io.github.fastformer.client.mode.ClientMode;
import io.github.fastformer.common.mode.BuiltinModes;
import io.github.fastformer.common.mode.ModeId;
import io.github.fastformer.client.mode.input.CandidateScrollRules;
import io.github.fastformer.client.mode.input.ScrollDecision;
import io.github.fastformer.client.mode.input.ScrollInput;
import io.github.fastformer.client.mode.input.ScrollView;
import io.github.fastformer.client.mode.ModeInputPolicy;
import io.github.fastformer.client.mode.ModeObservation;
import io.github.fastformer.client.mode.input.pointer.*;
import java.util.Map;
import java.util.Optional;

/** Declares quick shape ownership and its phase input rules. */
public final class QuickShapeMode implements ClientMode {
   private static final Map<State, ModeInputPolicy> PHASES = Map.of(State.BUILDING, new ModeInputPolicy(BUILDING, BUILDING, BLOCKED, BLOCKED, CANCEL, true, true, true));

   @Override public ModeId id() { return BuiltinModes.QUICK_SHAPE; }
   @Override public PointerDecision onPointer(PointerInput input, PointerView view) {
      if (!(view instanceof PointerView.Left left) || !input.press(0)) return PointerDecision.Flow.PASS;
      var captured = PointerCaptureRules.left(left);
      return captured != PointerDecision.Left.NONE ? captured
         : left.undoAvailable() ? PointerDecision.Left.UNDO : PointerDecision.Left.NONE;
   }
   @Override public int priority() { return 100; }
   @Override public ScrollDecision onScroll(ScrollInput input, ScrollView view) {
      return CandidateScrollRules.decide(input, view);
   }
   @Override public Map<State, ModeInputPolicy> phases() { return PHASES; }
   @Override public Optional<State> observe(ModeObservation observation) {
      return observation.buildingSession() ? Optional.of(State.BUILDING) : Optional.empty();
   }
}
