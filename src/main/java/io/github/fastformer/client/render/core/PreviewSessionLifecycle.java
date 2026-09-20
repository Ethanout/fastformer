package io.github.fastformer.client.render.core;

/** Coordinates cleanup at a client world-session boundary without owning its state. */
final class PreviewSessionLifecycle {
   interface StateOwner {
      void endInputSession();

      void clearSourceMask();

      void resetPreviewSession();

      void disconnectOperation();

      void clearInteractionCache();

      void clearFeedback();

      void clearWorldRenderState();
   }

   private final StateOwner stateOwner;

   PreviewSessionLifecycle(StateOwner stateOwner) {
      this.stateOwner = stateOwner;
   }

   void endWorldSession() {
      stateOwner.endInputSession();
      stateOwner.clearSourceMask();
      stateOwner.resetPreviewSession();
      stateOwner.disconnectOperation();
      stateOwner.clearInteractionCache();
      stateOwner.clearFeedback();
      stateOwner.clearWorldRenderState();
   }

   void clearFeedbackWithoutFocusedWorld(boolean focusedWorld) {
      if (!focusedWorld) {
         stateOwner.clearFeedback();
      }
   }
}
