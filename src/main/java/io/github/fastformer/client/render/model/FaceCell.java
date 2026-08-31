package io.github.fastformer.client.render.model;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

public record FaceCell(int u, int v) {
   public static FaceCell of(BlockPos pos, Direction direction) {
      return switch (direction.getAxis()) {
         case Y -> new FaceCell(pos.getX(), pos.getZ());
         case Z -> new FaceCell(pos.getX(), pos.getY());
         case X -> new FaceCell(pos.getZ(), pos.getY());
      };
   }
}
