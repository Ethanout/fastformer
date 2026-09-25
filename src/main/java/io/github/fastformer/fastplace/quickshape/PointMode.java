package io.github.fastformer.fastplace.quickshape;


public enum PointMode implements QuickShapeMode {
   RAYCAST("fastformer.mode.point.raycast");

   private final String translationKey;

   PointMode(String translationKey) {
      this.translationKey = translationKey;
   }

   @Override
   public QuickShapeStage stage() {
      return QuickShapeStage.POINT;
   }

   @Override
   public String translationKey() {
      return this.translationKey;
   }
}
