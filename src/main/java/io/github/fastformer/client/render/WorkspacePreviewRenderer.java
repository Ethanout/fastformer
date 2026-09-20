package io.github.fastformer.client.render;

import java.util.Collection;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource.BufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.neoforged.neoforge.client.RenderTypeHelper;
import net.minecraft.util.RandomSource;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

import io.github.fastformer.client.operation.model.ClientBlockSnapshot;
import io.github.fastformer.client.interaction.InteractionObject;
import io.github.fastformer.client.interaction.PartLabelInteraction;
import io.github.fastformer.client.render.FastPlaceClientPreview;

/** Renders client workspace blocks and the small labels attached to them. */
public final class WorkspacePreviewRenderer {
   private static final double EPSILON = 1.0E-7;
   private static final double DASH_LENGTH = 0.25;

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
      com.mojang.blaze3d.systems.RenderSystem.enableBlend();
      com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
      com.mojang.blaze3d.systems.RenderSystem.setShaderColor(red, green, blue, alpha * worldOpacity);
      try {
         Map<BlockPos, net.minecraft.world.level.block.state.BlockState> states = new java.util.HashMap<>();
         occlusionBlocks.forEach((pos, snapshot) -> states.put(pos, snapshot.state()));
         net.minecraft.world.level.BlockAndTintGetter previewLevel = minecraft.level == null
            ? null : PreviewBlockOcclusion.level(minecraft.level, states);
         BlockRenderDispatcher renderer = minecraft.getBlockRenderer();
         for (var entry : blocks.entrySet()) {
            poseStack.pushPose();
            poseStack.translate(
               entry.getKey().getX() - camera.x,
               entry.getKey().getY() - camera.y,
               entry.getKey().getZ() - camera.z
            );
            net.minecraft.world.level.block.state.BlockState state = entry.getValue().state();
            RenderType chunkType = ItemBlockRenderTypes.getRenderType(state, false);
            RenderType entityType = RenderTypeHelper.getEntityRenderType(chunkType, false);
            if (previewLevel != null) {
               renderer.renderBatched(
                  state, entry.getKey(), previewLevel, poseStack, buffers.getBuffer(entityType), true,
                  RandomSource.create(entry.getKey().asLong())
               );
            }
            poseStack.popPose();
         }
      } finally {
         buffers.endBatch();
         com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
         com.mojang.blaze3d.systems.RenderSystem.disableBlend();
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
      poseStack.scale(-0.021F, -0.021F, 0.021F);
      minecraft.font.drawInBatch(
         text, -minecraft.font.width(text) * 0.5F, -minecraft.font.lineHeight - 3.0F,
         0xFFFFFFFF, false, poseStack.last().pose(), buffers, Font.DisplayMode.SEE_THROUGH,
         0xB0203038, LightTexture.FULL_BRIGHT
      );
      poseStack.popPose();
   }

   public static void renderPendingDeleteBlocks(
      PoseStack poseStack, BufferSource buffers, Vec3 camera,
      Collection<BlockPos> blocks, double dashOffset
   ) {
      poseStack.pushPose();
      poseStack.translate(-camera.x, -camera.y, -camera.z);
      VertexConsumer lines = buffers.getBuffer(RenderType.lines());
      for (BlockPos pos : blocks) {
         renderDeleteFlowingBox(
            poseStack, lines, Vec3.atCenterOf(pos), new Vec3(0.505, 0.505, 0.505),
            dashOffset + (pos.getX() + pos.getY() + pos.getZ()) * 0.17, 0.94F
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
      Vec3 vector = to.subtract(from);
      double length = vector.length();
      if (length < EPSILON) {
         return;
      }
      Vec3 direction = vector.scale(1.0 / length);
      int index = (int)Math.floor(-offset / DASH_LENGTH) - 1;
      for (double start = index * DASH_LENGTH + offset; start < length; start += DASH_LENGTH, index++) {
         double clippedStart = Math.max(0.0, start);
         double clippedEnd = Math.min(length, start + DASH_LENGTH);
         if (clippedEnd <= clippedStart) {
            continue;
         }
         boolean bright = Math.floorMod(index, 2) == 0;
         FastPlaceClientPreview.renderLine(
            poseStack, consumer,
            from.add(direction.scale(clippedStart)), from.add(direction.scale(clippedEnd)),
            bright ? 1.0F : 0.38F, bright ? 0.12F : 0.0F, bright ? 0.08F : 0.0F, alpha
         );
      }
   }
}
