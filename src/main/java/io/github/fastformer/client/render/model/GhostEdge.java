package io.github.fastformer.client.render.model;

public record GhostEdge(GridPoint from, GridPoint to) {
   public static GhostEdge of(GridPoint first, GridPoint second) {
      return first.compareTo(second) <= 0 ? new GhostEdge(first, second) : new GhostEdge(second, first);
   }
}
