package io.github.fastformer.client.input;

import io.github.fastformer.fastplace.quickshape.RaycastPlacement;

/** Pure input policy for starting a normal building session. */
public final class BuildingInputSemantics {
   private BuildingInputSemantics() {
   }

   public static RaycastPlacement raycastPlacement(boolean altHeld) {
      return altHeld ? RaycastPlacement.EMBEDDED : RaycastPlacement.SURFACE;
   }
}
