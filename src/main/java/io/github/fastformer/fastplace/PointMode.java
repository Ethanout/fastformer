package io.github.fastformer.fastplace;

import io.github.fastformer.fastplace.world.*;

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
