package io.github.fastformer.server.replace;

/** One player's last crosshair request; absence is distinct from every tick value. */
final class QuickReplaceDedupe {
   private Long lastTick;

   boolean accept(long gameTick) {
      Long previous = lastTick;
      lastTick = gameTick;
      return previous == null || previous != gameTick;
   }

   void clear() { lastTick = null; }
}
