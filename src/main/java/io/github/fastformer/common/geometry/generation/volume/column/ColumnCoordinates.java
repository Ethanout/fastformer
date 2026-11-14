package io.github.fastformer.common.geometry.generation.volume.column;

import net.minecraft.core.BlockPos;

/** Coordinate conversion and overflow-safe face neighbors for compact columns. */
final class ColumnCoordinates {
   static final int[][] FACE_STEPS = {
      {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
   };

   private ColumnCoordinates() {}

   static int coordinate(BlockPos position, int axis) {
      return switch (axis) {
         case 0 -> position.getX();
         case 1 -> position.getY();
         case 2 -> position.getZ();
         default -> throw new IllegalArgumentException("axis " + axis);
      };
   }

   static BlockPos neighbor(BlockPos position, int[] step) {
      long x = (long)position.getX() + step[0];
      long y = (long)position.getY() + step[1];
      long z = (long)position.getZ() + step[2];
      if (x < Integer.MIN_VALUE || x > Integer.MAX_VALUE
         || y < Integer.MIN_VALUE || y > Integer.MAX_VALUE
         || z < Integer.MIN_VALUE || z > Integer.MAX_VALUE) {
         return null;
      }
      return new BlockPos((int)x, (int)y, (int)z);
   }
}
