package io.github.fastformer.client.operation.input;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.fastformer.client.operation.input.SelectionConfirmation.Action.*;
import io.github.fastformer.client.operation.selection.ClientSelectionSession;
import io.github.fastformer.client.operation.workspace.ClientOperationWorkspace;
import io.github.fastformer.fastplace.selection.OperationSelectionMode;
import io.github.fastformer.fastplace.selection.OperationSelectionVolume;
import io.github.fastformer.workspace.model.ClientSelectionPart;
import io.github.fastformer.workspace.model.WorkspaceTransform;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class SelectionConfirmationTest {
   @Test void bothEnterShortcutsSubmitAllRegardlessOfSelectionOrDraftState() {
      var workspace = new ClientOperationWorkspace();
      assertEquals(SUBMIT_ALL, SelectionConfirmation.decide(workspace, false));
      var volume = OperationSelectionVolume.create(OperationSelectionMode.CUBOID, List.of(BlockPos.ZERO),
         0, BlockPos.ZERO, BlockPos.ZERO, 0);
      workspace.addParts(List.of(new ClientSelectionPart(0, ClientSelectionPart.Source.WORLD, volume,
         Map.of(), WorkspaceTransform.IDENTITY, false)));
      assertEquals(SUBMIT_ALL, SelectionConfirmation.decide(workspace, false));
      assertEquals(SUBMIT_ALL, SelectionConfirmation.decide(workspace, false));
      workspace.beginEdit();
      workspace.updatePart(workspace.parts().getFirst().fixed());
      workspace.finishEdit();
      assertEquals(SUBMIT_ALL, SelectionConfirmation.decide(workspace, false));
      workspace.toggleSelected(workspace.activeId());
      assertEquals(SUBMIT_ALL, SelectionConfirmation.decide(workspace, false));
      workspace.selections().setDraft(new ClientSelectionSession.DraftState(OperationSelectionMode.CUBOID,
         List.of(BlockPos.ZERO), 0, BlockPos.ZERO, BlockPos.ZERO));
      assertEquals(SUBMIT_ALL, SelectionConfirmation.decide(workspace, false));
      assertEquals(SUBMIT_ALL, SelectionConfirmation.decide(workspace, false));
      assertEquals(BLOCKED, SelectionConfirmation.decide(workspace, true));
      workspace.setLocked(true);
      assertEquals(BLOCKED, SelectionConfirmation.decide(workspace, false));
   }
}
