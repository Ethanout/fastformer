package io.github.fastformer.server.worldwrite.snapshot;

import io.github.fastformer.common.world.memory.WorldOperationMemory;

/** Measures the block-entity payloads retained by reversible server snapshots. */
public final class SnapshotMemoryRules {
   private SnapshotMemoryRules() {}

   public static long nbtReserve(ReversibleBlockSnapshot snapshot) {
      if (snapshot == null || snapshot.blockEntity() == null) return 0L;
      long bytes = snapshot.blockEntity().estimatedBytes();
      return bytes > Long.MAX_VALUE / 3L ? Long.MAX_VALUE : bytes * 3L;
   }

   public static long nbtReserve(Iterable<ReversibleBlockSnapshot> snapshots) {
      if (snapshots == null) return 0L;
      long bytes = 0L;
      for (ReversibleBlockSnapshot snapshot : snapshots) {
         bytes = WorldOperationMemory.saturatingAdd(bytes, nbtReserve(snapshot));
      }
      return bytes;
   }
}
