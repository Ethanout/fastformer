package io.github.fastformer.fastplace.geometry.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class ObservedBlockSetTest {
   @Test
   void storesPackedPositionsWithoutChangingOrderedSetBehavior() {
      List<BlockPos> generated = new ArrayList<>();
      long[] scanned = new long[1];
      ObservedBlockSet positions = new ObservedBlockSet(new BlockGenerationObserver() {
         @Override
         public void onScanned(long amount) {
            scanned[0] += amount;
         }

         @Override
         public void onGenerated(BlockPos position) {
            generated.add(position);
         }
      });
      BlockPos first = new BlockPos(10, 64, -4);
      BlockPos second = new BlockPos(-3, -20, 8);

      assertTrue(positions.add(first));
      assertTrue(positions.add(second));
      assertFalse(positions.add(new BlockPos(10, 64, -4)));

      assertEquals(3L, scanned[0]);
      assertEquals(List.of(first, second), generated);
      assertEquals(List.of(first, second), List.copyOf(positions));
      assertTrue(positions.contains(new BlockPos(-3, -20, 8)));

      Iterator<BlockPos> iterator = positions.iterator();
      assertEquals(first, iterator.next());
      iterator.remove();
      assertEquals(List.of(second), List.copyOf(positions));
   }

   @Test
   void drainingIteratorReleasesEachConsumedPosition() {
      ObservedBlockSet positions = new ObservedBlockSet(BlockGenerationObserver.NONE);
      BlockPos first = new BlockPos(1, 2, 3);
      BlockPos second = new BlockPos(4, 5, 6);
      positions.add(first);
      positions.add(second);

      Iterator<BlockPos> iterator = positions.drainingIterator();

      assertEquals(first, iterator.next());
      assertEquals(1, positions.size());
      assertEquals(second, iterator.next());
      assertTrue(positions.isEmpty());
   }

   @Test
   void readOnlyViewKeepsDrainingOwnershipWithoutCopying() {
      ObservedBlockSet positions = new ObservedBlockSet(BlockGenerationObserver.NONE);
      positions.add(new BlockPos(7, 8, 9));

      java.util.Set<BlockPos> readOnly = GeneratedBlockSets.readOnly(positions);
      Iterator<BlockPos> iterator = ((DrainingBlockSet)readOnly).drainingIterator();

      assertEquals(new BlockPos(7, 8, 9), iterator.next());
      assertTrue(readOnly.isEmpty());
      assertTrue(positions.isEmpty());
   }
}
