package io.github.fastformer.fastplace.quickshape;


public enum PointMode implements FastPlaceMode {
   RAYCAST("fastformer.mode.point.raycast");

   private final String translationKey;

   PointMode(String translationKey) {
      this.translationKey = translationKey;
   }

   @Override
   public FastPlaceStage stage() {
      return FastPlaceStage.POINT;
   }

   @Override
   public String translationKey() {
      return this.translationKey;
   }
}
