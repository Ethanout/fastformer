package io.github.fastformer.client.render.core;

import static io.github.fastformer.client.render.type.PreviewRenderTypes.*;
import com.mojang.blaze3d.vertex.PoseStack;
import io.github.fastformer.client.render.shell.ShapeShellRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource.BufferSource;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

final class InitialBlockPreviewRenderer {
   private InitialBlockPreviewRenderer() {}

   static void render(RenderLevelStageEvent event, Minecraft minecraft, LocalPlayer player, BlockHitResult hit, boolean embedded, float opacity) {
      var preview = io.github.fastformer.client.render.model.InitialBlockPreview.resolve(
         player, hit, embedded
      );
      if (preview == null) return;
      PoseStack pose = event.getPoseStack();
      BufferSource buffers = minecraft.renderBuffers().bufferSource();
      Vec3 camera = event.getCamera().getPosition();
      ShapeShellRenderer.renderFaces(pose, buffers.getBuffer(GHOST_FACES), camera, preview.mesh().faces(), 0.24F * opacity);
      buffers.endBatch(GHOST_FACES);
      ShapeShellRenderer.renderEdges(pose, buffers.getBuffer(PENDING_XRAY_LINES), camera, preview.mesh().edges(), 0.35F * opacity);
      buffers.endBatch(PENDING_XRAY_LINES);
      ShapeShellRenderer.renderEdges(pose, buffers.getBuffer(GHOST_OUTLINE_LINES), camera, preview.mesh().edges(), 0.92F * opacity);
      buffers.endBatch(GHOST_OUTLINE_LINES);
   }
}
