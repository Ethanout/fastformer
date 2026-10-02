package io.github.fastformer.client.render;

import java.util.Collection;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource.BufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import io.github.fastformer.client.render.type.PreviewRenderTypes;
import io.github.fastformer.fastplace.geometry.GeometryPalette;
import io.github.fastformer.client.render.guide.GuideRenderer;
import net.minecraft.util.RandomSource;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

import io.github.fastformer.workspace.model.ClientBlockSnapshot;
import io.github.fastformer.client.interaction.InteractionObject;
import io.github.fastformer.client.interaction.PartLabelInteraction;

/** Renders client workspace blocks and the small labels attached to them. */
public final class WorkspacePreviewRenderer {

   private WorkspacePreviewRenderer() {
   }

   public static void renderBlocks(
      PoseStack poseStack, BufferSource buffers, Minecraft minecraft, Vec3 camera,
      Map<BlockPos, ClientBlockSnapshot> blocks, float red, float green, float blue,
      float alpha, float worldOpacity
   ) {
      renderBlocks(poseStack, buffers, minecraft, camera, blocks, blocks, red, green, blue, alpha, worldOpacity);
   }

   public static void renderBlocks(
      PoseStack poseStack, BufferSource buffers, Minecraft minecraft, Vec3 camera,
      Map<BlockPos, ClientBlockSnapshot> blocks,
      Map<BlockPos, ClientBlockSnapshot> occlusionBlocks,
      float red, float green, float blue, float alpha, float worldOpacity
   ) {
      renderBlocks(poseStack, buffers, minecraft, camera, blocks, occlusionBlocks, red, green, blue, alpha, worldOpacity, false);
   }

   public static void renderBlocks(
      PoseStack poseStack, BufferSource buffers, Minecraft minecraft, Vec3 camera,
      Map<BlockPos, ClientBlockSnapshot> blocks, Map<BlockPos, ClientBlockSnapshot> occlusionBlocks,
      float red, float green, float blue, float alpha, float worldOpacity, boolean dynamic
   ) {
      if (minecraft.level != null && FastPlaceClientShaders.previewMaterial() != null) {
         Map<BlockPos, net.minecraft.world.level.block.state.BlockState> states = new java.util.HashMap<>();
         occlusionBlocks.forEach((pos, snapshot) -> states.put(pos, snapshot.state()));
         PreviewMaterialRenderer.draw(poseStack, buffers, minecraft, camera, blocks,
            PreviewBlockOcclusion.level(minecraft.level, states), red, green, blue, alpha * worldOpacity, dynamic);
         return;
      }
      buffers.endBatch();
      com.mojang.blaze3d.systems.RenderSystem.enableBlend();
      com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
      com.mojang.blaze3d.systems.RenderSystem.setShaderColor(red, green, blue, alpha * worldOpacity);
      try {
         Map<BlockPos, net.minecraft.world.level.block.state.BlockState> states = new java.util.HashMap<>();
         occlusionBlocks.forEach((pos, snapshot) -> states.put(pos, snapshot.state()));
         net.minecraft.world.level.BlockAndTintGetter previewLevel = minecraft.level == null
            ? null : PreviewBlockOcclusion.level(minecraft.level, states);
         if (previewLevel != null) renderBlockModels(poseStack, buffers.getBuffer(PreviewRenderTypes.WORKSPACE_BLOCKS),
            minecraft, camera, blocks, previewLevel);
      } finally {
         buffers.endBatch(PreviewRenderTypes.WORKSPACE_BLOCKS);
         com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
         com.mojang.blaze3d.systems.RenderSystem.disableBlend();
      }
   }

   /** Uses snapshot states and vanilla models for both moving previews and x-ray previews. */
   public static void renderBlockModels(PoseStack pose, VertexConsumer vertices, Minecraft minecraft, Vec3 origin,
      Map<BlockPos, ClientBlockSnapshot> blocks, net.minecraft.world.level.BlockAndTintGetter previewLevel) {
      BlockRenderDispatcher renderer = minecraft.getBlockRenderer();
      for (var entry : blocks.entrySet()) {
         pose.pushPose();
         try {
            BlockPos position = entry.getKey();
            pose.translate(position.getX() - origin.x, position.getY() - origin.y, position.getZ() - origin.z);
            renderer.renderBatched(entry.getValue().state(), position, previewLevel, pose, vertices, true,
               RandomSource.create(position.asLong()));
         } finally {
            pose.popPose();
         }
      }
   }

   public static void renderPartLabel(
      PoseStack poseStack, BufferSource buffers, Minecraft minecraft, Vec3 camera,
      InteractionObject object, PartLabelInteraction.Context context
   ) {
      var label = PartLabelInteraction.present(object, context);
      Vec3 anchor = label.anchor();
      Component text = label.text();
      var appearance = label.appearance();
      poseStack.pushPose();
      poseStack.translate(anchor.x - camera.x, anchor.y - camera.y, anchor.z - camera.z);
      poseStack.mulPose(minecraft.gameRenderer.getMainCamera().rotation());
      poseStack.scale(-appearance.scale(), -appearance.scale(), appearance.scale());
      float x = -minecraft.font.width(text) * 0.5F;
      minecraft.font.drawInBatch(
         text, x, -minecraft.font.lineHeight * 0.5F,
         appearance.textColor(),
         false, poseStack.last().pose(), buffers, Font.DisplayMode.SEE_THROUGH,
         appearance.backgroundColor(), 0x00F000F0
      );
      poseStack.popPose();
   }

   public static void renderHintLabel(
      PoseStack poseStack, BufferSource buffers, Minecraft minecraft, Vec3 camera,
      Vec3 position, String text
   ) {
      poseStack.pushPose();
      poseStack.translate(position.x - camera.x, position.y - camera.y, position.z - camera.z);
      poseStack.mulPose(minecraft.gameRenderer.getMainCamera().rotation());
      poseStack.scale(-PreviewStyle.LABEL_SCALE, -PreviewStyle.LABEL_SCALE, PreviewStyle.LABEL_SCALE);
      minecraft.font.drawInBatch(
         text, -minecraft.font.width(text) * 0.5F, -minecraft.font.lineHeight - 3.0F,
         GeometryPalette.paperInk().argb(), false, poseStack.last().pose(), buffers, Font.DisplayMode.SEE_THROUGH,
         GeometryPalette.paper().argb(200), LightTexture.FULL_BRIGHT
      );
      poseStack.popPose();
   }

   public static void renderPendingDeleteBlocks(
      PoseStack poseStack, BufferSource buffers, Vec3 camera,
      Collection<BlockPos> blocks, double dashOffset, float opacity
   ) {
      poseStack.pushPose();
      poseStack.translate(-camera.x, -camera.y, -camera.z);
      VertexConsumer lines = buffers.getBuffer(PreviewRenderTypes.GHOST_OUTLINE_LINES);
      for (BlockPos pos : blocks) {
         renderDeleteFlowingBox(
            poseStack, lines, Vec3.atCenterOf(pos), new Vec3(0.505, 0.505, 0.505),
            dashOffset + (pos.getX() + pos.getY() + pos.getZ()) * 0.17, 0.94F * opacity
         );
      }
      poseStack.popPose();
   }

   private static void renderDeleteFlowingBox(
      PoseStack poseStack, VertexConsumer consumer, Vec3 center,
      Vec3 halfExtents, double offset, float alpha
   ) {
      double x0 = center.x - halfExtents.x;
      double y0 = center.y - halfExtents.y;
      double z0 = center.z - halfExtents.z;
      double x1 = center.x + halfExtents.x;
      double y1 = center.y + halfExtents.y;
      double z1 = center.z + halfExtents.z;
      Vec3[] corners = {
         new Vec3(x0, y0, z0), new Vec3(x1, y0, z0), new Vec3(x1, y1, z0), new Vec3(x0, y1, z0),
         new Vec3(x0, y0, z1), new Vec3(x1, y0, z1), new Vec3(x1, y1, z1), new Vec3(x0, y1, z1)
      };
      int[][] edgesAndFaceDiagonals = {
         {0, 1}, {1, 2}, {2, 3}, {3, 0}, {4, 5}, {5, 6}, {6, 7}, {7, 4},
         {0, 4}, {1, 5}, {2, 6}, {3, 7}, {0, 2}, {1, 3}, {4, 6}, {5, 7},
         {0, 5}, {1, 4}, {3, 6}, {2, 7}, {0, 7}, {3, 4}, {1, 6}, {2, 5}
      };
      for (int[] edge : edgesAndFaceDiagonals) {
         renderRedFlowingDashedLine(poseStack, consumer, corners[edge[0]], corners[edge[1]], alpha, offset);
      }
   }

   private static void renderRedFlowingDashedLine(
      PoseStack poseStack, VertexConsumer consumer, Vec3 from, Vec3 to, float alpha, double offset
   ) {
      GuideRenderer.renderDashedLine(poseStack, consumer, from, to, GeometryPalette.brick(),
         alpha, offset, PreviewStyle.DASH_LENGTH, 1.0F);
   }
}
