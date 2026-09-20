package io.github.fastformer.fastplace;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Regression coverage for the first quick replace of a player.
 *
 * <p>The first request has no recorded tick, so the previous implementation
 * compared the {@code null} result of {@link java.util.Map#put} against a
 * primitive {@code long}, unboxed it and threw a NullPointerException before the
 * raycast and the world write. These tests fail with that exception.</p>
 */
class QuickReplaceDedupeTest {
   @Test
   void theFirstRequestOfAPlayerIsAccepted() {
      UUID owner = UUID.randomUUID();
      try {
         assertTrue(QuickReplaceDedupe.accept(owner, 41L));
      } finally {
         QuickReplaceDedupe.forget(owner);
      }
   }

   @Test
   void aSecondRequestInTheSameTickIsRejected() {
      UUID owner = UUID.randomUUID();
      try {
         assertTrue(QuickReplaceDedupe.accept(owner, 41L));
         assertFalse(QuickReplaceDedupe.accept(owner, 41L));
      } finally {
         QuickReplaceDedupe.forget(owner);
      }
   }

   @Test
   void theNextTickAcceptsThePlayerAgain() {
      UUID owner = UUID.randomUUID();
      try {
         assertTrue(QuickReplaceDedupe.accept(owner, 41L));
         assertFalse(QuickReplaceDedupe.accept(owner, 41L));
         assertTrue(QuickReplaceDedupe.accept(owner, 42L));
         assertFalse(QuickReplaceDedupe.accept(owner, 42L));
      } finally {
         QuickReplaceDedupe.forget(owner);
      }
   }

   @Test
   void playersDoNotShareTheTickMemory() {
      UUID first = UUID.randomUUID();
      UUID second = UUID.randomUUID();
      try {
         assertTrue(QuickReplaceDedupe.accept(first, 41L));
         assertTrue(QuickReplaceDedupe.accept(second, 41L));
         assertFalse(QuickReplaceDedupe.accept(first, 41L));
      } finally {
         QuickReplaceDedupe.forget(first);
         QuickReplaceDedupe.forget(second);
      }
   }

   @Test
   void forgettingAPlayerAllowsTheFirstRequestAgain() {
      UUID owner = UUID.randomUUID();
      assertTrue(QuickReplaceDedupe.accept(owner, 41L));
      assertFalse(QuickReplaceDedupe.accept(owner, 41L));

      QuickReplaceDedupe.forget(owner);

      assertTrue(QuickReplaceDedupe.accept(owner, 41L));
      QuickReplaceDedupe.forget(owner);
   }

   @Test
   void aMissingOwnerIsRejectedWithoutThrowing() {
      assertFalse(QuickReplaceDedupe.accept(null, 41L));
   }
}
