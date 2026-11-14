package io.github.fastformer.client.interaction.selectiongesture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class SelectionRotationAnglesTest {
   @Test void selectionDragsSnapToQuartersUnlessControlIsHeld() {
      double preview = 73 * Math.PI * 2 / 1024;
      assertEquals(0, SelectionRotationAngles.preview(73, false), 1.0E-12);
      assertEquals(preview, SelectionRotationAngles.preview(73, true), 1.0E-12);
      assertEquals(Math.PI / 2, SelectionRotationAngles.preview(230, false), 1.0E-12);
      assertEquals(-Math.PI / 2, SelectionRotationAngles.preview(-230, false), 1.0E-12);
      assertEquals(Math.PI / 2, SelectionRotationAngles.finish(1.3, false), 1.0E-12);
      assertEquals(1.3, SelectionRotationAngles.finish(1.3, true), 1.0E-12);
   }
}
