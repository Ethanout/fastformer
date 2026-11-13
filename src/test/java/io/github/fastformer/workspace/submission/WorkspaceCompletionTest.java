package io.github.fastformer.workspace.submission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class WorkspaceCompletionTest {
   @Test
   void snapshotCannotBeChangedByTheTaskAfterReporting() {
      var ids = new ArrayList<>(List.of(3));
      var mutable = new BlockPos.MutableBlockPos(1, 2, 3);
      var targets = new ArrayList<BlockPos>(List.of(mutable));
      var result = new WorkspaceCompletion(UUID.randomUUID(), ResourceLocation.withDefaultNamespace("overworld"),
         UUID.randomUUID(), false, false, true, ids, targets, WorkspaceFailure.UNKNOWN);
      ids.clear();
      targets.clear();
      mutable.set(4, 5, 6);
      assertEquals(List.of(3), result.failedIds());
      assertEquals(List.of(new BlockPos(1, 2, 3)), result.failedTargets());
      assertThrows(UnsupportedOperationException.class, () -> result.failedTargets().clear());
   }
}
