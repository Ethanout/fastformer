package io.github.fastformer.fastplace.geometry.generation;

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
   private final long estimatedScan;
   private final AtomicLong scanned = new AtomicLong();
   private final AtomicLong generated = new AtomicLong();
   private final ConcurrentLinkedQueue<SectionBatch> published = new ConcurrentLinkedQueue<>();
   private final Map<Long, ArrayList<BlockPos>> pendingBySection = new HashMap<>();
   private int pendingCount;
   private volatile boolean complete;
   private volatile boolean cancelled;

   public ProgressiveBlockGeneration(long estimatedScan) {
      this.estimatedScan = Math.max(0L, estimatedScan);
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
      long section = SectionPos.asLong(
         SectionPos.blockToSectionCoord(position.getX()),
         SectionPos.blockToSectionCoord(position.getY()),
         SectionPos.blockToSectionCoord(position.getZ())
      );
      this.pendingBySection.computeIfAbsent(section, ignored -> new ArrayList<>()).add(position.immutable());
      this.pendingCount++;
      this.generated.incrementAndGet();
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
         this.publishPending();
         this.complete = true;
      }
   }

   public void cancel() {
      this.cancelled = true;
   }

   public List<SectionBatch> drainPublished() {
      ArrayList<SectionBatch> result = new ArrayList<>();
      for (SectionBatch batch; (batch = this.published.poll()) != null;) {
         result.add(batch);
      }
      return List.copyOf(result);
   }

   public Snapshot snapshot() {
      return new Snapshot(this.scanned.get(), this.estimatedScan, this.generated.get(), this.complete);
   }

   private void publishPending() {
      if (this.pendingCount == 0) {
         return;
      }
      for (Map.Entry<Long, ArrayList<BlockPos>> entry : this.pendingBySection.entrySet()) {
         if (!entry.getValue().isEmpty()) {
            this.published.add(new SectionBatch(entry.getKey(), List.copyOf(entry.getValue())));
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

   public record SectionBatch(long section, List<BlockPos> blocks) {
      public SectionBatch {
         blocks = List.copyOf(blocks);
      }
   }
}
