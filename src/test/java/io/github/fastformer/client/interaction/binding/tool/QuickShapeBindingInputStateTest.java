package io.github.fastformer.client.interaction.binding.tool;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class QuickShapeBindingInputStateTest {
   @Test void interactionReusesThePolicyOfEachPhysicalPress() {
      var state = new QuickShapeBindingInputState();
      var vanilla = new QuickShapeBindingInput.Result(true, false);
      var consumed = new QuickShapeBindingInput.Result(true, true);
      state.pressed(0, vanilla);
      state.pressed(1, consumed);
      assertSame(vanilla, state.interaction(0));
      assertSame(consumed, state.interaction(1));
      state.pressed(1, vanilla);
      assertSame(vanilla, state.interaction(1));
      assertNull(state.interaction(-1));
      assertNull(state.interaction(4));
   }

   @Test void cancellationInvalidatesQueuedCommandsAndCapturedPolicies() {
      var state = new QuickShapeBindingInputState();
      long generation = state.generation();
      state.pressed(1, new QuickShapeBindingInput.Result(true, true));
      assertTrue(state.accepts(generation));
      state.clear();
      assertFalse(state.accepts(generation));
      assertNull(state.interaction(1));
      assertTrue(state.accepts(state.generation()));
   }
}
