package io.github.fastformer.server.session.slot;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/** Server-thread repository of player slots; ending an active session preserves parked work. */
public final class PlayerSessions<S> {
   private final Map<UUID, PlayerSessionSlot<S>> slots = new HashMap<>();

   public S active(UUID owner) {
      PlayerSessionSlot<S> slot = slots.get(owner);
      return slot == null ? null : slot.active();
   }

   public boolean hasActive(UUID owner) { return active(owner) != null; }

   public void activate(UUID owner, S session) {
      slots.computeIfAbsent(owner, ignored -> new PlayerSessionSlot<>()).activate(session);
   }

   public S getOrCreate(UUID owner, Supplier<S> factory) {
      S session = active(owner);
      if (session == null) { session = factory.get(); activate(owner, session); }
      return session;
   }

   public S end(UUID owner) {
      PlayerSessionSlot<S> slot = slots.get(owner);
      if (slot == null) return null;
      S ended = slot.end();
      prune(owner, slot);
      return ended;
   }

   public void park(UUID owner, ResourceKey<Level> dimension) {
      PlayerSessionSlot<S> slot = slots.get(owner);
      if (slot != null) slot.park(dimension);
   }

   public void restore(UUID owner, ResourceKey<Level> dimension) {
      PlayerSessionSlot<S> slot = slots.get(owner);
      if (slot != null) { slot.restore(dimension); prune(owner, slot); }
   }

   public S parked(UUID owner, ResourceKey<Level> dimension) {
      PlayerSessionSlot<S> slot = slots.get(owner);
      return slot == null ? null : slot.parked(dimension);
   }

   public void discard(UUID owner) { slots.remove(owner); }
   public void clear() { slots.clear(); }
   int size() { return slots.size(); }

   private void prune(UUID owner, PlayerSessionSlot<S> slot) {
      if (slot.empty()) slots.remove(owner, slot);
   }
}
