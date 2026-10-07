package io.github.fastformer.client.input;

import io.github.fastformer.client.input.mouse.MouseButtonInputSemantics;

/** Consumes follow-up right presses and their releases within one fixed protection window. */
final class RightClickInertiaGuard {
   private static final long PROTECTION_NANOS = MouseButtonInputSemantics.ENTRY_DOUBLE_PRESS_NANOS;
   private boolean armed;
   private long enteredAt;
   private boolean suppressedPress;

   void arm(long now) {
      enteredAt = now;
      armed = true;
   }

   boolean capture(int action, int button, long now) {
      if (button != MouseButtonInputSemantics.RIGHT_BUTTON) return false;
      if (action == MouseButtonInputSemantics.RELEASE && suppressedPress) {
         suppressedPress = false;
         return true;
      }
      if (action != MouseButtonInputSemantics.PRESS || !armed) return false;
      long elapsed = now - enteredAt;
      if (elapsed < 0 || elapsed > PROTECTION_NANOS) {
         armed = false;
         return false;
      }
      suppressedPress = true;
      return true;
   }

   void clear() {
      armed = false;
      suppressedPress = false;
   }
}
