package io.github.fastformer.fastplace.geometry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class BlockPositionMapsTest {
   @Test
   void copyDoesNotShareMutablePositionsOrMapEntries() {
      BlockPos.MutableBlockPos key = new BlockPos.MutableBlockPos(1, 2, 3);
      Map<BlockPos, String> source = new LinkedHashMap<>();
      source.put(key, "before");
      Map<BlockPos, String> frozen = BlockPositionMaps.copyOf(source);

      key.set(4, 5, 6);
      source.clear();

      assertEquals(Map.of(new BlockPos(1, 2, 3), "before"), frozen);
      assertThrows(UnsupportedOperationException.class, () -> frozen.put(BlockPos.ZERO, "after"));
      assertThrows(UnsupportedOperationException.class, () -> frozen.entrySet().iterator().next().setValue("after"));
      assertThrows(UnsupportedOperationException.class, () -> frozen.keySet().clear());
   }

   @Test
   void nullValuesRemainInvalid() {
      Map<BlockPos, String> source = new LinkedHashMap<>();
      source.put(BlockPos.ZERO, null);
      assertThrows(NullPointerException.class, () -> BlockPositionMaps.copyOf(source));
   }
}
