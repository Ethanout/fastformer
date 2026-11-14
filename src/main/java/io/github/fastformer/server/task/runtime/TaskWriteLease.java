package io.github.fastformer.server.task.runtime;

import io.github.fastformer.server.recovery.WorldJournalPreparation;
import io.github.fastformer.server.worldwrite.transaction.WorldOperationCommit;
import io.github.fastformer.server.task.metrics.WorldOperationMetrics;
import io.github.fastformer.server.task.WorldTaskContext;
import io.github.fastformer.server.worldwrite.WorldWriteLeases;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import java.util.UUID;

/** Sole owner of a task's lease, including transfer to asynchronous journal cleanup. */
public final class TaskWriteLease {
   private WorldWriteLeases scope;
   private WorldWriteLeases.Lease lease;

   public boolean acquire(WorldTaskContext context, ResourceKey<Level> dimension, WorldOperationMetrics metrics) {
      return acquire(context.writeLeases(), context.owner(), dimension, metrics);
   }

   boolean acquire(WorldWriteLeases writes, UUID owner, ResourceKey<Level> dimension, WorldOperationMetrics metrics) {
      if (scope == writes && lease != null && owner.equals(lease.owner())
         && dimension.equals(lease.dimension()) && lease.isCurrent()) return true;
      release();
      lease = writes.takeOver(dimension, owner);
      if (lease == null) return false;
      scope = writes;
      metrics.leaseAcquired();
      return true;
   }

   public void release() {
      if (lease != null) lease.release();
      lease = null;
      scope = null;
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
   public void releaseAfterCancellation(java.util.function.Consumer<WorldWriteLeases.Lease> cleanup) {
      WorldWriteLeases.Lease cancelled = lease;
      lease = null;
      scope = null;
      cleanup.accept(cancelled);
   }
}
