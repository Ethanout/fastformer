package io.github.fastformer.fastplace;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rejects a second quick replace of one player in one server tick.
 *
 * <p>The first request of a player has no recorded tick yet, so {@link Map#put}
 * returns {@code null}. This class keeps that missing value separate from a real
 * tick number. Comparing the raw result of {@code put} against a primitive
 * {@code long} unboxes {@code null} and throws a NullPointerException before any
 * raycast or world write.</p>
 */
final class QuickReplaceDedupe {
   private static final Map<UUID, Long> LAST_TICK = new ConcurrentHashMap<>();

   private QuickReplaceDedupe() {
   }

   /**
    * Records the tick of this request and returns {@code true} only for the
    * first accepted request of this player in this tick.
    */
   static boolean accept(UUID owner, long gameTick) {
      if (owner == null) {
         return false;
      }
      Long previousTick = LAST_TICK.put(owner, Long.valueOf(gameTick));
      return previousTick == null || previousTick.longValue() != gameTick;
   }

   /** Drops the tick memory of a player whose connection ended. */
   static void forget(UUID owner) {
      if (owner != null) {
         LAST_TICK.remove(owner);
      }
   }

   static void clearAll() {
      LAST_TICK.clear();
   }
}
