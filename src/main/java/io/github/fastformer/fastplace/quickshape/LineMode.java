package io.github.fastformer.fastplace.quickshape;


public enum LineMode implements QuickShapeMode {
   AXIS("fastformer.mode.line.axis"),
   FREE_SCROLL("fastformer.mode.line.free_scroll"),
   RAYCAST("fastformer.mode.line.raycast");

   private final String translationKey;

   LineMode(String translationKey) {
      this.translationKey = translationKey;
   }

   @Override
   public QuickShapeStage stage() {
      return QuickShapeStage.LINE;
   }

   @Override
   public String translationKey() {
      return this.translationKey;
   }
}
