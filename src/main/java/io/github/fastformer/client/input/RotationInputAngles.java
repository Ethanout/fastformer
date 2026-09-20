package io.github.fastformer.client.input;

import io.github.fastformer.client.operation.transform.PixelPerfectAngles;

final class RotationInputAngles {
   private RotationInputAngles() { }

   static double resolve(int rawSteps, boolean control, ModifierGestureState modifier) {
      double radians = rawSteps * Math.PI * 2.0 / 1024.0;
      if (control) return PixelPerfectAngles.free(radians);
      if (modifier.held()) {
         modifier.consume();
         return PixelPerfectAngles.snap(radians);
      }
      return PixelPerfectAngles.defaultSnap(radians);
   }
}
