package io.github.fastformer.client.input;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PointerGestureStateTest {
   @Test
   void startingAnotherGestureInvalidatesThePreviousToken() {
      PointerGestureState state = new PointerGestureState();
      long first = state.begin(PointerGestureState.Kind.OPERATION_FACE);
      long second = state.begin(PointerGestureState.Kind.WORKSPACE_GIZMO);

      assertFalse(state.owns(first, PointerGestureState.Kind.OPERATION_FACE));
      assertTrue(state.owns(second, PointerGestureState.Kind.WORKSPACE_GIZMO));
   }

   @Test
   void cancelInvalidatesTheActiveGesture() {
      PointerGestureState state = new PointerGestureState();
      long token = state.begin(PointerGestureState.Kind.OPERATION_POINT);

      state.cancel();

      assertFalse(state.owns(token, PointerGestureState.Kind.OPERATION_POINT));
      assertFalse(state.active(PointerGestureState.Kind.OPERATION_POINT));
   }
}
