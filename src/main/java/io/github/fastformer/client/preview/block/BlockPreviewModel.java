package io.github.fastformer.client.preview.block;

import io.github.fastformer.common.world.BlockSnapshot;
import java.util.Map;
import net.minecraft.core.BlockPos;

/** Immutable content shared by preview passes without consulting a live input session. */
public record BlockPreviewModel(Map<BlockPos, BlockSnapshot> blocks) {
   public static final BlockPreviewModel EMPTY = new BlockPreviewModel(Map.of());

   public BlockPreviewModel { blocks = Map.copyOf(blocks); }
}
