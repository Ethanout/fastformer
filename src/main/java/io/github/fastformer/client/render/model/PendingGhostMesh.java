package io.github.fastformer.client.render.model;

import io.github.fastformer.client.render.PendingPreviewGrid;
import java.util.List;

public record PendingGhostMesh(List<PendingPreviewGrid.Segment> gridEdges) {
   public PendingGhostMesh {
      gridEdges = List.copyOf(gridEdges);
   }

   public static PendingGhostMesh empty() {
      return new PendingGhostMesh(List.of());
   }
}
