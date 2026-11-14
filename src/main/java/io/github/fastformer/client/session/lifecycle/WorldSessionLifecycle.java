package io.github.fastformer.client.session.lifecycle;

import io.github.fastformer.core.lifecycle.Scope;
import io.github.fastformer.core.lifecycle.Scoped;
import io.github.fastformer.core.lifecycle.ScopedKey;
import java.util.Objects;

/** Ordered world-boundary cleanup; one participant failure cannot skip the remaining resources. */
final class WorldSessionLifecycle {
   interface Participants {
      void endInputSession();
      void clearSourceMask();
      void resetPreviewSession();
      void disconnectOperation();
      void clearInteractionCache();
      void clearFeedback();
      void clearWorldRenderState();
   }

   private final Scope resources = new Scope();

   WorldSessionLifecycle(Participants participants) {
      Objects.requireNonNull(participants, "participants");
      step("input", participants::endInputSession);
      step("source_mask", participants::clearSourceMask);
      step("preview", participants::resetPreviewSession);
      step("operation", participants::disconnectOperation);
      step("interaction_cache", participants::clearInteractionCache);
      step("feedback", participants::clearFeedback);
      step("render", participants::clearWorldRenderState);
   }

   void onLeave() { resources.detach(); }

   private void step(String id, Runnable cleanup) {
      resources.get(ScopedKey.create(id, () -> new Cleanup(cleanup)));
   }

   private record Cleanup(Runnable run) implements Scoped {
      @Override public void onDetach() { run.run(); }
      @Override public void onDiscard() { run.run(); }
   }
}
