package io.github.fastformer.fastplace.history;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

/** Immutable bounds in block coordinates, independent of block models and net changes. */
record HistoryBounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
   HistoryBounds {
      if (minX > maxX || minY > maxY || minZ > maxZ) throw new IllegalArgumentException("Inverted history bounds");
   }

   static HistoryBounds of(long[] positions, int x, int y, int z, int width, int height, int depth) {
      if (positions == null) {
         if (width < 1 || height < 1 || depth < 1) throw new IllegalArgumentException("Empty dense history bounds");
         return new HistoryBounds(x, y, z, Math.toIntExact((long)x + width - 1),
            Math.toIntExact((long)y + height - 1), Math.toIntExact((long)z + depth - 1));
      }
      if (positions.length == 0) throw new IllegalArgumentException("Empty sparse history bounds");
      int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
      int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
      for (long packed : positions) {
         int px = BlockPos.getX(packed), py = BlockPos.getY(packed), pz = BlockPos.getZ(packed);
         minX = Math.min(minX, px);
         minY = Math.min(minY, py);
         minZ = Math.min(minZ, pz);
         maxX = Math.max(maxX, px);
         maxY = Math.max(maxY, py);
         maxZ = Math.max(maxZ, pz);
      }
      return new HistoryBounds(minX, minY, minZ, maxX, maxY, maxZ);
   }

   HistoryBounds union(HistoryBounds other) {
      return new HistoryBounds(Math.min(minX, other.minX), Math.min(minY, other.minY), Math.min(minZ, other.minZ),
         Math.max(maxX, other.maxX), Math.max(maxY, other.maxY), Math.max(maxZ, other.maxZ));
   }

   boolean within(HistoryBounds other, int distance) {
      return (long)minX - other.maxX <= distance && (long)other.minX - maxX <= distance
         && (long)minY - other.maxY <= distance && (long)other.minY - maxY <= distance
         && (long)minZ - other.maxZ <= distance && (long)other.minZ - maxZ <= distance;
   }

   void encode(CompoundTag root) {
      root.putIntArray("HistoryBounds", new int[]{minX, minY, minZ, maxX, maxY, maxZ});
   }

   /** Invalid or missing cached data must never remove the bounds of the actual recorded cells. */
   static HistoryBounds decode(CompoundTag root, HistoryBounds cells) {
      int[] values = root.getIntArray("HistoryBounds");
      if (values.length != 6) return cells;
      if (values[0] > cells.minX || values[1] > cells.minY || values[2] > cells.minZ
         || values[3] < cells.maxX || values[4] < cells.maxY || values[5] < cells.maxZ) return cells;
      return new HistoryBounds(values[0], values[1], values[2], values[3], values[4], values[5]);
   }
}
