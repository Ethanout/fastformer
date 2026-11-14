package io.github.fastformer.client.preview.assembly;

/** A revision becomes rendered only after all dependent resources have been invalidated. */
final class ThemeResourceInvalidation {
   private long renderedRevision = -1;

   void update(long revision, Runnable geometryPlan, Runnable previewResources, Runnable gpuResources) {
      if (renderedRevision == revision) return;
      geometryPlan.run();
      previewResources.run();
      gpuResources.run();
      renderedRevision = revision;
   }
}
