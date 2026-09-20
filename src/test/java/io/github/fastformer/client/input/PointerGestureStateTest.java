package io.github.fastformer.client.input;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class PointerGestureStateTest {
   @Test
   void noneNeverRepresentsAnActiveGesture() {
      var state = new PointerGestureState();
      assertFalse(state.active(PointerGestureState.Kind.NONE));
      state.begin(PointerGestureState.Kind.WORKSPACE_FACE);
      state.cancel();
      assertEquals(0L, state.activeToken());
      assertFalse(state.active(PointerGestureState.Kind.NONE));
      assertFalse(state.owns(state.activeToken(), PointerGestureState.Kind.NONE));
   }

   @Test
   void lateReleaseCannotFinishTheReplacementGesture() {
      var state = new PointerGestureState();
      long old = state.begin(PointerGestureState.Kind.WORKSPACE_FACE);
      state.cancel();
      state.cancel();
      long current = state.begin(PointerGestureState.Kind.WORKSPACE_FACE);
      state.finish(old);
      assertTrue(state.owns(current, PointerGestureState.Kind.WORKSPACE_FACE));
      state.finish(current);
      state.finish(current);
      assertEquals(0L, state.activeToken());
      assertFalse(state.active(PointerGestureState.Kind.NONE));
   }

   @Test
   void invalidBeginCannotReplaceAnActiveGesture() {
      var state = new PointerGestureState();
      long token = state.begin(PointerGestureState.Kind.WORKSPACE_GIZMO);
      assertThrows(IllegalArgumentException.class, () -> state.begin(null));
      assertThrows(IllegalArgumentException.class, () -> state.begin(PointerGestureState.Kind.NONE));
      assertTrue(state.owns(token, PointerGestureState.Kind.WORKSPACE_GIZMO));
      assertFalse(state.owns(token, null));
   }

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
