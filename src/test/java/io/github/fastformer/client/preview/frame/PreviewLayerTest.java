package io.github.fastformer.client.preview.frame;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class PreviewLayerTest {
   @Test void drawingUsesTheCapturedModelWithoutRebuildingIt() {
      var mode = new RecordingMode();
      var layer = PreviewLayer.capture(mode, 7);
      assertEquals(1, mode.builds);
      assertEquals(14, layer.model());
      layer.draw(null);
      layer.draw(null);
      assertEquals(1, mode.builds);
      assertEquals(2, mode.draws);
      assertEquals(14, mode.lastDrawn);
   }

   private static final class RecordingMode implements PreviewMode<Integer, Integer> {
      int builds, draws, lastDrawn;
      public Integer buildModel(Integer input) { builds++; return input * 2; }
      public void drawWorld(WorldPreviewFrame frame, Integer model) { draws++; lastDrawn = model; }
      public void clear() {}
   }
}
