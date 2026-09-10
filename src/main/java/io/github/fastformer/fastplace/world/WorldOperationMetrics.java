package io.github.fastformer.fastplace.world;

import java.util.UUID;
import java.util.EnumMap;
import java.util.Arrays;
import java.util.Map;

/**
 * Per-operation counters used to diagnose latency, memory pressure, and
 * recovery failures without changing the world operation itself.
 */
public final class WorldOperationMetrics {
   private final UUID operationId;
   private final long startedAt = System.nanoTime();
   private long phaseStartedAt = this.startedAt;
   private WorldOperationPhase phase = WorldOperationPhase.GENERATION;
   private final Map<WorldOperationPhase, Long> phaseNanos = new EnumMap<>(WorldOperationPhase.class);
   private int targetCount;
   private int snapshots;
   private int writes;
   private int placed;
   private int batches;
   private int lastBatchCells;
   private long lastBatchNanos;
   private long firstWriteNanos;
   private long completedNanos;
   private long firstSnapshotNanos;
   private long generationReadyNanos;
   private long journalReadyNanos;
   private long leaseAcquiredNanos;
   private long queuedNanos;
   private String queuedThread = "";
   private String generationThread = "";
   private String snapshotThread = "";
   private String journalThread = "";
   private String leaseThread = "";
   private String firstWriteThread = "";
   private final long[] batchNanosSamples = new long[64];
   private int batchSampleCount;
   private int nextBatchSample;
   private long peakUsedMemory;

   public WorldOperationMetrics() {
      this(UUID.randomUUID());
   }

   public WorldOperationMetrics(UUID operationId) {
      this.operationId = operationId == null ? UUID.randomUUID() : operationId;
   }

   public UUID operationId() {
      return this.operationId;
   }

   public synchronized void phase(WorldOperationPhase next) {
      if (next != null) {
         if (next != this.phase) {
            recordCurrentPhase(System.nanoTime());
            this.phaseStartedAt = System.nanoTime();
         }
         this.phase = next;
      }
      sampleMemory();
   }

   public synchronized void targetCount(int count) {
      this.targetCount = Math.max(0, count);
      sampleMemory();
   }

   public synchronized void snapshotCaptured() {
      if (this.firstSnapshotNanos == 0L) {
         this.firstSnapshotNanos = Math.max(0L, System.nanoTime() - this.startedAt);
         this.snapshotThread = Thread.currentThread().getName();
      }
      this.snapshots++;
      sampleMemory();
   }

   public synchronized void generationReady() {
      if (this.generationReadyNanos == 0L) {
         this.generationReadyNanos = Math.max(0L, System.nanoTime() - this.startedAt);
         this.generationThread = Thread.currentThread().getName();
      }
      sampleMemory();
   }

   public synchronized void journalReady() {
      if (this.journalReadyNanos == 0L) {
         this.journalReadyNanos = Math.max(0L, System.nanoTime() - this.startedAt);
         this.journalThread = Thread.currentThread().getName();
      }
      sampleMemory();
   }

   public synchronized void leaseAcquired() {
      if (this.leaseAcquiredNanos == 0L) {
         this.leaseAcquiredNanos = Math.max(0L, System.nanoTime() - this.startedAt);
         this.leaseThread = Thread.currentThread().getName();
      }
      sampleMemory();
   }

   public synchronized void queued() {
      if (this.queuedNanos == 0L) {
         this.queuedNanos = Math.max(0L, System.nanoTime() - this.startedAt);
         this.queuedThread = Thread.currentThread().getName();
      }
      sampleMemory();
   }

   public synchronized void writeAttempt(boolean didPlace) {
      if (didPlace && this.firstWriteNanos == 0L) {
         this.firstWriteNanos = Math.max(0L, System.nanoTime() - this.startedAt);
         this.firstWriteThread = Thread.currentThread().getName();
      }
      this.writes++;
      if (didPlace) {
         this.placed++;
      }
      sampleMemory();
   }

   public synchronized void recordBatch(int cells, long elapsedNanos) {
      if (cells <= 0) {
         return;
      }
      this.batches++;
      this.lastBatchCells = Math.max(0, cells);
      this.lastBatchNanos = Math.max(0L, elapsedNanos);
      this.batchNanosSamples[this.nextBatchSample] = this.lastBatchNanos;
      this.nextBatchSample = (this.nextBatchSample + 1) % this.batchNanosSamples.length;
      this.batchSampleCount = Math.min(this.batchSampleCount + 1, this.batchNanosSamples.length);
      sampleMemory();
   }

   public WorldOperationPhase phase() {
      return this.phase;
   }

   public synchronized void complete() {
      if (this.completedNanos == 0L) {
         this.completedNanos = Math.max(0L, System.nanoTime() - this.startedAt);
      }
      phase(WorldOperationPhase.COMPLETE);
   }

   public synchronized String summary() {
      recordCurrentPhase(System.nanoTime());
      long elapsedMillis = Math.max(0L, (System.nanoTime() - this.startedAt) / 1_000_000L);
      return "operation=" + this.operationId
         + ", phase=" + this.phase
         + ", targets=" + this.targetCount
         + ", snapshots=" + this.snapshots
         + ", writes=" + this.writes
         + ", placed=" + this.placed
         + ", generationReadyMs=" + (this.generationReadyNanos / 1_000_000L)
         + ", firstSnapshotMs=" + (this.firstSnapshotNanos / 1_000_000L)
         + ", journalReadyMs=" + (this.journalReadyNanos / 1_000_000L)
         + ", queuedMs=" + (this.queuedNanos / 1_000_000L)
         + ", threads=" + threadSummary()
         + ", leaseAcquiredMs=" + (this.leaseAcquiredNanos / 1_000_000L)
         + ", firstWriteMs=" + (this.firstWriteNanos / 1_000_000L)
         + ", afterFirstWriteMs=" + afterFirstWriteMillis()
         + ", batches=" + this.batches
         + ", lastBatchCells=" + this.lastBatchCells
         + ", lastBatchMs=" + (this.lastBatchNanos / 1_000_000L)
         + ", batchP95Ms=" + batchP95Millis()
         + ", phaseMs=" + phaseSummary()
         + ", peakUsedMiB=" + (this.peakUsedMemory / (1024L * 1024L))
         + ", elapsedMs=" + elapsedMillis;
   }

   private void recordCurrentPhase(long now) {
      long elapsed = Math.max(0L, now - this.phaseStartedAt);
      this.phaseNanos.merge(this.phase, elapsed, Long::sum);
      this.phaseStartedAt = now;
   }

   private String phaseSummary() {
      StringBuilder result = new StringBuilder();
      for (WorldOperationPhase phase : WorldOperationPhase.values()) {
         Long nanos = this.phaseNanos.get(phase);
         if (nanos != null && nanos > 0L) {
            if (result.length() > 0) {
               result.append(';');
            }
            result.append(phase.name()).append('=').append(nanos / 1_000_000L);
         }
      }
      return result.toString();
   }

   private String threadSummary() {
      return "queued:" + this.queuedThread
         + ",generation:" + this.generationThread
         + ",snapshot:" + this.snapshotThread
         + ",journal:" + this.journalThread
         + ",lease:" + this.leaseThread
         + ",firstWrite:" + this.firstWriteThread;
   }

   private long batchP95Millis() {
      if (this.batchSampleCount == 0) {
         return 0L;
      }
      long[] sorted = Arrays.copyOf(this.batchNanosSamples, this.batchSampleCount);
      Arrays.sort(sorted);
      int index = (int)Math.min(sorted.length - 1, (long)Math.ceil(sorted.length * 0.95D) - 1L);
      return sorted[index] / 1_000_000L;
   }

   private long afterFirstWriteMillis() {
      if (this.firstWriteNanos == 0L || this.completedNanos == 0L) {
         return 0L;
      }
      return Math.max(0L, (this.completedNanos - this.firstWriteNanos) / 1_000_000L);
   }

   private void sampleMemory() {
      Runtime runtime = Runtime.getRuntime();
      long used = Math.max(0L, runtime.totalMemory() - runtime.freeMemory());
      this.peakUsedMemory = Math.max(this.peakUsedMemory, used);
   }
}
