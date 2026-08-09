package io.github.fastformer.fastplace;

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
