package io.github.fastformer.common.api;

import java.util.Objects;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;

/** Published once after a non-empty writer's history batch and index are durable. */
public final class ShapeCommitted extends FastFormerEvent {
   public enum Kind { PLACEMENT, OPERATION }
   private final UUID owner;
   private final UUID operationId;
   private final ResourceLocation dimension;
   private final int changedBlocks;
   private final Kind kind;

   public ShapeCommitted(UUID owner, UUID operationId, ResourceLocation dimension, int changedBlocks, Kind kind) {
      this.owner = Objects.requireNonNull(owner, "owner");
      this.operationId = Objects.requireNonNull(operationId, "operationId");
      this.dimension = Objects.requireNonNull(dimension, "dimension");
      if (changedBlocks <= 0) throw new IllegalArgumentException("A committed shape must change blocks");
      this.changedBlocks = changedBlocks;
      this.kind = Objects.requireNonNull(kind, "kind");
   }

   public UUID owner() { return owner; }
   public UUID operationId() { return operationId; }
   public ResourceLocation dimension() { return dimension; }
   public int changedBlocks() { return changedBlocks; }
   public Kind kind() { return kind; }
}
