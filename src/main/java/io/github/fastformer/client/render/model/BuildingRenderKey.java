package io.github.fastformer.client.render.model;

import net.minecraft.core.BlockPos;

public record BuildingRenderKey(
   long stateVersion,
   BuildingPreviewKey previewKey,
   long previewResultVersion,
   BlockPos candidate,
   BlockPos hoveredPoint
) {
}
