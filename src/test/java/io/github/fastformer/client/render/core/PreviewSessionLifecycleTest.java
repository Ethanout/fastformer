package io.github.fastformer.client.render.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PreviewSessionLifecycleTest {
   @Test
   void worldSessionEndClearsEveryStateOwnerInOrder() {
      RecordingStateOwner stateOwner = new RecordingStateOwner();

      new PreviewSessionLifecycle(stateOwner).endWorldSession();

      assertEquals(
         List.of(
            "input",
            "source-mask",
            "preview-session",
            "operation",
            "interaction-cache",
            "feedback",
            "world-render-state"
         ),
         stateOwner.calls
      );
   }

   @Test
   void unfocusedWorldOnlyClearsFeedback() {
      RecordingStateOwner stateOwner = new RecordingStateOwner();
      PreviewSessionLifecycle lifecycle = new PreviewSessionLifecycle(stateOwner);

      lifecycle.clearFeedbackWithoutFocusedWorld(false);

      assertEquals(List.of("feedback"), stateOwner.calls);
   }

   @Test
   void focusedWorldKeepsFeedbackState() {
      RecordingStateOwner stateOwner = new RecordingStateOwner();

      new PreviewSessionLifecycle(stateOwner).clearFeedbackWithoutFocusedWorld(true);

      assertEquals(List.of(), stateOwner.calls);
   }

   private static final class RecordingStateOwner implements PreviewSessionLifecycle.StateOwner {
      private final List<String> calls = new ArrayList<>();

      @Override
      public void endInputSession() {
         calls.add("input");
      }

      @Override
      public void clearSourceMask() {
         calls.add("source-mask");
      }

      @Override
      public void resetPreviewSession() {
         calls.add("preview-session");
      }

      @Override
      public void disconnectOperation() {
         calls.add("operation");
      }

      @Override
      public void clearInteractionCache() {
         calls.add("interaction-cache");
      }

      @Override
      public void clearFeedback() {
         calls.add("feedback");
      }

      @Override
      public void clearWorldRenderState() {
         calls.add("world-render-state");
      }
   }
}
