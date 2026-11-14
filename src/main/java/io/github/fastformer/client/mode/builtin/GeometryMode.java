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

/** Declares geometry ownership and its phase input rules. */
public final class GeometryMode implements ClientMode {
   private static final Map<State, ModeInputPolicy> PHASES = Map.of(State.GEOMETRY, new ModeInputPolicy(GEOMETRY, GEOMETRY, BLOCKED, BLOCKED, CANCEL, true, true, true));

   @Override public ModeId id() { return BuiltinModes.GEOMETRY; }
   @Override public PointerDecision onPointer(PointerInput input, PointerView view) {
      if (view instanceof PointerView.Left left && input.press(0)) {
         var captured = PointerCaptureRules.left(left);
         return captured != PointerDecision.Left.NONE ? captured
            : left.undoAvailable() ? PointerDecision.Left.UNDO : PointerDecision.Left.NONE;
      }
      if (view instanceof PointerView.Right right && input.press(1)) {
         return right.vanillaOwnsPointer() ? PointerDecision.Right.YIELD_TO_VANILLA
            : right.geometryCaptured() ? PointerDecision.Right.GEOMETRY_CAPTURE : PointerDecision.Right.GEOMETRY_INTERACTION;
      }
      return PointerDecision.Flow.PASS;
   }
   @Override public int priority() { return 200; }
   @Override public ScrollDecision onScroll(ScrollInput input, ScrollView view) {
      return CandidateScrollRules.decide(input, view);
   }
   @Override public Map<State, ModeInputPolicy> phases() { return PHASES; }
   @Override public Optional<State> observe(ModeObservation observation) {
      return observation.geometrySession() ? Optional.of(State.GEOMETRY) : Optional.empty();
   }
}
