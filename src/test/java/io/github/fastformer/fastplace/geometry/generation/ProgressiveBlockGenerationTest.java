package io.github.fastformer.fastplace.geometry.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fastformer.fastplace.FillMode;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class ProgressiveBlockGenerationTest {
   @Test
   void boundedDrainRetainsRemainingSectionsWithoutLosingTargets() {
      ProgressiveBlockGeneration progress = new ProgressiveBlockGeneration(48L);
      Set<BlockPos> expected = new HashSet<>();
      for (int x = 0; x < 48; x++) {
         BlockPos position = new BlockPos(x, 0, 0);
         expected.add(position);
         progress.onGenerated(position);
      }
      progress.complete();

      Set<BlockPos> actual = new HashSet<>();
      for (int frame = 0; frame < 3; frame++) {
         List<ProgressiveBlockGeneration.SectionBatch> batches = progress.drainPublished(16L);
         assertEquals(16L, batches.stream().mapToLong(ProgressiveBlockGeneration.SectionBatch::packedBlockCount).sum());
         batches.forEach(batch -> actual.addAll(batch.blocks()));
      }
      assertEquals(expected, actual);
      assertTrue(progress.drainPublished().isEmpty());
      assertThrows(IllegalArgumentException.class, () -> progress.drainPublished(0L));
   }

   @Test
   void publishesTheSameFinalTargetsGroupedBySection() {
      List<Vec3> base = List.of(
         new Vec3(0.5, 0.5, 0.5),
         new Vec3(31.5, 0.5, 0.5),
         new Vec3(31.5, 0.5, 31.5),
         new Vec3(0.5, 0.5, 31.5)
      );
      ProgressiveBlockGeneration progress = new ProgressiveBlockGeneration(4096L);
      Set<BlockPos> actual = PrismGenerator.generateQuad(
         base, new Vec3(0.0, 7.0, 0.0), FillMode.SOLID, 100_000, progress
      );
      progress.complete();

      HashSet<BlockPos> published = new HashSet<>();
      for (ProgressiveBlockGeneration.SectionBatch batch : progress.drainPublished()) {
         published.addAll(batch.blocks());
      }
      assertEquals(actual, published);
      assertEquals(actual.size(), progress.snapshot().generated());
      assertTrue(progress.snapshot().complete());
   }

   @Test
   void cancellationInterruptsObservedGenerationBeforeMoreTargetsAreAccepted() {
      ProgressiveBlockGeneration progress = new ProgressiveBlockGeneration(100L);
      progress.cancel();
      assertThrows(
         CancellationException.class,
         () -> LineGenerator.generate(new BlockPos(0, 0, 0), new BlockPos(100, 20, 5), 1000, progress)
      );
   }

   @Test
   void cancellationReleasesAProducerBlockedByThePublishedWatermark() throws Exception {
      ProgressiveBlockGeneration progress = new ProgressiveBlockGeneration(300_000L);
      CompletableFuture<Void> producer = CompletableFuture.runAsync(() -> {
         for (int index = 0; index < 300_000; index++) {
            progress.onGenerated(new BlockPos(index, 0, 0));
         }
      });

      Thread.sleep(100L);
      progress.cancel();

      CompletionException failure = assertThrows(CompletionException.class, producer::join);
      assertTrue(failure.getCause() instanceof CancellationException);
      assertTrue(progress.drainPublished().isEmpty());
   }

   @Test
   void consumerCanDrainWhileProducerWaitsAtTheWatermark() throws Exception {
      ProgressiveBlockGeneration progress = new ProgressiveBlockGeneration(300_000L);
      AtomicBoolean producerDone = new AtomicBoolean();
      CompletableFuture<Void> producer = CompletableFuture.runAsync(() -> {
         for (int index = 0; index < 300_000; index++) {
            progress.onGenerated(new BlockPos(index, 0, 0));
         }
         progress.complete();
         producerDone.set(true);
      });

      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L);
      while (!producerDone.get() && System.nanoTime() < deadline) {
         progress.drainPublished();
         Thread.yield();
      }
      assertTrue(producerDone.get(), "producer did not pass the watermark before the deadline");
      producer.get(5, TimeUnit.SECONDS);
      progress.drainPublished();
      assertTrue(progress.snapshot().complete());
   }

   @Test
   void releasePublishedDropsQueuedProgressAfterFinalTargetsAreOwned() {
      ProgressiveBlockGeneration progress = new ProgressiveBlockGeneration(5000L);
      for (int index = 0; index < 4096; index++) {
         progress.onGenerated(new BlockPos(index, 0, 0));
      }
      progress.releasePublished();

      assertTrue(progress.drainPublished().isEmpty());
   }

   @Test
   void countOnlyModeNeverRetainsPreviewBatches() {
      ProgressiveBlockGeneration progress = new ProgressiveBlockGeneration(100_000L, false);
      for (int index = 0; index < 100_000; index++) {
         progress.onGenerated(new BlockPos(index, 0, 0));
      }
      progress.complete();

      assertEquals(100_000L, progress.snapshot().generated());
      assertTrue(progress.drainPublished().isEmpty());
      assertTrue(progress.snapshot().complete());
   }

   @Test
   void publishedSectionStoresPackedPositionsBehindTheLegacyView() {
      ProgressiveBlockGeneration progress = new ProgressiveBlockGeneration(10L);
      BlockPos first = new BlockPos(2, 3, 4);
      progress.onGenerated(first);
      progress.complete();

      ProgressiveBlockGeneration.SectionBatch batch = progress.drainPublished().getFirst();
      assertEquals(1L, batch.packedBlockCount());
      assertEquals(first, batch.blocks().getFirst());
      assertEquals(1, batch.blocks().size());
   }
}
