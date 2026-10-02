package io.github.fastformer.client.render.core;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.fastformer.client.render.WorkspacePreviewRenderer;
import io.github.fastformer.workspace.model.ClientBlockSnapshot;
import java.util.Map;
import io.github.fastformer.client.placement.QuickReplaceMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource.BufferSource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

final class QuickReplacePreviewRenderer {
   private QuickReplacePreviewRenderer() {}

   static void render(RenderLevelStageEvent event, Minecraft minecraft, LocalPlayer player) {
      QuickReplaceMode.Preview preview = QuickReplaceMode.preview(minecraft);
      if (preview == null) return;
      PoseStack poseStack = event.getPoseStack();
      Vec3 camera = event.getCamera().getPosition();
      BufferSource buffers = minecraft.renderBuffers().bufferSource();
      WorkspacePreviewRenderer.renderBlocks(poseStack, buffers, minecraft, camera,
         Map.of(preview.position(), new ClientBlockSnapshot(preview.state(), null)), 1, 1, 1, 0.42F, 1);
   }
}
