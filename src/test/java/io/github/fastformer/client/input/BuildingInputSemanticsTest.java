package io.github.fastformer.client.input;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.fastformer.fastplace.quickshape.RaycastPlacement;
import org.junit.jupiter.api.Test;

class BuildingInputSemanticsTest {
   @Test
   void normalInputPlacesOnTheHitSurface() {
      assertEquals(RaycastPlacement.SURFACE, BuildingInputSemantics.raycastPlacement(false));
   }

   @Test
   void altInputPlacesInsideTheHitBlock() {
      assertEquals(RaycastPlacement.EMBEDDED, BuildingInputSemantics.raycastPlacement(true));
   }
}
