package io.github.fastformer.client.render.model;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

public record GhostPlane(Direction direction, int coordinate) {
   public static GhostPlane of(BlockPos pos, Direction direction) {
      int coordinate = switch (direction) {
         case DOWN -> pos.getY();
         case UP -> pos.getY() + 1;
         case NORTH -> pos.getZ();
         case SOUTH -> pos.getZ() + 1;
         case WEST -> pos.getX();
         case EAST -> pos.getX() + 1;
      };
      return new GhostPlane(direction, coordinate);
   }
}
