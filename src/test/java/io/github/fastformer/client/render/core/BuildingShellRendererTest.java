package io.github.fastformer.client.render.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class BuildingShellRendererTest {
   @Test
   void cachedShellAppliesPreviewOpacityExactlyOnce() {
      assertEquals(0.40F, BuildingShellRenderer.previewAlpha(0.80F, 0.50F), 1.0E-6F);
      assertEquals(0.80F, BuildingShellRenderer.previewAlpha(0.80F, 1.0F), 1.0E-6F);
   }
}
