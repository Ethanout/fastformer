package io.github.fastformer.fastplace.quickshape;

import io.github.fastformer.fastplace.TranslatableText;


public enum QuickShapeStage implements TranslatableText {
   POINT("fastformer.stage.point"),
   LINE("fastformer.stage.line"),
   FACE("fastformer.stage.face"),
   VOLUME("fastformer.stage.volume");

   private final String translationKey;

   QuickShapeStage(String translationKey) {
      this.translationKey = translationKey;
   }

   @Override
   public String translationKey() {
      return this.translationKey;
   }

   public static QuickShapeStage fromPointCount(int confirmedPoints) {
      return switch (confirmedPoints) {
         case 0 -> POINT;
         case 1 -> LINE;
         case 2 -> FACE;
         default -> VOLUME;
      };
   }

   public static QuickShapeStage resolve(int confirmedPoints, FaceMode faceMode, boolean polygonClosed) {
      if (faceMode == FaceMode.POLYGON && confirmedPoints >= 2 && !polygonClosed) return FACE;
      return fromPointCount(confirmedPoints);
   }
}
