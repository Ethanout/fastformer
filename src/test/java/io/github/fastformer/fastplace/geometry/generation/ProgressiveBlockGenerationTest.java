package io.github.fastformer.fastplace.geometry.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fastformer.fastplace.FillMode;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class ProgressiveBlockGenerationTest {
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
}
