package io.github.fastformer.fastplace.geometry.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class WallGeneratorTest {
   @Test
   void closedTiltedExtrusionUsesOnlyPathLayersAndReportsLimit() {
      List<BlockPos> path = List.of(
         new BlockPos(0, 0, 0),
         new BlockPos(5, 0, 0),
         new BlockPos(5, 0, 4),
         new BlockPos(0, 0, 4)
      );
      BlockPos extrusion = new BlockPos(3, 6, -2);

      Set<BlockPos> result = WallGenerator.generate(path, true, extrusion, 10000);

      assertTrue(result.containsAll(LineGenerator.generate(path.getFirst(), path.get(1), 100)));
      assertTrue(result.stream().allMatch(position -> position.getY() >= 0 && position.getY() <= 6));
      assertEquals(result, WallGenerator.generate(path, true, extrusion, result.size()));
      assertTrue(GenerationLimitExceeded.is(
         WallGenerator.generate(path, true, extrusion, result.size() - 1)
      ));
      assertTrue(WallGenerator.estimateScanCells(path, true, extrusion) >= result.size());
   }

   @Test
   void resultBoundaryPreservesExplicitGenerationStatus() {
      List<BlockPos> path = List.of(new BlockPos(0, 0, 0), new BlockPos(8, 0, 0));

      BlockGenerationResult result = WallGenerator.generateResult(path, false, BlockPos.ZERO, 2);

      assertEquals(BlockGenerationResult.Status.LIMIT_EXCEEDED, result.status());
      assertTrue(result.blocks().isEmpty());
   }

}
