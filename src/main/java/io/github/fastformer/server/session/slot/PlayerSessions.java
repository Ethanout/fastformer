package io.github.fastformer.server.session.slot;

import io.github.fastformer.common.api.ModeChanged;
import io.github.fastformer.common.mode.ModeId;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

/** Server-thread repository of player slots; ending an active session preserves parked work. */
public final class PlayerSessions<S> {
   private final Map<UUID, PlayerSessionSlot<S>> slots = new HashMap<>();
   private final ResourceLocation repository;
   private final Function<S, ModeId> mode;
   private final Consumer<ModeChanged> events;

   public PlayerSessions() { this(ResourceLocation.parse("fastformer:unobserved"), ignored -> null, ignored -> {}); }

   public PlayerSessions(ResourceLocation repository, Function<S, ModeId> mode, Consumer<ModeChanged> events) {
      this.repository = Objects.requireNonNull(repository, "repository");
      this.mode = Objects.requireNonNull(mode, "mode");
      this.events = Objects.requireNonNull(events, "events");
   }

   public S active(UUID owner) {
      PlayerSessionSlot<S> slot = slots.get(owner);
      return slot == null ? null : slot.active();
   }

   public boolean hasActive(UUID owner) { return active(owner) != null; }

   public void activate(UUID owner, S session) {
      PlayerSessionSlot<S> slot = slots.computeIfAbsent(owner, ignored -> new PlayerSessionSlot<>());
      slot.activate(session);
      changed(owner, slot, ModeChanged.Cause.OPEN);
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
      changed(owner, slot, ModeChanged.Cause.END);
      return ended;
   }

   public void park(UUID owner, ResourceKey<Level> dimension) {
      PlayerSessionSlot<S> slot = slots.get(owner);
      if (slot != null) { slot.park(dimension); changed(owner, slot, ModeChanged.Cause.PARK); }
   }

   public void restore(UUID owner, ResourceKey<Level> dimension) {
      PlayerSessionSlot<S> slot = slots.get(owner);
      if (slot != null) { slot.restore(dimension); prune(owner, slot); changed(owner, slot, ModeChanged.Cause.RESTORE); }
   }

   public S parked(UUID owner, ResourceKey<Level> dimension) {
      PlayerSessionSlot<S> slot = slots.get(owner);
      return slot == null ? null : slot.parked(dimension);
   }

   public void discard(UUID owner) {
      PlayerSessionSlot<S> slot = slots.remove(owner);
      if (slot != null) { slot.clear(); changed(owner, slot, ModeChanged.Cause.DISCARD); }
   }
   public void clear() {
      Map<UUID, PlayerSessionSlot<S>> removed = new HashMap<>(slots);
      slots.clear();
      removed.forEach((owner, slot) -> { slot.clear(); changed(owner, slot, ModeChanged.Cause.DISCARD); });
   }
   int size() { return slots.size(); }

   private void changed(UUID owner, PlayerSessionSlot<S> slot, ModeChanged.Cause cause) {
      ModeId previous = slot.publishedMode;
      ModeId current = slot.active() == null ? null : mode.apply(slot.active());
      slot.publishedMode = current;
      if (!Objects.equals(previous, current)) {
         events.accept(new ModeChanged(owner, repository, Optional.ofNullable(previous), Optional.ofNullable(current), cause));
      }
   }

   private void prune(UUID owner, PlayerSessionSlot<S> slot) {
      if (slot.empty()) slots.remove(owner, slot);
   }
}
