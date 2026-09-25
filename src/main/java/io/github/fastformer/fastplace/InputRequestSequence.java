package io.github.fastformer.fastplace;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Tracks the last accepted request for each player in one input channel. */
final class InputRequestSequence {
   private final Map<UUID, Long> lastAccepted = new HashMap<>();

   boolean accept(UUID owner, long requestId) {
      if (owner == null || requestId <= 0L) return false;
      Long previous = this.lastAccepted.get(owner);
      if (previous != null && requestId <= previous) return false;
      this.lastAccepted.put(owner, requestId);
      return true;
   }

   void clear(UUID owner) {
      this.lastAccepted.remove(owner);
   }

   void clear() {
      this.lastAccepted.clear();
   }
}
