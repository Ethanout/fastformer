package io.github.fastformer.client.render;

import io.github.fastformer.client.render.PreviewBlockOcclusion;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

class PreviewBlockOcclusionTest {
   @Test
   void onlyConfirmedPreviewNeighborsAreOcclusionCandidates() {
      BlockPos first = BlockPos.ZERO;
      BlockPos second = first.east();
      Set<BlockPos> blocks = Set.of(first, second);

      assertTrue(PreviewBlockOcclusion.hasPreviewNeighbor(blocks, first, Direction.EAST));
      assertTrue(PreviewBlockOcclusion.hasPreviewNeighbor(blocks, second, Direction.WEST));
      assertFalse(PreviewBlockOcclusion.hasPreviewNeighbor(blocks, first, Direction.UP));
      assertFalse(PreviewBlockOcclusion.hasPreviewNeighbor(blocks, second, Direction.DOWN));
   }

   @Test
   void aMissingPreviewNeighborNeverLetsTheWorldHideTheFace() {
      BlockPos pos = BlockPos.ZERO;
      Set<BlockPos> blocks = Set.of(pos);

      for (Direction direction : Direction.values()) {
         assertFalse(PreviewBlockOcclusion.hasPreviewNeighbor(blocks, pos, direction));
      }
   }

}
