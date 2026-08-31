package io.github.fastformer.client.render.cache;

import io.github.fastformer.client.render.model.PendingGhostMesh;
import java.util.List;
import java.util.function.Function;
import com.mojang.blaze3d.vertex.VertexBuffer;
import io.github.fastformer.client.render.PendingPreviewGrid;

/** Owns the GPU buffer corresponding to the current pending preview mesh. */
public final class PendingGhostBufferCache {
   private final Function<List<PendingPreviewGrid.Segment>, VertexBuffer> uploader;
   private PendingGhostMesh mesh = PendingGhostMesh.empty();
   private VertexBuffer buffer;

   public PendingGhostBufferCache(Function<List<PendingPreviewGrid.Segment>, VertexBuffer> uploader) {
      this.uploader = uploader;
   }

   public VertexBuffer buffer(PendingGhostMesh requestedMesh) {
      if (this.mesh != requestedMesh) {
         clear();
         this.mesh = requestedMesh;
         if (!requestedMesh.gridEdges().isEmpty()) {
            this.buffer = this.uploader.apply(requestedMesh.gridEdges());
         }
      }
      return this.buffer;
   }

   public void clear() {
      if (this.buffer != null) {
         this.buffer.close();
         this.buffer = null;
      }
      this.mesh = PendingGhostMesh.empty();
   }
}
