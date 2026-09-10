package io.github.fastformer.fastplace.geometry.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class BlockGenerationResultSourceTest {
   @Test
   void sourceResultDoesNotEagerlyIterateOrCopyPositions() {
      List<BlockPos> positions = List.of(BlockPos.ZERO, BlockPos.ZERO.east());
      AtomicInteger iterations = new AtomicInteger();
      BlockPositionSource source = new BlockPositionSource() {
         @Override
         public int size() {
            return positions.size();
         }

         @Override
         public Iterator<BlockPos> iterator() {
            iterations.incrementAndGet();
            return positions.iterator();
         }
      };

      BlockGenerationResult result = BlockGenerationResult.success(source);

      assertEquals(0, iterations.get());
      assertEquals(2, result.blocks().size());
      assertEquals(0, iterations.get());
      assertEquals(positions, List.copyOf(result.blocks()));
      assertEquals(1, iterations.get());
   }
}
