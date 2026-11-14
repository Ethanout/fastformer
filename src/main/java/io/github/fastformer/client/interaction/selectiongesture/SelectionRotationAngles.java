package io.github.fastformer.client.interaction.selectiongesture;

import io.github.fastformer.common.math.PixelPerfectAngles;

final class SelectionRotationAngles {
   private SelectionRotationAngles() { }
   static double preview(int rawSteps, boolean control) {
      return finish(rawSteps * Math.PI * 2.0 / 1024.0, control);
   }
   static double finish(double radians, boolean control) {
      return control ? PixelPerfectAngles.free(radians) : PixelPerfectAngles.defaultSnap(radians);
   }
}
