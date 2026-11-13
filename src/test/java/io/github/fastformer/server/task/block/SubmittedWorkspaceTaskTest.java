package io.github.fastformer.server.task.block;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class SubmittedWorkspaceTaskTest {
   @Test
   void writesSupportingBlocksBeforeShortGrass() {
      BlockPos grass = new BlockPos(0, 5, 0);
      BlockPos dirt = grass.below();
      // Input order is intentionally unsafe. Submission maps do not promise it.
      assertEquals(List.of(dirt, grass), SubmittedWorkspaceTask.orderedPositions(List.of(grass, dirt)));
   }
}
