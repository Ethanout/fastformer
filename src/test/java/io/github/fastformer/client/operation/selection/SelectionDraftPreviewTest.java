package io.github.fastformer.client.operation.selection;

import static org.junit.jupiter.api.Assertions.*;
import io.github.fastformer.fastplace.selection.OperationSelectionMode;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

class SelectionDraftPreviewTest {
   @Test void eitherPointRoleCombinesWithTheCandidateForDeletion() {
      var point = new BlockPos(1, 2, 3);
      var candidate = new BlockPos(5, 6, 7);
      for (boolean second : List.of(false, true)) {
         var draft = new ClientSelectionSession.DraftState(OperationSelectionMode.CUBOID, List.of(point), 0, point, point, second);
         assertEquals(new AABB(1, 2, 3, 6, 7, 8), SelectionDraftPreview.volume(draft, candidate).bounds());
         assertEquals(new AABB(point), SelectionDraftPreview.volume(draft, point).bounds());
         assertEquals(new AABB(point), SelectionDraftPreview.volume(draft, null).bounds());
      }
   }
}
