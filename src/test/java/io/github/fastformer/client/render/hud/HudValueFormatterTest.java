package io.github.fastformer.client.render.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class HudValueFormatterTest {
   @Test
   void coordinatesUseOneDecimalOnlyWhenNeeded() {
      assertEquals("4", HudValueFormatter.coordinate(4.0));
      assertEquals("4.3", HudValueFormatter.coordinate(4.25));
      assertEquals("0.0", HudValueFormatter.coordinate(Double.NaN));
   }

   @Test
   void scalesUseTwoDecimalsOnlyWhenNeeded() {
      assertEquals("2", HudValueFormatter.scale(2.0));
      assertEquals("1.25", HudValueFormatter.scale(1.25));
      assertEquals("0.00", HudValueFormatter.scale(Double.POSITIVE_INFINITY));
   }
}
