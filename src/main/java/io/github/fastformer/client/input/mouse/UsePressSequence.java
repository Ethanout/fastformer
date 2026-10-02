package io.github.fastformer.client.input.mouse;

/** Keeps a released physical click from becoming a held use after an ownership change. */
public final class UsePressSequence {
   private boolean tracked;
   private boolean held;
   private int pendingClicks;

   public void press() {
      tracked = true;
      held = true;
      pendingClicks++;
   }

   public void release() { held = false; }

   public boolean accept() {
      if (!tracked) return true;
      if (pendingClicks > 0) {
         pendingClicks--;
         return true;
      }
      return held;
   }
}
