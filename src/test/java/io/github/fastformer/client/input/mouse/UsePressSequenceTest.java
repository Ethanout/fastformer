package io.github.fastformer.client.input.mouse;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class UsePressSequenceTest {
   @Test void aReleasedClickCannotRepeatWhenVanillaMissesTheRelease() {
      var press = new UsePressSequence();
      press.press();
      assertTrue(press.accept());
      press.release();
      for (int tick = 1; tick <= 8; tick++) assertFalse(press.accept());
   }

   @Test void fastSeparateClicksAreNeverDebounced() {
      var press = new UsePressSequence();
      for (int click = 0; click < 5; click++) {
         press.press();
         press.release();
         assertTrue(press.accept());
         assertFalse(press.accept());
      }
   }

   @Test void heldInputKeepsVanillaRepeatTiming() {
      var press = new UsePressSequence();
      press.press();
      for (int tick = 0; tick < 8; tick++) assertTrue(press.accept());
      press.release();
      assertFalse(press.accept());
   }

   @Test void twoPhysicalClicksQueuedBeforeOneTickBothPlace() {
      var press = new UsePressSequence();
      press.press();
      press.release();
      press.press();
      press.release();
      assertTrue(press.accept());
      assertTrue(press.accept());
      assertFalse(press.accept());
   }
}
