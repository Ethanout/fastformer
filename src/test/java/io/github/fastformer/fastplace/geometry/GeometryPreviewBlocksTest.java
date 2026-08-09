package io.github.fastformer.fastplace.geometry;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class GeometryPreviewBlocksTest {
   @Test
   void fastBuildingLayersNeverClipConfirmedBlocksToTheNewCandidateShape() {
      BlockPos first = new BlockPos(0, 0, 0);
      BlockPos confirmedOnly = new BlockPos(1, 0, 0);
      BlockPos shared = new BlockPos(2, 0, 0);
      BlockPos pendingOnly = new BlockPos(3, 0, 0);

      GeometryPreviewBlocks.Layers layers = GeometryPreviewBlocks.layersPreservingConfirmed(
         Set.of(first, confirmedOnly, shared),
         Set.of(first, shared, pendingOnly)
      );

      assertEquals(Set.of(first, confirmedOnly, shared), layers.confirmed());
      assertEquals(Set.of(pendingOnly), layers.pending());
   }

   @Test
   void saturatedDetailedPreviewFallsBackInsteadOfReturningATruncatedShape() {
      Set<BlockPos> fallback = Set.of(new BlockPos(99, 99, 99));

      Set<BlockPos> result = GeometryPreviewBlocks.generatedOrFallback(
         4,
         4,
         () -> positions(4),
         () -> fallback
      );

      assertEquals(fallback, result);
   }

   private static Set<BlockPos> positions(int count) {
      LinkedHashSet<BlockPos> result = new LinkedHashSet<>();
      for (int index = 0; index < count; index++) {
         result.add(new BlockPos(index, 0, 0));
      }
      return result;
   }
}
