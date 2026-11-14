package io.github.fastformer.client.preview.geometry;

import static io.github.fastformer.client.render.type.PreviewRenderTypes.*;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import io.github.fastformer.client.preview.frame.WorldPreviewFrame;
import io.github.fastformer.client.render.FastPlaceClientShaders;
import io.github.fastformer.client.render.PendingPreviewGrid;
import io.github.fastformer.client.render.PreviewStyle;
import io.github.fastformer.client.render.cache.GhostMeshCache;
import io.github.fastformer.client.render.cache.PendingGhostBufferCache;
import io.github.fastformer.client.render.cache.PendingGhostMeshCache;
import io.github.fastformer.client.render.geometry.PencilStroke;
import io.github.fastformer.client.render.geometry.PreviewQuads;
import io.github.fastformer.client.render.guide.GuideRenderer;
import io.github.fastformer.client.render.mesh.GhostMeshBuilder;
import io.github.fastformer.client.render.model.GeometryRenderLayers;
import io.github.fastformer.client.render.model.GhostEdge;
import io.github.fastformer.client.render.GhostOutlineDepthBias;
import io.github.fastformer.client.render.model.GhostQuad;
import io.github.fastformer.client.render.model.PendingGhostMesh;
import io.github.fastformer.client.render.theme.VisualThemes;
import io.github.fastformer.client.render.type.HaloLineRenderType;
import io.github.fastformer.fastplace.geometry.GeometryPalette;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.BiConsumer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/** Owns geometry ghost meshes and GPU buffers; all frame state is passed by the caller. */
final class GhostBlockPreviewRenderer {
   private static final int FACE_BLOCK_LIMIT = 16_000;
   private static final double FACE_OFFSET = 0.002;
   private static final double OUTLINE_CAMERA_BIAS = 0.012;
   private static final float PENDING_ALPHA = 0.78F;
   private final GhostMeshCache confirmed = new GhostMeshCache(blocks -> GhostMeshBuilder.build(blocks, true, true, true));
   private final GhostMeshCache outline = new GhostMeshCache(blocks -> GhostMeshBuilder.build(blocks, false, true, true));
   private final PendingGhostMeshCache pending;
   private final PendingGhostBufferCache pendingBuffer = new PendingGhostBufferCache(GhostBlockPreviewRenderer::upload);

   GhostBlockPreviewRenderer(ThreadPoolExecutor executor, BiConsumer<String, Throwable> failureLogger) {
      pending = new PendingGhostMeshCache(executor, GhostMeshBuilder::buildPending,
         failure -> failureLogger.accept("Unable to build FastFormer preview mesh", failure));
   }

   void clear() { confirmed.clear(); outline.clear(); pending.clear(); pendingBuffer.clear(); }

   void draw(WorldPreviewFrame frame, GeometryRenderLayers layers) {
      drawConfirmed(frame, layers.confirmed());
      drawPending(frame, layers.pending());
   }

   private void drawConfirmed(WorldPreviewFrame frame, Set<BlockPos> blocks) {
      if (blocks.isEmpty()) return;
      boolean faces = blocks.size() <= FACE_BLOCK_LIMIT;
      var mesh = faces ? confirmed.mesh(blocks) : outline.mesh(blocks);
      var ink = GeometryPalette.ink();
      var color = GeometryPalette.confirmedOutline();
      if (faces) {
         var consumer = frame.buffers().getBuffer(GHOST_FACES);
         frame.pose().pushPose();
         frame.pose().translate(-frame.camera().x, -frame.camera().y, -frame.camera().z);
         for (var face : mesh.faces()) face(frame.pose(), consumer, face, ink.red(), ink.green(), ink.blue(), PreviewStyle.FACE_ALPHA * frame.opacity());
         frame.pose().popPose();
         frame.buffers().endBatch(GHOST_FACES);
      }
      edges(frame, frame.buffers().getBuffer(GHOST_OUTLINE_LINES), mesh.edges(), color.red(), color.green(), color.blue(),
         PreviewStyle.OUTLINE_ALPHA * frame.opacity());
      frame.buffers().endBatch(GHOST_OUTLINE_LINES);
   }

   private void drawPending(WorldPreviewFrame frame, Set<BlockPos> blocks) {
      if (blocks.isEmpty()) { pending.clearPreview(); pendingBuffer.clear(); return; }
      PendingGhostMesh mesh = pending.mesh(blocks);
      if (GeometryPalette.humanist()) { fallback(frame, mesh); return; }
      VertexBuffer buffer = pendingBuffer.buffer(mesh);
      if (buffer == null) return;
      if (FastPlaceClientShaders.pendingDashedLines() == null) { fallback(frame, mesh); return; }
      Matrix4f modelView = new Matrix4f(frame.modelView()).translate((float)-frame.camera().x, (float)-frame.camera().y, (float)-frame.camera().z);
      float alpha = VisualThemes.value("candidate_alpha", 0.8F) * frame.opacity();
      drawBuffer(frame, buffer, PENDING_DASHED_XRAY_LINES, modelView, alpha);
      drawBuffer(frame, buffer, PENDING_DASHED_LINES, modelView, alpha);
   }

   private static void fallback(WorldPreviewFrame frame, PendingGhostMesh mesh) {
      frame.pose().pushPose();
      frame.pose().translate(-frame.camera().x, -frame.camera().y, -frame.camera().z);
      for (RenderType type : List.of(DYNAMIC_XRAY_LINES, DYNAMIC_LINES)) {
         VertexConsumer consumer = frame.buffers().getBuffer(type);
         for (PendingPreviewGrid.Segment edge : mesh.gridEdges()) {
            GuideRenderer.renderAlternatingDashedLine(frame.pose(), consumer,
               new Vec3(edge.from().x(), edge.from().y(), edge.from().z()), new Vec3(edge.to().x(), edge.to().y(), edge.to().z()),
               PENDING_ALPHA * frame.opacity(), frame.animation().dashOffset(PreviewStyle.DASH_SPEED, PreviewStyle.DASH_PERIOD),
               PreviewStyle.DASH_LENGTH, 1.0F);
         }
         frame.buffers().endBatch(type);
      }
      frame.pose().popPose();
   }

   private static void drawBuffer(WorldPreviewFrame frame, VertexBuffer buffer, RenderType type, Matrix4f modelView, float alpha) {
      var shader = FastPlaceClientShaders.pendingDashedLines();
      if (shader == null) return;
      type.setupRenderState();
      try {
         FastPlaceClientShaders.setPendingDashOffset((float)frame.animation().dashOffset(PreviewStyle.DASH_SPEED, PreviewStyle.DASH_PERIOD));
         var ink = GeometryPalette.ink();
         RenderSystem.setShaderColor(ink.red(), ink.green(), ink.blue(), alpha);
         buffer.bind();
         HaloLineRenderType.drawUnder(type, buffer, modelView, frame.projection());
         buffer.drawWithShader(modelView, frame.projection(), shader);
      } finally {
         VertexBuffer.unbind();
         RenderSystem.setShaderColor(1, 1, 1, 1);
         type.clearRenderState();
      }
   }

   private static void edges(WorldPreviewFrame frame, VertexConsumer consumer, List<GhostEdge> edges, float red, float green, float blue, float alpha) {
      frame.pose().pushPose();
      frame.pose().translate(-frame.camera().x, -frame.camera().y, -frame.camera().z);
      for (GhostEdge edge : edges) {
         PencilStroke.draw(frame.pose(), consumer, GhostOutlineDepthBias.towardCamera(edge.from().vec3(), frame.camera(), OUTLINE_CAMERA_BIAS),
            GhostOutlineDepthBias.towardCamera(edge.to().vec3(), frame.camera(), OUTLINE_CAMERA_BIAS), red, green, blue, alpha, false);
      }
      frame.pose().popPose();
   }

   private static void face(PoseStack pose, VertexConsumer consumer, GhostQuad face, float red, float green, float blue, float alpha) {
      Vec3 normal = Vec3.atLowerCornerOf(face.direction().getNormal()).scale(FACE_OFFSET);
      double x0 = face.x0() + normal.x, y0 = face.y0() + normal.y, z0 = face.z0() + normal.z;
      double x1 = face.x1() + normal.x, y1 = face.y1() + normal.y, z1 = face.z1() + normal.z;
      switch (face.direction()) {
         case DOWN, UP -> PreviewQuads.write(pose, consumer, new Vec3(x0,y0,z0), new Vec3(x1,y0,z0), new Vec3(x1,y0,z1), new Vec3(x0,y0,z1), red,green,blue,alpha);
         case NORTH, SOUTH -> PreviewQuads.write(pose, consumer, new Vec3(x0,y0,z0), new Vec3(x1,y0,z0), new Vec3(x1,y1,z0), new Vec3(x0,y1,z0), red,green,blue,alpha);
         case WEST, EAST -> PreviewQuads.write(pose, consumer, new Vec3(x0,y0,z0), new Vec3(x0,y0,z1), new Vec3(x0,y1,z1), new Vec3(x0,y1,z0), red,green,blue,alpha);
      }
   }

   private static VertexBuffer upload(List<PendingPreviewGrid.Segment> edges) {
      long estimatedBytes = (long)edges.size() * 2L * DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL.getVertexSize();
      int capacity = (int)Math.clamp(estimatedBytes, 256L, 16L * 1024L * 1024L);
      try (ByteBufferBuilder bytes = new ByteBufferBuilder(capacity)) {
         BufferBuilder builder = new BufferBuilder(bytes, VertexFormat.Mode.LINES, DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL);
         for (PendingPreviewGrid.Segment edge : edges) {
            var from = edge.from();
            var to = edge.to();
            float dx = to.x() - from.x(), dy = to.y() - from.y(), dz = to.z() - from.z();
            float length = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
            if (length <= 0) continue;
            float inverse = 1 / length;
            builder.addVertex(from.x(), from.y(), from.z()).setUv(0, 0).setColor(1F,1F,1F,1F).setNormal(dx*inverse,dy*inverse,dz*inverse);
            builder.addVertex(to.x(), to.y(), to.z()).setUv(length, 0).setColor(1F,1F,1F,1F).setNormal(dx*inverse,dy*inverse,dz*inverse);
         }
         MeshData data = builder.buildOrThrow();
         VertexBuffer buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
         try {
            buffer.bind();
            buffer.upload(data);
            return buffer;
         } catch (RuntimeException | Error failure) {
            buffer.close();
            throw failure;
         } finally { VertexBuffer.unbind(); }
      }
   }
}
