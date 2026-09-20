package io.github.fastformer.client.input;

/**
 * Decides whether the world input context still owns an active pointer
 * gesture.
 *
 * <p>A screen or a lost window focus takes the mouse away from the world. The
 * mouse release that belongs to the gesture then never reaches the world
 * handler, so the gesture must be ended by the context change itself instead of
 * waiting for a release event that no longer arrives.
 */
public final class InputContextBoundary {
   private InputContextBoundary() {
   }

   /**
    * Returns false when an in-flight gesture must be cancelled.
    *
    * @param gestureActive a drag or an explicit click capture is in flight
    * @param screenOpen    a screen owns the mouse
    * @param windowFocused the game window still has focus
    */
   public static boolean pointerContextIntact(
      boolean gestureActive,
      boolean screenOpen,
      boolean windowFocused
   ) {
      if (!gestureActive) {
         return true;
      }
      return !screenOpen && windowFocused;
   }
}
