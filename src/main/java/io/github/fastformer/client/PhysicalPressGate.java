package io.github.fastformer.client;

/** Allows one logical action during each physical press/release cycle. */
final class PhysicalPressGate {
   private boolean pressed;
   private boolean consumed;

   void press() {
      if (!pressed) {
         pressed = true;
         consumed = false;
      }
   }

   boolean consume() {
      if (!pressed) {
         pressed = true;
      }
      if (consumed) {
         return false;
      }
      consumed = true;
      return true;
   }

   void release() {
      pressed = false;
      consumed = false;
   }
}
