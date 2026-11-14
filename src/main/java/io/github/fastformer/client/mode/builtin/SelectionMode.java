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

/** Declares selection ownership and its phase input rules. */
public final class SelectionMode implements ClientMode {
   private static final Map<State, ModeInputPolicy> PHASES = Map.of(
      State.SELECTING, new ModeInputPolicy(OPERATION, BLOCKED, OPERATION, OPERATION, CANCEL, false, true, false),
      State.ADJUSTING, new ModeInputPolicy(OPERATION, OPERATION, OPERATION, OPERATION, CANCEL, true, true, false));

   @Override public ModeId id() { return BuiltinModes.SELECTION; }
   @Override public PointerDecision onPointer(PointerInput input, PointerView view) {
      if (view instanceof PointerView.Left left && input.press(0)) {
         var captured = PointerCaptureRules.left(left);
         return captured == PointerDecision.Left.NONE ? PointerDecision.Left.OPERATION_CAPTURE : captured;
      }
      if (view instanceof PointerView.Right right && input.press(1)) return rightPress(right);
      return PointerDecision.Flow.PASS;
   }

   private static PointerDecision.Right rightPress(PointerView.Right view) {
      if (view.workspacePointerTarget()) return PointerDecision.Right.WORKSPACE_POINTER;
      if (view.workspaceActive()) return PointerDecision.Right.WORKSPACE_ADJUST_OR_CAPTURE;
      if (view.selectionConfirmed()) {
         return view.vanillaOwnsPointer() ? PointerDecision.Right.YIELD_TO_VANILLA : PointerDecision.Right.CONFIRMED_GIZMO_CAPTURE;
      }
      if (view.vanillaOwnsPointer() && !view.operationAdjust() && !view.operationAlt() && !view.operationPointTarget()) {
         return PointerDecision.Right.YIELD_TO_VANILLA;
      }
      if (view.operationAlt()) return PointerDecision.Right.OPERATION_ALT;
      if (view.operationAdjust()) return PointerDecision.Right.OPERATION_ADJUST;
      if (view.operationPointTarget()) return PointerDecision.Right.OPERATION_POINT;
      if (view.operationEdgeTarget()) return PointerDecision.Right.OPERATION_EDGE;
      if (view.operationNextPointTarget()) return PointerDecision.Right.OPERATION_NEXT_POINT;
      if (view.cuboidWorldTarget()) return PointerDecision.Right.CUBOID_NEXT_POINT;
      if (view.cuboidFaceTarget()) return PointerDecision.Right.CUBOID_FACE;
      return view.closePathTarget() ? PointerDecision.Right.CLOSE_PATH : PointerDecision.Right.NONE;
   }
   @Override public int priority() { return 300; }
   @Override public ScrollDecision onScroll(ScrollInput input, ScrollView view) {
      if (input.direction() != 0 && view.workspaceActive()) return ScrollDecision.workspace(input.direction());
      return CandidateScrollRules.decide(input, view);
   }
   @Override public Map<State, ModeInputPolicy> phases() { return PHASES; }
   @Override public Optional<State> observe(ModeObservation observation) {
      return !observation.operationSession() ? Optional.empty()
         : Optional.of(observation.workspaceHasParts() || observation.selectionConfirmed() ? State.ADJUSTING : State.SELECTING);
   }
}
