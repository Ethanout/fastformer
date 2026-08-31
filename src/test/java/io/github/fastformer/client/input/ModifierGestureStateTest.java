package io.github.fastformer.client.input;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ModifierGestureStateTest {
   @Test
   void consumedAltCannotTriggerShortPressOnRelease() {
      ModifierGestureState state = new ModifierGestureState();
      state.press(100L, true, true);
      state.consume();

      ModifierGestureState.Release release = state.release(150L, 250L);

      assertTrue(release.routed());
      assertTrue(release.cycleEligible());
      assertFalse(release.shortPress());
      assertFalse(state.held());
   }

   @Test
   void unconsumedShortAltReleaseRemainsEligible() {
      ModifierGestureState state = new ModifierGestureState();
      state.press(100L, true, true);

      ModifierGestureState.Release release = state.release(200L, 250L);

      assertTrue(release.routed());
      assertTrue(release.cycleEligible());
      assertTrue(release.shortPress());
   }
}
