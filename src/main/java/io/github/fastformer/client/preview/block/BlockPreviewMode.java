package io.github.fastformer.client.preview.block;

import io.github.fastformer.client.preview.frame.PreviewMode;
import io.github.fastformer.client.preview.frame.WorldPreviewFrame;
import io.github.fastformer.client.render.WorkspacePreviewRenderer;

/** Draws captured tool blocks; each tool owns an instance and its opacity. */
public final class BlockPreviewMode implements PreviewMode<BlockPreviewInput, BlockPreviewModel> {
   private final float alpha;

   public BlockPreviewMode(float alpha) {
      if (!Float.isFinite(alpha) || alpha < 0 || alpha > 1) throw new IllegalArgumentException("Invalid block preview alpha");
      this.alpha = alpha;
   }

   @Override public BlockPreviewModel buildModel(BlockPreviewInput input) {
      return input.blocks().isEmpty() ? BlockPreviewModel.EMPTY : new BlockPreviewModel(input.blocks());
   }

   @Override public void drawWorld(WorldPreviewFrame frame, BlockPreviewModel model) {
      if (model.blocks().isEmpty() || frame.minecraft().level == null || frame.minecraft().player == null) return;
      WorkspacePreviewRenderer.renderBlocks(frame.pose(), frame.buffers(), frame.minecraft(), frame.camera(),
         model.blocks(), 1, 1, 1, alpha, frame.opacity());
   }

   @Override public void clear() { }
}
