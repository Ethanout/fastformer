package io.github.fastformer.fastplace;

import io.github.fastformer.fastplace.world.*;

public enum PolygonVolumeShape implements TranslatableText {
   EXTRUDE("fastformer.mode.polygon_volume.extrude"),
   APEX("fastformer.mode.polygon_volume.apex");

   private final String translationKey;

   PolygonVolumeShape(String translationKey) {
      this.translationKey = translationKey;
   }

   @Override
   public String translationKey() {
      return this.translationKey;
   }

   public PolygonVolumeShape next() {
      PolygonVolumeShape[] values = values();
      return values[(this.ordinal() + 1) % values.length];
   }
}
