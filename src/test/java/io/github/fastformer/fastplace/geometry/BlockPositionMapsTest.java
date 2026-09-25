package io.github.fastformer.fastplace.geometry;

import org.junit.jupiter.api.Test;
import net.minecraft.core.BlockPos;
import static org.junit.jupiter.api.Assertions.*;

class BlockPositionMapsTest {
   @Test
   void copyDoesNotShareMutablePositionsOrMapEntries() {
      BlockPos.MutableBlockPos key = new BlockPos.MutableBlockPos(1, 2, 3);
      java.util.Map<BlockPos, String> source = new java.util.LinkedHashMap<>();
      source.put(key, "before");
      java.util.Map<BlockPos, String> frozen = BlockPositionMaps.copyOf(source);
      key.set(4, 5, 6);
      source.clear();
      assertEquals(java.util.Map.of(new BlockPos(1, 2, 3), "before"), frozen);
      assertThrows(UnsupportedOperationException.class, () -> frozen.put(BlockPos.ZERO, "after"));
      assertThrows(UnsupportedOperationException.class, () -> frozen.entrySet().iterator().next().setValue("after"));
      assertThrows(UnsupportedOperationException.class, () -> frozen.keySet().clear());
   }

   @Test
   void nullValuesRemainInvalid() {
      java.util.Map<BlockPos, String> source = new java.util.LinkedHashMap<>();
      source.put(BlockPos.ZERO, null);
      assertThrows(NullPointerException.class, () -> BlockPositionMaps.copyOf(source));
   }

   @Test void completedScanTransfersImmutableOwnershipWithoutAnotherFullCopy() {
      var builder = new BlockPositionMaps.Builder<String>();
      var position = new BlockPos.MutableBlockPos(1, 2, 3);
      builder.put(position, "block");
      position.set(9, 9, 9);
      var frozen = builder.build();
      assertEquals("block", frozen.get(new BlockPos(1, 2, 3)));
      assertSame(frozen, BlockPositionMaps.copyOf(frozen));
      assertThrows(IllegalStateException.class, () -> builder.put(BlockPos.ZERO, "late"));
      assertThrows(UnsupportedOperationException.class, () -> frozen.entrySet().iterator().next().setValue("changed"));
      assertThrows(UnsupportedOperationException.class, () -> frozen.remove(new BlockPos(1, 2, 3)));
   }
}
