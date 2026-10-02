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
      if (!player.getMainHandItem().isEmpty()) {
         var fallback = PreviewRenderResources.INITIAL_MODEL.render(pose, buffers, camera,
            java.util.Set.of(preview.position()), preview.state(), java.util.Map.of(),
            io.github.fastformer.client.render.PreviewMaterialRenderer.pendingAlpha(FastPlaceClientPreviewCore.ghostBreathPulse()) * opacity, true);
         if (!fallback.isEmpty()) {
            ShapeShellRenderer.renderFaces(pose, buffers.getBuffer(GHOST_FACES), camera, preview.mesh().faces(),
               io.github.fastformer.client.render.PreviewMaterialRenderer.pendingAlpha(FastPlaceClientPreviewCore.ghostBreathPulse()) * opacity);
            buffers.endBatch(GHOST_FACES);
         }
      }
      // First-point candidates use the same faint continuous contour as later stages.
      double offset = FastPlaceClientPreviewCore.pendingGridDashOffset();
      ShapeShellRenderer.renderDashedEdges(pose, buffers.getBuffer(DYNAMIC_XRAY_LINES), camera, preview.mesh().edges(), opacity, offset);
      buffers.endBatch(DYNAMIC_XRAY_LINES);
      ShapeShellRenderer.renderDashedEdges(pose, buffers.getBuffer(DYNAMIC_LINES), camera, preview.mesh().edges(), opacity, offset);
      buffers.endBatch(DYNAMIC_LINES);
   }
}
