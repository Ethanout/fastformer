package io.github.fastformer.common.api;

import java.util.Objects;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;

/**
 * A new undo entry was accepted. QUEUED describes in-memory admission with an
 * asynchronous save; only DURABLE guarantees that its batch and index were saved.
 * A queued entry does not produce another event when its save finishes or retries.
 */
public final class HistoryRecorded extends FastFormerEvent {
   public enum Persistence { QUEUED, DURABLE }
   private final UUID owner;
   private final UUID operationId;
   private final ResourceLocation dimension;
   private final ResourceLocation entryType;
   private final int changes;
   private final Persistence persistence;

   public HistoryRecorded(UUID owner, UUID operationId, ResourceLocation dimension,
      ResourceLocation entryType, int changes, Persistence persistence) {
      this.owner = Objects.requireNonNull(owner, "owner");
      this.operationId = Objects.requireNonNull(operationId, "operationId");
      this.dimension = Objects.requireNonNull(dimension, "dimension");
      this.entryType = Objects.requireNonNull(entryType, "entryType");
      if (changes < 0) throw new IllegalArgumentException("History size must not be negative");
      this.changes = changes;
      this.persistence = Objects.requireNonNull(persistence, "persistence");
   }

   public UUID owner() { return owner; }
   public UUID operationId() { return operationId; }
   public ResourceLocation dimension() { return dimension; }
   public ResourceLocation entryType() { return entryType; }
   public int changes() { return changes; }
   public Persistence persistence() { return persistence; }
}
