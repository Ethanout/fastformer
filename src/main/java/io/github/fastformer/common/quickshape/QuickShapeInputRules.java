package io.github.fastformer.common.quickshape;

import io.github.fastformer.common.quickshape.mode.FaceMode;

/** Stage rules shared by input capture and authoritative dispatch. */
public final class QuickShapeInputRules {
   private QuickShapeInputRules() {
   }

   public static boolean ignoresMiddleClick(FaceMode faceMode, int confirmedPoints, boolean polygonClosed) {
      return faceMode == FaceMode.POLYGON && confirmedPoints >= 2 && !polygonClosed;
   }
}
