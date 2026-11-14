package io.github.fastformer.client.preview;

import com.mojang.logging.LogUtils;
import io.github.fastformer.client.input.PointerDragSnapshotView;
import io.github.fastformer.client.lifecycle.ClientBackgroundTasks;
import io.github.fastformer.client.preview.geometry.GeometryPreviewMode;
import io.github.fastformer.client.preview.quickshape.QuickShapePreviewMode;
import io.github.fastformer.client.session.ClientSessionSystem;
import org.slf4j.Logger;

/** Owns preview generation capabilities, asynchronous result consumption, and their shared diagnostics. */
public final class ClientPreviewSystem {
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final long FAILURE_INTERVAL_NANOS = 5_000_000_000L;
   private final QuickShapePreviewMode quickShape;
   private final GeometryPreviewMode geometry;
   private long lastFailureAt;

   private ClientPreviewSystem() {
      var sessions = ClientSessionSystem.instance();
      quickShape = new QuickShapePreviewMode(sessions.previews(),
         () -> PointerDragSnapshotView.modifierHeld(sessions.inputSession()), this::logFailure,
         ClientBackgroundTasks.serial("preview-generation", 16));
      geometry = new GeometryPreviewMode(this::logFailure, ClientBackgroundTasks.serial("preview-mesh", 16));
   }

   private static final class Holder {
      private static final ClientPreviewSystem INSTANCE = new ClientPreviewSystem();
   }

   public static ClientPreviewSystem instance() { return Holder.INSTANCE; }
   public QuickShapePreviewMode quickShape() { return quickShape; }
   public GeometryPreviewMode geometry() { return geometry; }

   public void tick() { quickShape.tick(); }

   public void clear() {
      quickShape.clear();
      geometry.clear();
   }

   private void logFailure(String message, Throwable failure) {
      long now = System.nanoTime();
      if (lastFailureAt == 0L || now < lastFailureAt || now - lastFailureAt >= FAILURE_INTERVAL_NANOS) {
         lastFailureAt = now;
         LOGGER.warn(message, failure);
      }
   }
}
