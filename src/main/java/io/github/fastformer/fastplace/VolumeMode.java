package io.github.fastformer.fastplace;

import io.github.fastformer.fastplace.world.*;

public enum VolumeMode implements FastPlaceMode {
   PERPENDICULAR_TO_FACE("fastformer.mode.volume.perpendicular_to_face"),
   FREE("fastformer.mode.volume.free");

   private final String translationKey;

   VolumeMode(String translationKey) {
      this.translationKey = translationKey;
   }

   @Override
   public FastPlaceStage stage() {
      return FastPlaceStage.VOLUME;
   }

   @Override
   public String translationKey() {
      return this.translationKey;
   }
}
