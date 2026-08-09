package io.github.fastformer.fastplace;

/** Conservative heap preflight for snapshot-heavy reversible operations. */
final class WorldOperationMemory {
   private static final long MINIMUM_RESERVE = 256L * 1024L * 1024L;
   private static final long ESTIMATED_BYTES_PER_BLOCK = 224L;

   private WorldOperationMemory() {
   }

   static boolean canPrepare(long blocks) {
      return canPrepare(blocks, 0L);
   }

   static boolean canPrepare(long blocks, long additionalBytes) {
      Runtime runtime = Runtime.getRuntime();
      return canPrepare(
         blocks,
         additionalBytes,
         runtime.maxMemory(),
         runtime.totalMemory(),
         runtime.freeMemory()
      );
   }

   static boolean canPrepare(long blocks, long maxMemory, long totalMemory, long freeMemory) {
      return canPrepare(blocks, 0L, maxMemory, totalMemory, freeMemory);
   }

   static boolean canPrepare(
      long blocks,
      long additionalBytes,
      long maxMemory,
      long totalMemory,
      long freeMemory
   ) {
      if (blocks <= 0L) {
         return true;
      }
      long used = Math.max(0L, totalMemory - freeMemory);
      long available = Math.max(0L, maxMemory - used);
      long reserve = Math.max(MINIMUM_RESERVE, maxMemory / 8L);
      long usable = Math.max(0L, available - reserve);
      if (blocks > Long.MAX_VALUE / ESTIMATED_BYTES_PER_BLOCK) {
         return false;
      }
      long base = blocks * ESTIMATED_BYTES_PER_BLOCK;
      if (additionalBytes < 0L || additionalBytes > Long.MAX_VALUE - base) {
         return false;
      }
      return base + additionalBytes <= usable;
   }

   static long saturatingAdd(long first, long second) {
      if (first < 0L || second < 0L || first > Long.MAX_VALUE - second) {
         return Long.MAX_VALUE;
      }
      return first + second;
   }

   static long snapshotNbtReserve(ReversibleBlockSnapshot snapshot) {
      if (snapshot == null || snapshot.blockEntity() == null) {
         return 0L;
      }
      long bytes = snapshot.blockEntity().estimatedBytes();
      return bytes > Long.MAX_VALUE / 3L ? Long.MAX_VALUE : bytes * 3L;
   }
}
