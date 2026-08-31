package io.github.fastformer.client.render;

import io.github.fastformer.client.render.core.FastPlaceClientPreviewCore;

/**
 * Stable public entry point for client preview queries and render events.
 * Implementation is grouped under {@code render.core} to keep the render
 * package organized without changing callers or packet reflection names.
 */
public final class FastPlaceClientPreview extends FastPlaceClientPreviewCore {
   private FastPlaceClientPreview() {
   }
}
