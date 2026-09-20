package io.github.fastformer.fastplace.task;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class ClientWorkspacePlacementTaskTest {
   @Test
   void writesSupportingBlocksBeforeShortGrass() {
      BlockPos grass = new BlockPos(0, 5, 0);
      BlockPos dirt = grass.below();
      // Input order is intentionally unsafe. Submission maps do not promise it.
      assertEquals(List.of(dirt, grass), ClientWorkspacePlacementTask.orderedPositions(List.of(grass, dirt)));
   }
}
