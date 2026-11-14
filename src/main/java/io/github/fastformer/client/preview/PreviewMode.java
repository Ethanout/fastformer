package io.github.fastformer.client.preview;

import io.github.fastformer.client.preview.frame.WorldPreviewFrame;

/** A preview capability constructs its own model and draws only from explicit frame data. */
public interface PreviewMode<I, M> {
   M buildModel(I input);
   void drawWorld(WorldPreviewFrame frame, M model);
   void clear();
}
