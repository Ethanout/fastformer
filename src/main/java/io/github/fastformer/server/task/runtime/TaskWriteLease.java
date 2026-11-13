package io.github.fastformer.server.task.runtime;

import io.github.fastformer.server.recovery.WorldJournalPreparation;
import io.github.fastformer.fastplace.world.WorldOperationCommit;
import io.github.fastformer.fastplace.world.WorldOperationMetrics;
import io.github.fastformer.server.task.WorldTaskContext;
import io.github.fastformer.fastplace.world.WorldWriteCoordinator;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import java.util.UUID;

/** Sole owner of a task's lease, including transfer to asynchronous journal cleanup. */
public final class TaskWriteLease {
   private WorldWriteCoordinator.Lease lease;

   public boolean acquire(WorldTaskContext context, ResourceKey<Level> dimension, WorldOperationMetrics metrics) {
      return acquire(context.server(), context.owner(), dimension, metrics);
   }

   boolean acquire(Object server, UUID owner, ResourceKey<Level> dimension, WorldOperationMetrics metrics) {
      if (lease != null && dimension.equals(lease.dimension()) && WorldWriteCoordinator.renew(lease)) return true;
      release();
      lease = WorldWriteCoordinator.takeOver(server, dimension, owner);
      if (lease == null) return false;
      metrics.leaseAcquired();
      return true;
   }

   public void release() {
      WorldWriteCoordinator.release(lease);
      lease = null;
   }

   /** Captures the exact lease before deferred work; a late callback cannot release a newer lease. */
   public void releaseAfterCancellation(WorldJournalPreparation journal, WorldOperationCommit commit) {
      if (commit != null) commit.cancel();
      releaseAfterCancellation(cancelled -> {
         if (cancelled == null) journal.cancel();
         else journal.releaseAfterCancellation(cancelled);
      });
   }

   /** Transfers the exact lease to the resource that is still cleaning up. */
   public void releaseAfterCancellation(java.util.function.Consumer<WorldWriteCoordinator.Lease> cleanup) {
      WorldWriteCoordinator.Lease cancelled = lease;
      lease = null;
      cleanup.accept(cancelled);
   }
}
