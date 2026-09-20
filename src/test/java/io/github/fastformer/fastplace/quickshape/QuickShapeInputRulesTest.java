package io.github.fastformer.fastplace.quickshape;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuickShapeInputRulesTest {
   @Test
   void openPolygonFaceConsumesMiddleWithoutConfirmation() {
      assertTrue(QuickShapeInputRules.ignoresMiddleClick(FaceMode.POLYGON, 2, false));
      assertTrue(QuickShapeInputRules.ignoresMiddleClick(FaceMode.POLYGON, 3, false));
      assertTrue(QuickShapeInputRules.ignoresMiddleClick(FaceMode.POLYGON, 10, false));
   }

   @Test
   void lineVolumeAndOtherFaceModesKeepTheirMiddleBehavior() {
      assertFalse(QuickShapeInputRules.ignoresMiddleClick(FaceMode.POLYGON, 1, false));
      assertFalse(QuickShapeInputRules.ignoresMiddleClick(FaceMode.POLYGON, 3, true));
      for (FaceMode mode : FaceMode.values()) {
         if (mode != FaceMode.POLYGON) {
            assertFalse(QuickShapeInputRules.ignoresMiddleClick(mode, 3, false));
         }
      }
   }
}
