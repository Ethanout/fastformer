package io.github.fastformer.client.preview.block;

import io.github.fastformer.common.world.BlockSnapshot;
import java.util.Map;
import net.minecraft.core.BlockPos;

/** Captured block content; input and placement systems resolve targets before preview assembly. */
public record BlockPreviewInput(Map<BlockPos, BlockSnapshot> blocks) {
   public static final BlockPreviewInput EMPTY = new BlockPreviewInput(Map.of());

   public BlockPreviewInput { blocks = Map.copyOf(blocks); }
}
