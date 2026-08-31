package io.github.fastformer.client.render.model;

import java.util.List;

public record GhostMesh(List<GhostQuad> faces, List<GhostEdge> edges) {
   public static GhostMesh empty() {
      return new GhostMesh(List.of(), List.of());
   }
}
