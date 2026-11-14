package io.github.fastformer.client.input.mouse;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class PointerButtonCaptureTest {
   @Test void extraPhysicalMouseButtonOwnsItsCaptureWithoutBecomingALogicalLeftClick() {
      var capture = new PointerButtonCapture(); capture.capture(5);
      assertTrue(capture.owns(5)); assertFalse(capture.owns(0)); assertEquals(5, capture.buttonId());
      capture.clear(); assertEquals(-1, capture.buttonId());
   }
   @Test void absenceIsDistinctFromALeftPressAndClearReleasesOwnership() {
      var capture = new PointerButtonCapture();
      assertFalse(capture.active());
      assertFalse(capture.owns(0));
      capture.capture(0);
      assertTrue(capture.active());
      assertTrue(capture.owns(0));
      assertFalse(capture.owns(1));
      capture.clear();
      assertFalse(capture.active());
      assertEquals(-1, capture.buttonId());
   }

   @Test void invalidButtonDoesNotDestroyExistingOwnership() {
      var capture = new PointerButtonCapture();
      capture.capture(1);
      assertThrows(IllegalArgumentException.class, () -> capture.capture(-1));
      assertTrue(capture.owns(1));
   }
}
