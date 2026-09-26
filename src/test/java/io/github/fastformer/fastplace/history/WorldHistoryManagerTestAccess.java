package io.github.fastformer.fastplace.history;

import io.github.fastformer.fastplace.world.snapshot.ReversibleBlockSnapshot;
import java.util.List;
import java.util.UUID;

/** Package bridge for cross-package lifecycle tests. */
public final class WorldHistoryManagerTestAccess {
   private WorldHistoryManagerTestAccess() {
   }

   public static int recoveryCaptureCount(UUID owner) {
      return WorldHistoryManager.recoveryCaptureCountForTest(owner);
   }

   public static WorldChangeBatch activeRecoveryBatch(UUID owner) {
      return WorldHistoryManager.activeRecoveryBatchForTest(owner);
   }

   public static List<ReversibleBlockSnapshot> sourceSnapshots(WorldChangeBatch batch, boolean undo) {
      return batch.sourceSnapshots(undo);
   }

   public static List<ReversibleBlockSnapshot> targetSnapshots(WorldChangeBatch batch, boolean undo) {
      return batch.targetSnapshots(undo);
   }
}
