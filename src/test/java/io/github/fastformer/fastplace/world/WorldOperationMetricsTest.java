package io.github.fastformer.fastplace.world;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WorldOperationMetricsTest {
   @Test
   void summaryContainsOperationIdentityAndCounters() {
      WorldOperationMetrics metrics = new WorldOperationMetrics();
      metrics.targetCount(12);
      metrics.phase(WorldOperationPhase.SNAPSHOT);
      metrics.generationReady();
      metrics.snapshotCaptured();
      metrics.phase(WorldOperationPhase.WRITE);
      metrics.journalReady();
      metrics.queued();
      metrics.leaseAcquired();
      metrics.writeAttempt(true);
      metrics.recordBatch(7, 2_500_000L);
      metrics.recordBatch(0, 1_000_000L);
      metrics.recordBatch(8, 10_000_000L);

      String summary = metrics.summary();
      assertTrue(summary.contains("operation="));
      assertTrue(summary.contains("phase=WRITE"));
      assertTrue(summary.contains("targets=12"));
      assertTrue(summary.contains("snapshots=1"));
      assertTrue(summary.contains("writes=1"));
      assertTrue(summary.contains("placed=1"));
      assertTrue(summary.contains("firstWriteMs="));
      assertTrue(summary.contains("afterFirstWriteMs="));
      assertTrue(summary.contains("firstSnapshotMs="));
      assertTrue(summary.contains("generationReadyMs="));
      assertTrue(summary.contains("journalReadyMs="));
      assertTrue(summary.contains("leaseAcquiredMs="));
      assertTrue(summary.contains("queuedMs="));
      assertTrue(summary.contains("threads="));
      assertTrue(summary.contains("firstWrite:"));
      assertTrue(summary.contains("batches=2"));
      assertTrue(summary.contains("lastBatchCells=8"));
      assertTrue(summary.contains("lastBatchMs=10"));
      assertTrue(summary.contains("batchP95Ms=10"));
      assertTrue(summary.contains("phaseMs="));
      assertTrue(summary.contains("GENERATION="));
      assertTrue(summary.contains("SNAPSHOT="));
      assertTrue(summary.contains("WRITE="));

      metrics.complete();
      assertTrue(metrics.summary().contains("phase=COMPLETE"));
   }

   @Test
   void failedWriteAttemptIsCountedWithoutIncreasingPlaced() {
      WorldOperationMetrics metrics = new WorldOperationMetrics();

      metrics.writeAttempt(false);

      String summary = metrics.summary();
      assertTrue(summary.contains("writes=1"));
      assertTrue(summary.contains("placed=0"));
   }
}
