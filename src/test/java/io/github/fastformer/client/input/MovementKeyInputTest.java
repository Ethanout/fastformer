package io.github.fastformer.client.input;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.client.KeyMapping;
import net.neoforged.neoforge.client.settings.IKeyConflictContext;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

class MovementKeyInputTest {
   private static final IKeyConflictContext ACTIVE = new IKeyConflictContext() {
      public boolean isActive() { return true; }
      public boolean conflicts(IKeyConflictContext other) { return true; }
   };

   @Test
   void selectionShortcutsAndTheirRepeatsDoNotStrafeOrInterruptOtherMovement() {
      var forward = mapping("forward", GLFW.GLFW_KEY_W);
      var left = mapping("left", GLFW.GLFW_KEY_A);
      var right = mapping("right", GLFW.GLFW_KEY_D);
      var movement = new KeyMapping[]{forward, left, right};
      forward.setDown(true);
      for (var direction : new KeyMapping[]{left, right}) {
         for (int action : new int[]{GLFW.GLFW_PRESS, GLFW.GLFW_REPEAT}) {
            direction.setDown(true); // Vanilla updates the key before the mod input event.
            MovementKeyInput.reconcile(movement, input(direction.getKey().getValue(), action, true), true);
            assertFalse(direction.isDown());
            assertTrue(forward.isDown());
         }
      }
   }

   @Test
   void releaseClearsMovementWhetherControlIsReleasedBeforeOrAfterTheLetter() {
      var left = mapping("released_left", GLFW.GLFW_KEY_A);
      for (boolean control : new boolean[]{false, true}) {
         left.setDown(true);
         MovementKeyInput.reconcile(new KeyMapping[]{left}, input(GLFW.GLFW_KEY_A, GLFW.GLFW_RELEASE, control), false);
         assertFalse(left.isDown());
      }
   }

   @Test
   void ordinaryMovementPressesArePreserved() {
      var right = mapping("ordinary_right", GLFW.GLFW_KEY_D);
      right.setDown(true);
      MovementKeyInput.reconcile(new KeyMapping[]{right}, input(GLFW.GLFW_KEY_D, GLFW.GLFW_PRESS, false), false);
      assertTrue(right.isDown());
   }

   @Test
   void customMovementBindingsUseTheirActualKeys() {
      var forward = mapping("custom_forward", GLFW.GLFW_KEY_C);
      forward.setDown(true);
      MovementKeyInput.reconcile(new KeyMapping[]{forward}, input(GLFW.GLFW_KEY_C, GLFW.GLFW_PRESS, true), true);
      assertFalse(forward.isDown());
   }

   private static KeyMapping mapping(String name, int key) {
      var mapping = new KeyMapping("fastformer.test." + name, key, KeyMapping.CATEGORY_MOVEMENT);
      mapping.setKeyConflictContext(ACTIVE);
      return mapping;
   }

   private static KeyboardInputSnapshot input(int key, int action, boolean control) {
      return new KeyboardInputSnapshot(key, 0, action, 0, 1, false, control);
   }
}
