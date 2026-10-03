package io.github.fastformer.client.controlpoint;

import io.github.fastformer.client.operation.selection.ClientSelectionSession;
import io.github.fastformer.fastplace.geometry.controlpoint.ControlPointRole;
import io.github.fastformer.fastplace.selection.OperationSelectionMode;
import java.util.List;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ControlPointPresentationTest {
   @Test void cuboidCandidatesFillTheMissingRoleWithoutHoverInteraction() {
      for (boolean secondOnly : new boolean[]{false, true}) {
         var draft = new ClientSelectionSession.DraftState(OperationSelectionMode.CUBOID,
            List.of(BlockPos.ZERO), 0, null, null, secondOnly);
         var points = ControlPointPresentation.draft(draft, new BlockPos(2, 3, 4));
         assertEquals(2, points.size());
         assertEquals(secondOnly ? ControlPointRole.SECONDARY : ControlPointRole.PRIMARY, points.get(0).role());
         assertEquals(secondOnly ? ControlPointRole.PRIMARY : ControlPointRole.SECONDARY, points.get(1).role());
         assertTrue(points.get(0).confirmed());
         assertFalse(points.get(1).confirmed());
         assertFalse(points.get(0).feedback().changesOnHover());
         assertEquals(1, ControlPointPresentation.draft(draft, BlockPos.ZERO).size());
      }
   }
}
