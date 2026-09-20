package io.github.fastformer.client.input;

/** Physical key data captured before semantic dispatch. */
record KeyboardInputSnapshot(
   int key,
   int scanCode,
   int action,
   int modifiers,
   long occurredAtNanos,
   boolean altDown,
   boolean controlDown
) {
   static KeyboardInputSnapshot capture(
      int key, int scanCode, int action, int modifiers, long occurredAtNanos,
      boolean leftAlt, boolean rightAlt, boolean leftControl, boolean rightControl
   ) {
      return new KeyboardInputSnapshot(key, scanCode, action, modifiers, occurredAtNanos,
         afterEvent(key, action, 342, leftAlt) || afterEvent(key, action, 346, rightAlt),
         afterEvent(key, action, 341, leftControl) || afterEvent(key, action, 345, rightControl));
   }

   boolean altKey() {
      return this.key == 342 || this.key == 346;
   }

   boolean controlKey() {
      return this.key == 341 || this.key == 345;
   }

   private static boolean afterEvent(int eventKey, int action, int modifierKey, boolean physicallyDown) {
      if (eventKey != modifierKey) return physicallyDown;
      return action != 0;
   }
}
