package io.github.fastformer.client.render.model;

import io.github.fastformer.fastplace.geometry.generation.GenerationFailed;
import io.github.fastformer.fastplace.geometry.BlockPositionSets;
import io.github.fastformer.fastplace.geometry.generation.GenerationLimitExceeded;
import java.util.Set;
import net.minecraft.core.BlockPos;

public record BuildingBlockResult(BuildingPreviewKey key, Set<BlockPos> blocks) {
   public BuildingBlockResult {
      blocks = GenerationLimitExceeded.is(blocks) || GenerationFailed.is(blocks) ? blocks : BlockPositionSets.copyOf(blocks);
   }
}
