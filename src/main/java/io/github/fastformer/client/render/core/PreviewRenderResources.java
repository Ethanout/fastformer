package io.github.fastformer.client.render.core;

import io.github.fastformer.client.render.model.PlacementModelPreview;

/** Remaining initial-point and smart-selection resources awaiting mode ownership. */
final class PreviewRenderResources {
   private PreviewRenderResources() { }
   static final PlacementModelPreview INITIAL_MODEL = new PlacementModelPreview();

   static void clearShells() {
      INITIAL_MODEL.clear();
      io.github.fastformer.client.render.PreviewMaterialRenderer.clear();
      SmartSelectionRenderer.clear();
      SmartSelectionIsolationRenderer.clear();
   }

}
