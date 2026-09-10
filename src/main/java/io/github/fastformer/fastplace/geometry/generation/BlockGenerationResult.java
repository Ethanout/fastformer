package io.github.fastformer.fastplace.geometry.generation;

import java.util.Objects;
import java.util.Set;
import net.minecraft.core.BlockPos;

/** Explicit result at the boundary between geometry generation and world placement. */
public record BlockGenerationResult(Status status, Set<BlockPos> blocks) {
   public BlockGenerationResult {
      Objects.requireNonNull(status, "status");
      Objects.requireNonNull(blocks, "blocks");
      blocks = status == Status.SUCCESS ? GeneratedBlockSets.readOnly(blocks) : Set.of();
   }

   public static BlockGenerationResult fromLegacy(Set<BlockPos> blocks) {
      Objects.requireNonNull(blocks, "blocks");
      if (GenerationFailed.is(blocks)) {
         return new BlockGenerationResult(Status.CONSTRAINTS_FAILED, Set.of());
      }
      if (GenerationLimitExceeded.is(blocks)) {
         return new BlockGenerationResult(Status.LIMIT_EXCEEDED, Set.of());
      }
      return new BlockGenerationResult(Status.SUCCESS, blocks);
   }

   /** Creates a successful result without materializing a second position set. */
   public static BlockGenerationResult success(BlockPositionSource source) {
      Objects.requireNonNull(source, "source");
      return new BlockGenerationResult(Status.SUCCESS, BlockPositionSources.asSet(source));
   }

   public static BlockGenerationResult limitExceeded() {
      return new BlockGenerationResult(Status.LIMIT_EXCEEDED, Set.of());
   }

   public static BlockGenerationResult constraintsFailed() {
      return new BlockGenerationResult(Status.CONSTRAINTS_FAILED, Set.of());
   }

   public boolean successful() {
      return status == Status.SUCCESS;
   }

   /**
    * Exposes generated positions without making consumers depend on the
    * concrete set implementation. The source remains task-owned.
    */
   public BlockPositionSource positionSource() {
      return BlockPositionSources.from(this.blocks);
   }

   public enum Status {
      SUCCESS,
      LIMIT_EXCEEDED,
      CONSTRAINTS_FAILED
   }
}
