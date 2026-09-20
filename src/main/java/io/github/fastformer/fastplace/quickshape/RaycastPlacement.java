package io.github.fastformer.fastplace.quickshape;

import io.github.fastformer.fastplace.TranslatableText;


public enum RaycastPlacement implements TranslatableText {
   EMBEDDED("fastformer.mode.raycast.embedded"),
   SURFACE("fastformer.mode.raycast.surface");

   private final String translationKey;

   RaycastPlacement(String translationKey) {
      this.translationKey = translationKey;
   }

   @Override
   public String translationKey() {
      return this.translationKey;
   }
}
