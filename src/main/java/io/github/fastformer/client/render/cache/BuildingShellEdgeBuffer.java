package io.github.fastformer.client.render.cache;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import io.github.fastformer.client.render.ShapeShellMesh;
import io.github.fastformer.client.render.guide.GuideRenderer;
import java.util.List;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/** Owns one shell's edge upload; depth passes share the same vertices. */
public final class BuildingShellEdgeBuffer {
   private List<ShapeShellMesh.StyledEdge> edges;
   private VertexBuffer buffer;
   private Vec3 origin = Vec3.ZERO;

   public void draw(PoseStack pose, Vec3 camera, List<ShapeShellMesh.StyledEdge> edges, RenderType type, float alpha) {
      if (this.edges != edges) {
         this.clear();
         if (!edges.isEmpty()) {
            this.origin = edges.getFirst().from();
            this.buffer = upload(edges, this.origin, type);
         }
         this.edges = edges;
      }
      if (this.buffer == null) {
         return;
      }
      Matrix4f transform = new Matrix4f(pose.last().pose()).translate(
         (float)(this.origin.x - camera.x), (float)(this.origin.y - camera.y), (float)(this.origin.z - camera.z)
      );
      float[] previousColor = RenderSystem.getShaderColor().clone();
      type.setupRenderState();
      try {
         RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, alpha);
         this.buffer.bind();
         this.buffer.drawWithShader(transform, RenderSystem.getProjectionMatrix(), RenderSystem.getShader());
      } finally {
         VertexBuffer.unbind();
         RenderSystem.setShaderColor(previousColor[0], previousColor[1], previousColor[2], previousColor[3]);
         type.clearRenderState();
      }
   }

   private static VertexBuffer upload(List<ShapeShellMesh.StyledEdge> edges, Vec3 origin, RenderType type) {
      int capacity = (int)Math.clamp((long)edges.size() * 4L * type.format().getVertexSize(), 256L, 16L * 1024L * 1024L);
      try (ByteBufferBuilder bytes = new ByteBufferBuilder(capacity)) {
         BufferBuilder builder = new BufferBuilder(bytes, type.mode(), type.format());
         PoseStack localPose = new PoseStack();
         for (ShapeShellMesh.StyledEdge edge : edges) {
            ShapeShellMesh.Color color = edge.color();
            GuideRenderer.renderLine(localPose, builder, edge.from().subtract(origin), edge.to().subtract(origin),
               color.red(), color.green(), color.blue(), 1.0F, 1.0F);
         }
         MeshData data = builder.build();
         if (data == null) {
            return null;
         }
         VertexBuffer uploaded = new VertexBuffer(VertexBuffer.Usage.STATIC);
         try {
            uploaded.bind();
            uploaded.upload(data);
            return uploaded;
         } catch (RuntimeException | Error failure) {
            uploaded.close();
            throw failure;
         } finally {
            VertexBuffer.unbind();
         }
      }
   }

   public void clear() {
      if (this.buffer != null) {
         this.buffer.close();
         this.buffer = null;
      }
      this.edges = null;
      this.origin = Vec3.ZERO;
   }
}
