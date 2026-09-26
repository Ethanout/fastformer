package io.github.fastformer.client.render.core;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.systems.RenderSystem;
import io.github.fastformer.client.placement.QuickReplaceMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
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
      poseStack.pushPose();
      poseStack.translate(preview.position().getX() - camera.x, preview.position().getY() - camera.y, preview.position().getZ() - camera.z);
      RenderSystem.enableBlend();
      RenderSystem.defaultBlendFunc();
      RenderSystem.setShaderColor(0.55F, 0.9F, 1.0F, 0.42F);
      minecraft.getBlockRenderer().renderSingleBlock(preview.state(), poseStack, buffers, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
      RenderSystem.setShaderColor(1F, 1F, 1F, 1F);
      RenderSystem.disableBlend();
      poseStack.popPose();
   }
}
