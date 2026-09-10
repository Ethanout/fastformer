package io.github.fastformer.fastplace.placement.effect;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.LongUnaryOperator;
import java.util.function.UnaryOperator;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import io.github.fastformer.fastplace.geometry.generation.BlockGenerationResult;
import io.github.fastformer.fastplace.geometry.generation.GeneratedBlockSets;

/** Frozen effect behavior used by preview generation and the placement task. */
public record ResolvedPlacementEffect(
   ResourceLocation id,
   UnaryOperator<Set<BlockPos>> targetBlocks,
   LongUnaryOperator estimatedBlocks,
   Function<Set<BlockPos>, Map<BlockPos, BlockState>> stateOverrides
) {
   private static final UnaryOperator<Set<BlockPos>> UNCHANGED_TARGETS = targets -> targets;

   public ResolvedPlacementEffect {
      if (id == null || targetBlocks == null || estimatedBlocks == null || stateOverrides == null) {
         throw new IllegalArgumentException("A resolved placement effect requires generation and state strategies");
      }
   }

   public ResolvedPlacementEffect(
      ResourceLocation id,
      Function<Set<BlockPos>, Map<BlockPos, BlockState>> stateOverrides
   ) {
      this(id, UNCHANGED_TARGETS, value -> value, stateOverrides);
   }

   public Set<BlockPos> applyToTargets(Set<BlockPos> baseTargets) {
      BlockGenerationResult baseResult = BlockGenerationResult.fromLegacy(baseTargets);
      if (!baseResult.successful()) {
         return baseTargets;
      }
      return this.transformSuccessfulTargets(baseResult.blocks());
   }

   public BlockGenerationResult applyToTargets(BlockGenerationResult baseResult) {
      Objects.requireNonNull(baseResult, "baseResult");
      if (!baseResult.successful()) {
         return baseResult;
      }
      return BlockGenerationResult.fromLegacy(this.transformSuccessfulTargets(baseResult.blocks()));
   }

   private Set<BlockPos> transformSuccessfulTargets(Set<BlockPos> baseTargets) {
      if (!transformsTargets()) {
         return baseTargets;
      }
      // Effects receive a read-only view.  Copying here would materialize a
      // potentially lazy, packed target set before the effect can transform it.
      Set<BlockPos> transformed = this.targetBlocks.apply(GeneratedBlockSets.readOnly(baseTargets));
      if (transformed == null) {
         throw new IllegalStateException("Placement effect returned null targets: " + this.id);
      }
      BlockGenerationResult transformedResult = BlockGenerationResult.fromLegacy(transformed);
      return transformedResult.successful() ? GeneratedBlockSets.readOnly(transformed) : transformed;
   }

   public boolean transformsTargets() {
      return this.targetBlocks != UNCHANGED_TARGETS;
   }

   public long estimateTargetBlocks(long baseEstimate) {
      long estimate = this.estimatedBlocks.applyAsLong(Math.max(0L, baseEstimate));
      return estimate < 0L ? Long.MAX_VALUE : estimate;
   }
}
