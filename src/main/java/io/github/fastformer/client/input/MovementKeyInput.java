package io.github.fastformer.client.input;

import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

/** Shortcut ownership must not leave the same physical key held as movement. */
final class MovementKeyInput {
   private MovementKeyInput() { }

   static void reconcile(KeyMapping[] movementKeys, KeyboardInputSnapshot input, boolean shortcutOwnsKey) {
      if (input.action() != GLFW.GLFW_RELEASE && !shortcutOwnsKey) return;
      // Release the binding directly: modifier/context filtering can exclude it after a chord changes.
      for (var mapping : movementKeys) {
         if (mapping.matches(input.key(), input.scanCode())) mapping.setDown(false);
      }
   }
}
