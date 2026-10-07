package io.github.fastformer.client.render.hud;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class ScreenPixelHysteresisTest {
   @Test void holdsSmallOscillationsAcrossARoundingBoundary() {
      var pixels = new ScreenPixelHysteresis();
      pixels.update(10.49, 20.49, 1);
      for (double offset : new double[] {0.51, 0.47, 0.6, 0.49, 0.7}) {
         assertEquals(new ScreenPixelHysteresis.Position(10, 20), pixels.update(10 + offset, 20 + offset, 1));
      }
      assertEquals(new ScreenPixelHysteresis.Position(11, 21), pixels.update(10.8, 20.8, 1));
      assertEquals(new ScreenPixelHysteresis.Position(11, 21), pixels.update(10.49, 20.49, 1));
      assertEquals(new ScreenPixelHysteresis.Position(10, 20), pixels.update(10.2, 20.2, 1));
   }

   @Test void cameraMotionHasNoTimeDelayOrAccumulatedOffset() {
      var pixels = new ScreenPixelHysteresis();
      pixels.update(0, 0, 1);
      assertEquals(new ScreenPixelHysteresis.Position(123, -45), pixels.update(123.1, -45.1, 1));
      assertEquals(new ScreenPixelHysteresis.Position(-250, 310), pixels.update(-250, 310, 1));
   }

   @Test void releaseDistanceUsesFramebufferPixelsAtEveryGuiScale() {
      for (int scale : new int[] {1, 2, 3, 4}) {
         var pixels = new ScreenPixelHysteresis();
         pixels.update(0, 0, scale);
         assertEquals(0, pixels.update(0.7 / scale, 0, scale).x());
         assertEquals(1.0 / scale, pixels.update(0.8 / scale, 0, scale).x());
      }
   }

   @Test void errorStaysWithinThreeQuartersOfAFramebufferPixel() {
      var pixels = new ScreenPixelHysteresis();
      for (int index = 0; index < 1000; index++) {
         double x = Math.sin(index * 0.07) * 100;
         double y = Math.cos(index * 0.13) * 100;
         var actual = pixels.update(x, y, 3);
         assertTrue(Math.abs(actual.x() - x) * 3 <= 0.750001);
         assertTrue(Math.abs(actual.y() - y) * 3 <= 0.750001);
      }
   }

   @Test void changingGuiScaleResetsThePixelGrid() {
      var pixels = new ScreenPixelHysteresis();
      assertEquals(10, pixels.update(10.2, 0, 1).x());
      assertEquals(10.25, pixels.update(10.2, 0, 4).x());
   }
}
