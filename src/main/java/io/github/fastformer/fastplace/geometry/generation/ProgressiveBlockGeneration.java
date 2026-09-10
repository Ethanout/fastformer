package io.github.fastformer.fastplace.geometry.generation;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;

/** Thread-safe progress bridge from a background generator to a client/server tick thread. */
public final class ProgressiveBlockGeneration implements BlockGenerationObserver {
   private static final int PUBLISH_BLOCKS = 4096;
   private static final int MAX_PUBLISHED_BATCHES = 64;
   private final long estimatedScan;
   private final boolean publishBatches;
   private final AtomicLong scanned = new AtomicLong();
   private final AtomicLong generated = new AtomicLong();
   private final ConcurrentLinkedQueue<SectionBatch> published = new ConcurrentLinkedQueue<>();
   private final Map<Long, LongArrayList> pendingBySection = new HashMap<>();
   private int pendingCount;
   private volatile boolean complete;
   private volatile boolean cancelled;

   public ProgressiveBlockGeneration(long estimatedScan) {
      this(estimatedScan, true);
   }

   /**
    * Creates a progress bridge. Server-side placement generation has no
    * preview consumer, so it can disable batch retention and avoid both a
    * second position working set and producer backpressure deadlock.
    */
   public ProgressiveBlockGeneration(long estimatedScan, boolean publishBatches) {
      this.estimatedScan = Math.max(0L, estimatedScan);
      this.publishBatches = publishBatches;
   }

   @Override
   public void onScanned(long amount) {
      if (amount > 0L) {
         this.scanned.getAndAccumulate(amount, ProgressiveBlockGeneration::saturatedAdd);
      }
   }

   @Override
   public synchronized void onGenerated(BlockPos position) {
      this.checkCancelled();
      this.generated.incrementAndGet();
      if (!this.publishBatches) {
         return;
      }
      long section = SectionPos.asLong(
         SectionPos.blockToSectionCoord(position.getX()),
         SectionPos.blockToSectionCoord(position.getY()),
         SectionPos.blockToSectionCoord(position.getZ())
      );
      this.pendingBySection.computeIfAbsent(section, ignored -> new LongArrayList()).add(position.asLong());
      this.pendingCount++;
      if (this.pendingCount >= PUBLISH_BLOCKS) {
         this.publishPending();
      }
   }

   @Override
   public void checkCancelled() {
      if (this.cancelled || Thread.currentThread().isInterrupted()) {
         throw new CancellationException("FastFormer block generation cancelled");
      }
   }

   public synchronized void complete() {
      if (!this.cancelled) {
         if (this.publishBatches) {
            this.publishPending();
         }
         this.complete = true;
      }
   }

   public synchronized void cancel() {
      this.cancelled = true;
      this.published.clear();
      this.pendingBySection.clear();
      this.pendingCount = 0;
      this.notifyAll();
   }

   public List<SectionBatch> drainPublished() {
      ArrayList<SectionBatch> result = new ArrayList<>();
      for (SectionBatch batch; (batch = this.published.poll()) != null;) {
         result.add(batch);
      }
      if (!result.isEmpty()) {
         synchronized (this) {
            this.notifyAll();
         }
      }
      return List.copyOf(result);
   }

   /** Releases queued progress batches once the placement owns the final targets. */
   public synchronized void releasePublished() {
      this.published.clear();
      this.pendingBySection.clear();
      this.pendingCount = 0;
      this.notifyAll();
   }

   public Snapshot snapshot() {
      return new Snapshot(this.scanned.get(), this.estimatedScan, this.generated.get(), this.complete);
   }

   private void publishPending() {
      if (this.pendingCount == 0) {
         return;
      }
      while (this.published.size() >= MAX_PUBLISHED_BATCHES) {
         this.checkCancelled();
         try {
            this.wait(50L);
         } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new CancellationException("FastFormer block generation interrupted while waiting for consumers");
         }
      }
      for (Map.Entry<Long, LongArrayList> entry : this.pendingBySection.entrySet()) {
         if (!entry.getValue().isEmpty()) {
            this.published.add(new SectionBatch(entry.getKey(), entry.getValue().toLongArray()));
         }
      }
      this.pendingBySection.clear();
      this.pendingCount = 0;
   }

   private static long saturatedAdd(long left, long right) {
      return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
   }

   public record Snapshot(long scanned, long estimatedScan, long generated, boolean complete) {
   }

   public record SectionBatch(long section, long[] packedBlocks) {
      public SectionBatch {
         packedBlocks = packedBlocks.clone();
      }

      /**
       * Keeps the existing preview API while avoiding a second boxed position
       * collection in the bounded publication queue.
       */
      public List<BlockPos> blocks() {
         return new AbstractList<>() {
            @Override
            public BlockPos get(int index) {
               return BlockPos.of(SectionBatch.this.packedBlocks[index]);
            }

            @Override
            public int size() {
               return SectionBatch.this.packedBlocks.length;
            }
         };
      }

      @Override
      public long[] packedBlocks() {
         return this.packedBlocks.clone();
      }

      public long packedBlockCount() {
         return this.packedBlocks.length;
      }
   }
}
