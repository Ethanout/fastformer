package io.github.fastformer.server.input;

/** Tracks strictly increasing requests for one player and one input channel. */
final class InputRequestSequence {
   private long lastAccepted;

   boolean accept(long requestId) {
      if (requestId <= lastAccepted) return false;
      lastAccepted = requestId;
      return true;
   }

   void clear() {
      lastAccepted = 0;
   }
}
