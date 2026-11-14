package io.github.fastformer.client.input.state;

import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_RIGHT;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;
import static org.lwjgl.glfw.GLFW.GLFW_RELEASE;

/** Consumes follow-up physical presses and their paired releases within one fixed window. */
public final class EntryPressGuard {
   private long protectionNanos;
   private int physicalButton;
   private boolean armed;
   private long enteredAt;
   private boolean suppressedPress;

   public void arm(long now) {
      arm(now, GLFW_MOUSE_BUTTON_RIGHT, 200);
   }

   public void arm(long now, int physicalButton, int durationMillis) {
      if (durationMillis < 1 || durationMillis > 60_000) throw new IllegalArgumentException("Invalid entry guard duration");
      protectionNanos = durationMillis * 1_000_000L;
      this.physicalButton = physicalButton;
      enteredAt = now;
      armed = true;
   }

   public boolean capture(int action, int button, long now) {
      if (button != physicalButton) return false;
      if (action == GLFW_RELEASE && suppressedPress) {
         suppressedPress = false;
         return true;
      }
      if (action != GLFW_PRESS || !armed) return false;
      long elapsed = now - enteredAt;
      if (elapsed < 0 || elapsed > protectionNanos) {
         armed = false;
         return false;
      }
      suppressedPress = true;
      return true;
   }

   public void clear() {
      armed = false;
      suppressedPress = false;
   }
}
