package io.github.fastformer.client.preview.frame;

import java.util.Objects;

/** Binds an already-built model to the capability that owns its drawing resources. */
public record PreviewLayer<M>(PreviewMode<?, M> mode, M model) {
   public PreviewLayer {
      Objects.requireNonNull(mode, "mode");
      Objects.requireNonNull(model, "model");
   }

   public static <I, M> PreviewLayer<M> capture(PreviewMode<I, M> mode, I input) {
      return new PreviewLayer<>(mode, mode.buildModel(input));
   }

   public void drawAfterLevel(WorldPreviewFrame frame) { mode.drawAfterLevel(frame, model); }

   public void draw(WorldPreviewFrame frame) { mode.drawWorld(frame, model); }
}
