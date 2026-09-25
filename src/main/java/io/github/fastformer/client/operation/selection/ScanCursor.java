package io.github.fastformer.client.operation.selection;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

/** Bounds scanned cells, including air, before world access. */
public final class ScanCursor {
   private final int minX, minY, minZ, sizeY, sizeZ;
   private final long volume;
   private long index;
   public ScanCursor(AABB box, long limit) {
      double x = Math.ceil(box.maxX) - Math.floor(box.minX);
      double y = Math.ceil(box.maxY) - Math.floor(box.minY);
      double z = Math.ceil(box.maxZ) - Math.floor(box.minZ);
      double cells = x * y * z;
      if (!Double.isFinite(cells) || cells < 1 || cells > limit
         || box.minX < -30_000_000 || box.maxX > 30_000_000 || box.minZ < -30_000_000 || box.maxZ > 30_000_000
         || box.minY < Integer.MIN_VALUE || box.maxY > Integer.MAX_VALUE)
         throw new IllegalArgumentException("Selection scan exceeds its budget");
      minX = (int)Math.floor(box.minX); minY = (int)Math.floor(box.minY); minZ = (int)Math.floor(box.minZ);
      sizeY = (int)y; sizeZ = (int)z; volume = (long)cells;
   }
   public long volume() { return volume; }
   public boolean hasNext() { return index < volume; }
   public BlockPos next() {
      if (!hasNext()) throw new java.util.NoSuchElementException();
      long current = index++;
      return new BlockPos(minX + (int)(current / ((long)sizeY * sizeZ)),
         minY + (int)(current / sizeZ % sizeY), minZ + (int)(current % sizeZ));
   }
}
