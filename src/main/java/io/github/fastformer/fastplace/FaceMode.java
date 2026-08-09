package io.github.fastformer.fastplace;

public enum FaceMode implements FastPlaceMode {
   COORDINATE_PLANE("fastformer.mode.face.coordinate_plane"),
   PARALLELOGRAM_BASE_PLANE("fastformer.mode.face.parallelogram_base_plane"),
   POLYGON("fastformer.mode.face.polygon");

   private final String translationKey;

   FaceMode(String translationKey) {
      this.translationKey = translationKey;
   }

   @Override
   public FastPlaceStage stage() {
      return FastPlaceStage.FACE;
   }

   @Override
   public String translationKey() {
      return this.translationKey;
   }
}
