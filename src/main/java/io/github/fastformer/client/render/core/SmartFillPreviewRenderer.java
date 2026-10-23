package io.github.fastformer.client.render.core;

import io.github.fastformer.client.input.FastPlaceClientInput;
import io.github.fastformer.client.render.WorkspacePreviewRenderer;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

final class SmartFillPreviewRenderer {
   private SmartFillPreviewRenderer() { }
   static void render(RenderLevelStageEvent event, Minecraft minecraft) {
      var blocks = FastPlaceClientInput.currentSession().smartFill().blocks();
      if (blocks.isEmpty() || minecraft.level == null || minecraft.player == null) return;
      WorkspacePreviewRenderer.renderBlocks(event.getPoseStack(), minecraft.renderBuffers().bufferSource(), minecraft,
         event.getCamera().getPosition(), blocks, 1, 1, 1, 0.55F, 1);
   }
}
