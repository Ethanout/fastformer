package io.github.fastformer.fastplace.geometry.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fastformer.fastplace.FillMode;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class CompoundGeneratorLimitTest {
   @Test
   void sweepReportsRatherThanSilentlyTruncatesAtTheLimit() {
      List<BlockPos> points = List.of(
         BlockPos.ZERO,
         new BlockPos(2, 0, 0),
         new BlockPos(0, 0, 0),
         new BlockPos(0, 0, 5)
      );
      Set<BlockPos> complete = SweepGenerator.generate(points, FillMode.SOLID, 10000);

      assertFalse(complete.isEmpty());
      assertTrue(complete instanceof DrainingBlockSet);
      assertEquals(complete, SweepGenerator.generate(points, FillMode.SOLID, complete.size()));
      assertTrue(GenerationLimitExceeded.is(
         SweepGenerator.generate(points, FillMode.SOLID, complete.size() - 1)
      ));
   }

   @Test
   void loftReportsRatherThanSilentlyTruncatesAtTheLimit() {
      List<BlockPos> points = List.of(
         new BlockPos(0, 0, 0),
         new BlockPos(0, 4, 0),
         new BlockPos(6, 0, 0),
         new BlockPos(6, 4, 0)
      );
      Set<BlockPos> complete = LoftGenerator.generate(points, 10000);

      assertFalse(complete.isEmpty());
      assertTrue(complete instanceof DrainingBlockSet);
      assertEquals(complete, LoftGenerator.generate(points, complete.size()));
      assertTrue(GenerationLimitExceeded.is(LoftGenerator.generate(points, complete.size() - 1)));
   }

   @Test
   void zeroBudgetStillCarriesAnExplicitLimitStatus() {
      Set<BlockPos> limited = LoftGenerator.generate(
         List.of(BlockPos.ZERO, new BlockPos(1, 0, 0)),
         0
      );

      assertTrue(limited.isEmpty());
      assertTrue(GenerationLimitExceeded.is(limited));
      assertEquals(
         BlockGenerationResult.Status.LIMIT_EXCEEDED,
         BlockGenerationResult.fromLegacy(limited).status()
      );
   }

   @Test
   void typedCompoundResultsPreserveLimitStatus() {
      List<BlockPos> points = List.of(
         BlockPos.ZERO,
         new BlockPos(2, 0, 0),
         new BlockPos(0, 0, 0),
         new BlockPos(0, 0, 5)
      );

      BlockGenerationResult sweep = SweepGenerator.generateResult(points, FillMode.SOLID, 1);
      BlockGenerationResult loft = LoftGenerator.generateResult(points, 1);

      assertEquals(BlockGenerationResult.Status.LIMIT_EXCEEDED, sweep.status());
      assertEquals(BlockGenerationResult.Status.LIMIT_EXCEEDED, loft.status());
      assertTrue(sweep.blocks().isEmpty());
      assertTrue(loft.blocks().isEmpty());
   }
}
