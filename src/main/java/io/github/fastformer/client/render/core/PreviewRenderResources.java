package io.github.fastformer.client.render.core;

/** Remaining shared smart-selection resources awaiting mode ownership. */
final class PreviewRenderResources {
   private PreviewRenderResources() { }

   static void clearShells() {
      io.github.fastformer.client.render.PreviewMaterialRenderer.clear();
      SmartSelectionRenderer.clear();
      SmartSelectionIsolationRenderer.clear();
   }

}
