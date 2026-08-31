package io.github.fastformer.client.render.model;

import net.minecraft.core.Direction;

public record GhostQuad(Direction direction, double x0, double y0, double z0, double x1, double y1, double z1) {
   public static GhostQuad of(GhostPlane plane, int u, int v, int width, int height) {
      return switch (plane.direction().getAxis()) {
         case Y -> new GhostQuad(plane.direction(), u, plane.coordinate(), v, u + width, plane.coordinate(), v + height);
         case Z -> new GhostQuad(plane.direction(), u, v, plane.coordinate(), u + width, v + height, plane.coordinate());
         case X -> new GhostQuad(plane.direction(), plane.coordinate(), v, u, plane.coordinate(), v + height, u + width);
      };
   }
}
