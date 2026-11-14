package io.github.fastformer.client.input;

import io.github.fastformer.client.input.state.EntryPressGuard;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.fastformer.client.input.mouse.MouseButtonInputSemantics.*;
import org.junit.jupiter.api.Test;

/** Protection follows the rebound physical button, including its release after expiry. */
class EntryPressGuardTest {
   @Test void aConfiguredExtraButtonOwnsItsPairedReleaseWithoutExtendingTheWindow() {
      var guard = new EntryPressGuard();
      guard.arm(0, 4, 375);
      assertFalse(guard.capture(PRESS, RIGHT_BUTTON, 100_000_000));
      assertTrue(guard.capture(PRESS, 4, 375_000_000));
      assertTrue(guard.capture(RELEASE, 4, 500_000_000));
      assertFalse(guard.capture(PRESS, 4, 500_000_001));
   }

   @Test void ordinaryPressesHaveNoProtectionUntilArmed() {
      var guard = new EntryPressGuard();
      assertFalse(guard.capture(PRESS, RIGHT_BUTTON, 0));
      assertFalse(guard.capture(PRESS, RIGHT_BUTTON, 100_000_000));
   }

   @Test void sourceReleasePassesThroughAndSecondPressIsIgnoredAtTheBoundary() {
      var guard = new EntryPressGuard();
      guard.arm(1_000_000_000);
      assertFalse(guard.capture(RELEASE, RIGHT_BUTTON, 1_010_000_000));
      assertTrue(guard.capture(PRESS, RIGHT_BUTTON, 1_200_000_000));
      assertTrue(guard.capture(RELEASE, RIGHT_BUTTON, 1_210_000_000));
      assertFalse(guard.capture(PRESS, RIGHT_BUTTON, 1_220_000_000));
   }

   @Test void swallowedPressStillOwnsItsReleaseAfterTheWindowExpires() {
      var guard = new EntryPressGuard();
      guard.arm(0);
      assertTrue(guard.capture(PRESS, RIGHT_BUTTON, 100_000_000));
      assertTrue(guard.capture(RELEASE, RIGHT_BUTTON, 500_000_000));
      assertFalse(guard.capture(RELEASE, RIGHT_BUTTON, 500_000_001));
   }

   @Test void repeatedPressesDoNotExtendTheInitialProtectionWindow() {
      var guard = new EntryPressGuard();
      guard.arm(0);
      assertTrue(guard.capture(PRESS, RIGHT_BUTTON, 100_000_000));
      assertTrue(guard.capture(RELEASE, RIGHT_BUTTON, 110_000_000));
      assertTrue(guard.capture(PRESS, RIGHT_BUTTON, 200_000_000));
      assertTrue(guard.capture(RELEASE, RIGHT_BUTTON, 210_000_000));
      assertFalse(guard.capture(PRESS, RIGHT_BUTTON, 200_000_001));
   }

   @Test void otherButtonsRemainUsableDuringProtection() {
      var guard = new EntryPressGuard();
      guard.arm(0);
      assertFalse(guard.capture(PRESS, LEFT_BUTTON, 100_000_000));
      assertFalse(guard.capture(PRESS, MIDDLE_BUTTON, 100_000_000));
      assertFalse(guard.capture(RELEASE, LEFT_BUTTON, 110_000_000));
      assertTrue(guard.capture(PRESS, RIGHT_BUTTON, 120_000_000));
   }

   @Test void cancellationAndWorldResetRemoveProtection() {
      var session = new ClientInputSession();
      session.entryPressGuard.arm(0);
      session.cuboidPointPressGuard.arm(0);
      session.discardPhysicalEvents();
      assertFalse(session.entryPressGuard.capture(PRESS, RIGHT_BUTTON, 10));
      assertFalse(session.cuboidPointPressGuard.capture(PRESS, RIGHT_BUTTON, 10));
      session.entryPressGuard.arm(20);
      session.cuboidPointPressGuard.arm(20);
      session.reset();
      assertFalse(session.entryPressGuard.capture(PRESS, RIGHT_BUTTON, 30));
      assertFalse(session.cuboidPointPressGuard.capture(PRESS, RIGHT_BUTTON, 30));
   }
}
