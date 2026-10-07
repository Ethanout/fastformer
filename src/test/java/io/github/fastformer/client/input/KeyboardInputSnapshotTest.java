package io.github.fastformer.client.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class KeyboardInputSnapshotTest {
   @Test
   void modifierReleaseIgnoresStalePhysicalStateOfReleasedKey() {
      var alt = KeyboardInputSnapshot.capture(342, 7, 0, 4, 100L, true, false, false, false);
      var control = KeyboardInputSnapshot.capture(345, 8, 0, 2, 200L, false, false, false, true);
      assertFalse(alt.altDown());
      assertFalse(control.controlDown());
      assertTrue(alt.altKey());
      assertTrue(control.controlKey());
   }

   @Test
   void releasingOneModifierKeepsTheOtherSideHeld() {
      var alt = KeyboardInputSnapshot.capture(346, 0, 0, 0, 0L, true, true, false, false);
      var control = KeyboardInputSnapshot.capture(341, 0, 0, 0, 0L, false, false, true, true);
      assertTrue(alt.altDown());
      assertTrue(control.controlDown());
   }

   @Test
   void pressAndRepeatUseEventEvenBeforePhysicalStateChanges() {
      for (int action : new int[] {1, 2}) {
         assertTrue(KeyboardInputSnapshot.capture(342, 0, action, 0, 0L,
            false, false, false, false).altDown());
         assertTrue(KeyboardInputSnapshot.capture(345, 0, action, 0, 0L,
            false, false, false, false).controlDown());
      }
   }

   @Test
   void ordinaryKeyRetainsCaptureDataAcrossLaterModifierRelease() {
      var confirm = KeyboardInputSnapshot.capture(257, 28, 1, 6, 123L, false, true, true, false);
      var release = KeyboardInputSnapshot.capture(346, 0, 0, 0, 456L, false, true, false, false);
      assertFalse(release.altDown());
      assertTrue(confirm.altDown());
      assertTrue(confirm.controlDown());
      assertEquals(257, confirm.key());
      assertEquals(28, confirm.scanCode());
      assertEquals(1, confirm.action());
      assertEquals(6, confirm.modifiers());
      assertEquals(123L, confirm.occurredAtNanos());
   }

   @Test
   void shiftQKeepsItsDropMeaningAfterShiftIsReleased() {
      var press = KeyboardInputSnapshot.capture(81, 0, 1, 1, 100L, false, false, false, false);
      var later = KeyboardInputSnapshot.capture(81, 0, 1, 0, 200L, false, false, false, false);
      assertTrue(press.shiftDown());
      assertFalse(later.shiftDown());
   }

   @Test
   void controlQKeepsItsDropMeaningAfterControlIsReleased() {
      var press = KeyboardInputSnapshot.capture(81, 0, 1, 2, 100L, false, false, true, false);
      var release = KeyboardInputSnapshot.capture(341, 0, 0, 0, 200L, false, false, true, false);
      assertFalse(release.controlDown());
      assertTrue(press.controlDown());
   }
}
