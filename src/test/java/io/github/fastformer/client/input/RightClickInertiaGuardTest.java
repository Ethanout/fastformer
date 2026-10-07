package io.github.fastformer.client.input;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.fastformer.client.input.mouse.MouseButtonInputSemantics.*;
import org.junit.jupiter.api.Test;

class RightClickInertiaGuardTest {
   @Test void ordinaryPressesHaveNoProtectionUntilArmed() {
      var guard = new RightClickInertiaGuard();
      assertFalse(guard.capture(PRESS, RIGHT_BUTTON, 0));
      assertFalse(guard.capture(PRESS, RIGHT_BUTTON, 100_000_000));
   }

   @Test void sourceReleasePassesThroughAndSecondPressIsIgnoredAtTheBoundary() {
      var guard = new RightClickInertiaGuard();
      guard.arm(1_000_000_000);
      assertFalse(guard.capture(RELEASE, RIGHT_BUTTON, 1_010_000_000));
      assertTrue(guard.capture(PRESS, RIGHT_BUTTON, 1_200_000_000));
      assertTrue(guard.capture(RELEASE, RIGHT_BUTTON, 1_210_000_000));
      assertFalse(guard.capture(PRESS, RIGHT_BUTTON, 1_220_000_000));
   }

   @Test void swallowedPressStillOwnsItsReleaseAfterTheWindowExpires() {
      var guard = new RightClickInertiaGuard();
      guard.arm(0);
      assertTrue(guard.capture(PRESS, RIGHT_BUTTON, 100_000_000));
      assertTrue(guard.capture(RELEASE, RIGHT_BUTTON, 500_000_000));
      assertFalse(guard.capture(RELEASE, RIGHT_BUTTON, 500_000_001));
   }

   @Test void repeatedPressesDoNotExtendTheInitialProtectionWindow() {
      var guard = new RightClickInertiaGuard();
      guard.arm(0);
      assertTrue(guard.capture(PRESS, RIGHT_BUTTON, 100_000_000));
      assertTrue(guard.capture(RELEASE, RIGHT_BUTTON, 110_000_000));
      assertTrue(guard.capture(PRESS, RIGHT_BUTTON, 200_000_000));
      assertTrue(guard.capture(RELEASE, RIGHT_BUTTON, 210_000_000));
      assertFalse(guard.capture(PRESS, RIGHT_BUTTON, 200_000_001));
   }

   @Test void otherButtonsRemainUsableDuringProtection() {
      var guard = new RightClickInertiaGuard();
      guard.arm(0);
      assertFalse(guard.capture(PRESS, LEFT_BUTTON, 100_000_000));
      assertFalse(guard.capture(PRESS, MIDDLE_BUTTON, 100_000_000));
      assertFalse(guard.capture(RELEASE, LEFT_BUTTON, 110_000_000));
      assertTrue(guard.capture(PRESS, RIGHT_BUTTON, 120_000_000));
   }

   @Test void cancellationAndWorldResetRemoveProtection() {
      var session = new ClientInputSession();
      session.distantEntryPressGuard.arm(0);
      session.cuboidPointPressGuard.arm(0);
      session.discardPhysicalEvents();
      assertFalse(session.distantEntryPressGuard.capture(PRESS, RIGHT_BUTTON, 10));
      assertFalse(session.cuboidPointPressGuard.capture(PRESS, RIGHT_BUTTON, 10));
      session.distantEntryPressGuard.arm(20);
      session.cuboidPointPressGuard.arm(20);
      session.reset();
      assertFalse(session.distantEntryPressGuard.capture(PRESS, RIGHT_BUTTON, 30));
      assertFalse(session.cuboidPointPressGuard.capture(PRESS, RIGHT_BUTTON, 30));
   }
}
