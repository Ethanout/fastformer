package io.github.fastformer.client.quickshape.input;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.fastformer.fastplace.quickshape.RaycastPlacement;
import org.junit.jupiter.api.Test;

class BuildingInputSemanticsTest {
   @Test
   void firstMiddleClickEmbedsWithoutChangingOtherButtons() {
      assertEquals(RaycastPlacement.EMBEDDED, BuildingInputSemantics.initialPlacement(false, true));
      assertEquals(RaycastPlacement.EMBEDDED, BuildingInputSemantics.initialPlacement(true, true));
      assertEquals(RaycastPlacement.EMBEDDED, BuildingInputSemantics.initialPlacement(true, false));
      assertEquals(RaycastPlacement.SURFACE, BuildingInputSemantics.initialPlacement(false, false));
   }

   @Test
   void normalInputPlacesOnTheHitSurface() {
      assertEquals(RaycastPlacement.SURFACE, BuildingInputSemantics.raycastPlacement(false));
   }

   @Test
   void altInputPlacesInsideTheHitBlock() {
      assertEquals(RaycastPlacement.EMBEDDED, BuildingInputSemantics.raycastPlacement(true));
   }
}
