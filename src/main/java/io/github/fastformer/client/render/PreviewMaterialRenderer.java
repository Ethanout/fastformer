package io.github.fastformer.client.render;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import io.github.fastformer.client.render.theme.VisualThemes;
import io.github.fastformer.client.render.type.PreviewRenderTypes;
import io.github.fastformer.workspace.model.ClientBlockSnapshot;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource.BufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/** Shared model pass and region-only material effect for every placement preview. */
public final class PreviewMaterialRenderer {
   private static TextureTarget target;
   private static Object level;

   private PreviewMaterialRenderer() { }

   public static float confirmedAlpha() { return VisualThemes.value("confirmed_alpha", 0.85F); }

   public static float pendingAlpha(float pulse) {
      float min = VisualThemes.value("pending_min_alpha", 0.05F);
      float max = VisualThemes.value("pending_max_alpha", 0.30F);
      return min + (max - min) * Math.clamp(pulse, 0, 1);
   }

   public static void draw(PoseStack pose, BufferSource buffers, Minecraft minecraft, Vec3 camera,
      Map<BlockPos, ClientBlockSnapshot> blocks, BlockAndTintGetter previewLevel,
      float red, float green, float blue, float alpha, boolean dynamic) {
      if (blocks.isEmpty() || alpha <= 0) return;
      var shader = io.github.fastformer.client.render.style.VisualStyleHooks.shader(FastPlaceClientShaders.previewMaterial(),
         io.github.fastformer.client.render.style.VisualStyleContext.Pass.MATERIAL, dynamic);
      if (shader == null) return;
      buffers.endBatch();
      var main = minecraft.getMainRenderTarget();
      if (level != minecraft.level) { clear(); level = minecraft.level; }
      if (target == null) target = new TextureTarget(main.width, main.height, true, Minecraft.ON_OSX);
      else if (target.width != main.width || target.height != main.height) target.resize(main.width, main.height, Minecraft.ON_OSX);
      Matrix4f inverse = new Matrix4f(RenderSystem.getProjectionMatrix())
         .mul(RenderSystem.getModelViewMatrix()).mul(pose.last().pose()).invert();
      float[] previous = RenderSystem.getShaderColor().clone();
      try {
         target.setClearColor(0, 0, 0, 0);
         target.clear(Minecraft.ON_OSX);
         target.copyDepthFrom(main);
         target.bindWrite(true);
         RenderSystem.setShaderColor(1, 1, 1, 1);
         WorkspacePreviewRenderer.renderBlockModels(pose, buffers.getBuffer(PreviewRenderTypes.PREVIEW_MODEL_TARGET),
            minecraft, camera, blocks, previewLevel);
         buffers.endBatch(PreviewRenderTypes.PREVIEW_MODEL_TARGET);
         main.bindWrite(true);
         shader.setSampler("PreviewColor", target.getColorTextureId());
         shader.setSampler("PreviewDepth", target.getDepthTextureId());
         shader.safeGetUniform("InverseViewProjection").set(inverse);
         shader.safeGetUniform("CameraPosition").set((float)camera.x, (float)camera.y, (float)camera.z);
         shader.safeGetUniform("PreviewTint").set(red, green, blue, alpha);
         shader.safeGetUniform("HatchStrength").set(VisualThemes.value("hatch_strength", 0));
         shader.safeGetUniform("HatchScale").set(VisualThemes.value("hatch_scale", 12));
         shader.safeGetUniform("BoilFrame").set((float)io.github.fastformer.client.render.geometry.BoilClock.sheet(dynamic));
         // Both themes use model depth; the line shader controls the hidden appearance.
         RenderSystem.enableDepthTest();
         RenderSystem.depthFunc(org.lwjgl.opengl.GL11.GL_LEQUAL);
         RenderSystem.depthMask(true);
         RenderSystem.enableBlend();
         RenderSystem.defaultBlendFunc();
         RenderSystem.disableCull();
         RenderSystem.setShader(() -> shader);
         var quad = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
         quad.addVertex(-1, -1, 0).setUv(0, 0);
         quad.addVertex(1, -1, 0).setUv(1, 0);
         quad.addVertex(1, 1, 0).setUv(1, 1);
         quad.addVertex(-1, 1, 0).setUv(0, 1);
         BufferUploader.drawWithShader(quad.buildOrThrow());
      } finally {
         main.bindWrite(true);
         RenderSystem.enableDepthTest();
         RenderSystem.depthMask(true);
         RenderSystem.enableCull();
         RenderSystem.disableBlend();
         RenderSystem.setShaderColor(previous[0], previous[1], previous[2], previous[3]);
      }
   }

   public static void clear() {
      if (target != null) { target.destroyBuffers(); target = null; }
      level = null;
   }
}
