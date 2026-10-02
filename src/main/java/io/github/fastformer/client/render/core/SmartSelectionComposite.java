package io.github.fastformer.client.render.core;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import io.github.fastformer.client.render.FastPlaceClientShaders;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;

/** Composites cells over the world without sampling an attached depth texture. */
final class SmartSelectionComposite {
   private TextureTarget worldDepth;

   void draw(RenderTarget cells, RenderTarget main, Matrix4f projection) {
      var shader = FastPlaceClientShaders.smartSelectionComposite();
      if (shader == null) return;
      if (worldDepth == null) worldDepth = new TextureTarget(main.width, main.height, true, Minecraft.ON_OSX);
      else if (worldDepth.width != main.width || worldDepth.height != main.height) {
         worldDepth.resize(main.width, main.height, Minecraft.ON_OSX);
      }
      // Reading main's depth while drawing into main would create a framebuffer feedback loop.
      worldDepth.copyDepthFrom(main);
      main.bindWrite(true);
      RenderSystem.disableDepthTest();
      RenderSystem.depthMask(false);
      // Shader blend settings are cached separately from RenderSystem state.
      RenderSystem.enableBlend();
      RenderSystem.defaultBlendFunc();
      RenderSystem.disableCull();
      try {
         shader.setSampler("SelectionColor", cells.getColorTextureId());
         shader.setSampler("SelectionDepth", cells.getDepthTextureId());
         shader.setSampler("WorldDepth", worldDepth.getDepthTextureId());
         shader.safeGetUniform("InverseProjection").set(new Matrix4f(projection).invert());
         RenderSystem.setShader(() -> shader);
         var quad = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
         quad.addVertex(-1, -1, 0).setUv(0, 0);
         quad.addVertex(1, -1, 0).setUv(1, 0);
         quad.addVertex(1, 1, 0).setUv(1, 1);
         quad.addVertex(-1, 1, 0).setUv(0, 1);
         BufferUploader.drawWithShader(quad.buildOrThrow());
      } finally {
         RenderSystem.disableBlend();
         RenderSystem.enableCull();
         RenderSystem.enableDepthTest();
         RenderSystem.depthMask(true);
      }
   }

   void clear() {
      if (worldDepth != null) { worldDepth.destroyBuffers(); worldDepth = null; }
   }
}
