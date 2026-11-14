package io.github.fastformer.server.session.slot;

import io.github.fastformer.common.mode.ModeId;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/** Owns one player's active editing session and dimension-bound parked sessions. */
public final class PlayerSessionSlot<S> {
   private S active;
   ModeId publishedMode;
   private final Map<ResourceKey<Level>, S> parked = new HashMap<>();

   public S active() { return active; }
   public void activate(S session) { active = Objects.requireNonNull(session, "session"); }
   public S end() {
      S ended = active;
      active = null;
      return ended;
   }

   public void park(ResourceKey<Level> dimension) {
      if (dimension != null && active != null) parked.put(dimension, end());
   }

   /** An already active session wins; the parked session remains available for later. */
   public void restore(ResourceKey<Level> dimension) {
      if (dimension != null && active == null) active = parked.remove(dimension);
   }

   public S parked(ResourceKey<Level> dimension) { return parked.get(dimension); }
   public int parkedCount() { return parked.size(); }
   public boolean empty() { return active == null && parked.isEmpty(); }
   public void clear() { active = null; parked.clear(); }
}
