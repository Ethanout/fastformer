package io.github.fastformer.server.replace;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class QuickReplaceDedupeTest {
   @Test void firstRequestAcceptsEveryTickValueWithoutUnboxingAbsence() {
      assertTrue(new QuickReplaceDedupe().accept(41));
      assertTrue(new QuickReplaceDedupe().accept(0));
      assertTrue(new QuickReplaceDedupe().accept(Long.MIN_VALUE));
   }

   @Test void repeatedTickRejectsUntilANewTick() {
      var state = new QuickReplaceDedupe();
      assertTrue(state.accept(41));
      assertFalse(state.accept(41));
      assertTrue(state.accept(42));
      assertFalse(state.accept(42));
   }

   @Test void playersHaveIndependentTickMemory() {
      var first = new QuickReplaceDedupe();
      var second = new QuickReplaceDedupe();
      assertTrue(first.accept(41));
      assertTrue(second.accept(41));
      assertFalse(first.accept(41));
   }

   @Test void cleanupAllowsTheFirstRequestAgain() {
      var state = new QuickReplaceDedupe();
      assertTrue(state.accept(41));
      assertFalse(state.accept(41));
      state.clear();
      assertTrue(state.accept(41));
   }
}
