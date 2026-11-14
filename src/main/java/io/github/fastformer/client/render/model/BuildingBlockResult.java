package io.github.fastformer.client.render.model;

import io.github.fastformer.common.geometry.generation.source.GenerationFailed;
import io.github.fastformer.common.world.BlockPositionSets;
import io.github.fastformer.common.geometry.generation.source.GenerationLimitExceeded;
import java.util.Set;
import net.minecraft.core.BlockPos;

public record BuildingBlockResult(BuildingPreviewKey key, Set<BlockPos> blocks) {
   public BuildingBlockResult {
      blocks = GenerationLimitExceeded.is(blocks) || GenerationFailed.is(blocks) ? blocks : BlockPositionSets.copyOf(blocks);
   }
}
