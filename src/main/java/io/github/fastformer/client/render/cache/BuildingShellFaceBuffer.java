package io.github.fastformer.client.render.cache;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexSorting;
import io.github.fastformer.client.render.FastPlaceClientPreview;
import io.github.fastformer.client.render.ShapeShellMesh;
import java.util.List;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/** Owns one shell's face vertices and updates only its camera-sorted indices. */
public final class BuildingShellFaceBuffer {
   private static final double FACE_OFFSET = 0.002;

   private List<ShapeShellMesh.Face> faces;
   private VertexBuffer buffer;
   private MeshData.SortState sortState;
   private ByteBufferBuilder indexBytes;
   private Vec3 origin = Vec3.ZERO;
   private Vec3 sortedForCamera;

   public void draw(PoseStack pose, Vec3 camera, List<ShapeShellMesh.Face> faces, RenderType type, float alpha) {
      if (this.faces != faces) {
         this.clear();
         if (!faces.isEmpty()) {
            this.origin = faces.getFirst().vertices().getFirst();
            this.upload(faces, camera, type);
         }
         this.faces = faces;
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
         this.sortForCamera(camera);
         this.buffer.drawWithShader(transform, RenderSystem.getProjectionMatrix(), RenderSystem.getShader());
      } finally {
         VertexBuffer.unbind();
         RenderSystem.setShaderColor(previousColor[0], previousColor[1], previousColor[2], previousColor[3]);
         type.clearRenderState();
      }
   }

   private void upload(List<ShapeShellMesh.Face> faces, Vec3 camera, RenderType type) {
      int vertexCapacity = (int)Math.clamp(
         (long)faces.size() * 4L * type.format().getVertexSize(), 256L, 16L * 1024L * 1024L
      );
      try (ByteBufferBuilder vertices = new ByteBufferBuilder(vertexCapacity)) {
         BufferBuilder builder = new BufferBuilder(vertices, type.mode(), type.format());
         PoseStack localPose = new PoseStack();
         for (ShapeShellMesh.Face face : faces) {
            Vec3 normal = Vec3.atLowerCornerOf(face.direction().getNormal()).scale(FACE_OFFSET);
            List<Vec3> points = face.vertices();
            ShapeShellMesh.Color color = face.color();
            FastPlaceClientPreview.addGhostQuadUnscaled(
               localPose,
               builder,
               points.get(0).add(normal).subtract(this.origin),
               points.get(1).add(normal).subtract(this.origin),
               points.get(2).add(normal).subtract(this.origin),
               points.get(3).add(normal).subtract(this.origin),
               color.red(),
               color.green(),
               color.blue(),
               1.0F
            );
         }
         MeshData data = builder.build();
         if (data == null) {
            return;
         }
         this.indexBytes = new ByteBufferBuilder(indexCapacity(faces.size()));
         try {
            this.sortState = data.sortQuads(this.indexBytes, sortingFor(camera, this.origin));
         } catch (RuntimeException | Error failure) {
            // The sorting buffer is not reachable from sortState yet, so release it
            // here instead of waiting for the next clear().
            this.indexBytes.close();
            this.indexBytes = null;
            throw failure;
         }
         this.sortedForCamera = camera;
         VertexBuffer uploaded = new VertexBuffer(VertexBuffer.Usage.STATIC);
         try {
            uploaded.bind();
            uploaded.upload(data);
            this.buffer = uploaded;
         } catch (RuntimeException | Error failure) {
            uploaded.close();
            throw failure;
         } finally {
            VertexBuffer.unbind();
         }
      }
   }

   private void sortForCamera(Vec3 camera) {
      if (this.sortState == null || camera.equals(this.sortedForCamera)) {
         return;
      }
      ByteBufferBuilder.Result indices = this.sortState.buildSortedIndexBuffer(
         this.indexBytes, sortingFor(camera, this.origin)
      );
      if (indices != null) {
         this.buffer.uploadIndexBuffer(indices);
      }
      this.sortedForCamera = camera;
   }

   private static VertexSorting sortingFor(Vec3 camera, Vec3 origin) {
      return VertexSorting.byDistance(
         (float)(camera.x - origin.x),
         (float)(camera.y - origin.y),
         (float)(camera.z - origin.z)
      );
   }

   static int indexCapacity(int faceCount) {
      return (int)Math.clamp((long)faceCount * 6L * Integer.BYTES, 256L, 16L * 1024L * 1024L);
   }

   public void clear() {
      if (this.buffer != null) {
         this.buffer.close();
         this.buffer = null;
      }
      if (this.indexBytes != null) {
         this.indexBytes.close();
         this.indexBytes = null;
      }
      this.faces = null;
      this.sortState = null;
      this.origin = Vec3.ZERO;
      this.sortedForCamera = null;
   }
}
