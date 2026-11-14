package io.github.fastformer.common.api;

import io.github.fastformer.common.mode.ModeId;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;

/**
 * One editor repository changed its active mode. Empty means that this repository
 * has no active session; parked drafts and held-item classification are excluded.
 */
public final class ModeChanged extends FastFormerEvent {
   public enum Cause { OPEN, END, PARK, RESTORE, DISCARD }
   private final UUID owner;
   private final ResourceLocation repository;
   private final Optional<ModeId> previous;
   private final Optional<ModeId> current;
   private final Cause cause;

   public ModeChanged(UUID owner, ResourceLocation repository, Optional<ModeId> previous,
      Optional<ModeId> current, Cause cause) {
      this.owner = Objects.requireNonNull(owner, "owner");
      this.repository = Objects.requireNonNull(repository, "repository");
      this.previous = Objects.requireNonNull(previous, "previous");
      this.current = Objects.requireNonNull(current, "current");
      if (previous.equals(current)) throw new IllegalArgumentException("Mode did not change");
      this.cause = Objects.requireNonNull(cause, "cause");
   }

   public UUID owner() { return owner; }
   public ResourceLocation repository() { return repository; }
   public Optional<ModeId> previous() { return previous; }
   public Optional<ModeId> current() { return current; }
   public Cause cause() { return cause; }
}
