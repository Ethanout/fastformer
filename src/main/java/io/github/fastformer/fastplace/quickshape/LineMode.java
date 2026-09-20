package io.github.fastformer.fastplace.quickshape;


public enum LineMode implements FastPlaceMode {
   AXIS("fastformer.mode.line.axis"),
   FREE_SCROLL("fastformer.mode.line.free_scroll"),
   RAYCAST("fastformer.mode.line.raycast");

   private final String translationKey;

   LineMode(String translationKey) {
      this.translationKey = translationKey;
   }

   @Override
   public FastPlaceStage stage() {
      return FastPlaceStage.LINE;
   }

   @Override
   public String translationKey() {
      return this.translationKey;
   }
}
