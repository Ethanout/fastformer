package io.github.fastformer.client.render;

import io.github.fastformer.fastplace.geometry.GeometryPalette;

import io.github.fastformer.client.input.HistoryConflictConfirmation;
import io.github.fastformer.client.render.type.PreviewRenderTypes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

public final class HistoryConflictRenderer {
   private HistoryConflictRenderer() { }
   public static void render(RenderLevelStageEvent event, Minecraft minecraft) {
      var prompt = HistoryConflictConfirmation.pending();
      if (prompt == null || minecraft.level == null || !minecraft.level.dimension().location().equals(prompt.dimension())) return;
      var pose = event.getPoseStack();
      var camera = event.getCamera().getPosition();
      var buffers = minecraft.renderBuffers().bufferSource();
      pose.pushPose();
      pose.translate(-camera.x, -camera.y, -camera.z);
      for (var type : java.util.List.of(PreviewRenderTypes.GHOST_OUTLINE_LINES, PreviewRenderTypes.PENDING_XRAY_LINES)) {
         var lines = buffers.getBuffer(type);
         float alpha = PreviewStyle.OUTLINE_ALPHA;
         for (var pos : HistoryConflictConfirmation.positions()) {
            var bounds = new AABB(pos).inflate(PreviewStyle.OUTLINE_INFLATE);
            if (event.getFrustum().isVisible(bounds)) LevelRenderer.renderLineBox(pose, lines, bounds,
               GeometryPalette.brick().red(), GeometryPalette.brick().green(), GeometryPalette.brick().blue(), alpha);
         }
         buffers.endBatch(type);
      }
      pose.popPose();
   }
}
