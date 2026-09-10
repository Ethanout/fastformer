package io.github.fastformer.fastplace.world;

/** Owns recent batch timing without owning task state. */
public final class WorldBatchFeedback {
   private WorldOperationMetrics metrics;
   private int cells;
   private long elapsedNanos;

   public WorldBatchFeedback(WorldOperationMetrics metrics) {
      if (metrics == null) {
         throw new IllegalArgumentException("Batch feedback requires operation metrics");
      }
      this.metrics = metrics;
   }

   public int cells() {
      return this.cells;
   }

   public long elapsedNanos() {
      return this.elapsedNanos;
   }

   /** Rebinds feedback when a task adopts the operation metrics of its batch. */
   public void attach(WorldOperationMetrics metrics) {
      if (metrics == null) {
         throw new IllegalArgumentException("Batch feedback requires operation metrics");
      }
      this.metrics = metrics;
   }

   public void record(int cells, long elapsedNanos) {
      this.cells = Math.max(0, cells);
      this.elapsedNanos = Math.max(0L, elapsedNanos);
      if (this.cells > 0) {
         this.metrics.recordBatch(this.cells, this.elapsedNanos);
      }
   }
}
