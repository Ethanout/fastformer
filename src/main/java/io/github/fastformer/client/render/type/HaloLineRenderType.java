package io.github.fastformer.client.render.type;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import io.github.fastformer.client.render.FastPlaceClientShaders;
import io.github.fastformer.client.render.PreviewStyle;
import io.github.fastformer.fastplace.geometry.GeometryPalette;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix4f;

/**
 * A line pipeline that lays a wider contrast halo under a pencil line.
 *
 * <p>Both passes read the same uploaded vertices. The halo shaders keep only the vertex alpha, so
 * faint marks get a faint halo.
 */
public final class HaloLineRenderType extends RenderType {
   private final boolean dynamic;

   HaloLineRenderType(String name, RenderType ink) {
      this(name, ink, false);
   }

   HaloLineRenderType(String name, RenderType ink, boolean dynamic) {
      super(name, ink.format(), ink.mode(), ink.bufferSize(), false, false,
         ink::setupRenderState, ink::clearRenderState);
      this.dynamic = dynamic;
   }

   @Override
   public void draw(MeshData mesh) {
      this.setupRenderState();
      try {
         VertexBuffer buffer = this.format().getImmediateDrawVertexBuffer();
         buffer.bind();
         buffer.upload(mesh);
         Matrix4f modelView = RenderSystem.getModelViewMatrix();
         Matrix4f projection = RenderSystem.getProjectionMatrix();
         drawUnder(this, buffer, modelView, projection);
         buffer.drawWithShader(modelView, projection, RenderSystem.getShader());
      } finally {
         VertexBuffer.unbind();
         this.clearRenderState();
      }
   }

   /**
    * Draws the halo for an already bound buffer, between {@code setupRenderState} and the ink draw.
    * Does nothing for pipelines without a halo.
    */
   public static void drawUnder(RenderType type, VertexBuffer buffer, Matrix4f modelView, Matrix4f projection) {
      if (!(type instanceof HaloLineRenderType)) {
         return;
      }
      ShaderInstance shader = type.format().contains(VertexFormatElement.UV0)
         ? FastPlaceClientShaders.haloDashedLines()
         : FastPlaceClientShaders.haloLines(((HaloLineRenderType)type).dynamic);
      if (shader == null) {
         return;
      }
      float[] color = RenderSystem.getShaderColor().clone();
      float width = RenderSystem.getShaderLineWidth();
      GeometryPalette.Color halo = GeometryPalette.halo();
      try {
         RenderSystem.lineWidth(width + io.github.fastformer.client.render.theme.VisualThemes.value("halo_extra_width", (float)PreviewStyle.HALO_EXTRA_WIDTH)
            * io.github.fastformer.client.render.style.VisualStyleContext.guiScaleFactor());
         RenderSystem.setShaderColor(halo.red(), halo.green(), halo.blue(), GeometryPalette.haloAlpha() * color[3]);
         buffer.drawWithShader(modelView, projection, shader);
      } finally {
         RenderSystem.lineWidth(width);
         RenderSystem.setShaderColor(color[0], color[1], color[2], color[3]);
      }
   }
}
