package io.github.fastformer.fastplace.quickshape;


public enum VolumeMode implements QuickShapeMode {
   PERPENDICULAR_TO_FACE("fastformer.mode.volume.perpendicular_to_face"),
   FREE("fastformer.mode.volume.free");

   private final String translationKey;

   VolumeMode(String translationKey) {
      this.translationKey = translationKey;
   }

   @Override
   public QuickShapeStage stage() {
      return QuickShapeStage.VOLUME;
   }

   @Override
   public String translationKey() {
      return this.translationKey;
   }
}
