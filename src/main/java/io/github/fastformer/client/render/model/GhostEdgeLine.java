package io.github.fastformer.client.render.model;

import net.minecraft.core.Direction;

public record GhostEdgeLine(Direction.Axis axis, int fixedA, int fixedB) {
   public static GhostEdgeLine of(GhostEdge edge) {
      GridPoint from = edge.from();
      GridPoint to = edge.to();
      if (from.x() != to.x()) {
         return new GhostEdgeLine(Direction.Axis.X, from.y(), from.z());
      }
      if (from.y() != to.y()) {
         return new GhostEdgeLine(Direction.Axis.Y, from.x(), from.z());
      }
      return new GhostEdgeLine(Direction.Axis.Z, from.x(), from.y());
   }

   public GhostInterval interval(GhostEdge edge) {
      return switch (this.axis) {
         case X -> new GhostInterval(edge.from().x(), edge.to().x());
         case Y -> new GhostInterval(edge.from().y(), edge.to().y());
         case Z -> new GhostInterval(edge.from().z(), edge.to().z());
      };
   }

   public GhostEdge edge(int start, int end) {
      return switch (this.axis) {
         case X -> GhostEdge.of(new GridPoint(start, this.fixedA, this.fixedB), new GridPoint(end, this.fixedA, this.fixedB));
         case Y -> GhostEdge.of(new GridPoint(this.fixedA, start, this.fixedB), new GridPoint(this.fixedA, end, this.fixedB));
         case Z -> GhostEdge.of(new GridPoint(this.fixedA, this.fixedB, start), new GridPoint(this.fixedA, this.fixedB, end));
      };
   }
}
