package io.github.fastformer.fastplace.quickshape;

import io.github.fastformer.fastplace.TranslatableText;


public enum FastPlaceStage implements TranslatableText {
   POINT("fastformer.stage.point"),
   LINE("fastformer.stage.line"),
   FACE("fastformer.stage.face"),
   VOLUME("fastformer.stage.volume");

   private final String translationKey;

   FastPlaceStage(String translationKey) {
      this.translationKey = translationKey;
   }

   @Override
   public String translationKey() {
      return this.translationKey;
   }
}
