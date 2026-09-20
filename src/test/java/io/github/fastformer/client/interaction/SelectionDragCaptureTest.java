package io.github.fastformer.client.interaction;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.client.operation.model.ClientSelectionPart;
import io.github.fastformer.client.operation.workspace.ClientOperationWorkspace;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class SelectionDragCaptureTest {
   @Test
   void transformKeepsCaptureButReplacementWithSameNumberDoesNot() {
      var workspace = populated();
      UUID owner = UUID.randomUUID();
      var capture = SelectionDragCapture.create(owner, workspace, workspace.parts(), 0, 1);
      workspace.beginEdit();
      workspace.updatePart(workspace.part(1).orElseThrow().withTranslation(new BlockPos(3, 0, 0)));
      workspace.finishEdit();
      assertTrue(capture.matches(owner, workspace, 1));
      workspace.selectOnly(1);
      workspace.removeSelectedParts();
      assertFalse(capture.matches(owner, workspace, 1));
      workspace.addParts(List.of(ClientSelectionPart.empty(ClientSelectionPart.Source.CLIPBOARD)));
      assertTrue(workspace.part(1).isPresent());
      assertFalse(capture.matches(owner, workspace, 1));
   }

   @Test
   void ownerAndGestureMustBothMatch() {
      var workspace = populated();
      UUID owner = UUID.randomUUID();
      var capture = SelectionDragCapture.create(owner, workspace, workspace.parts(), 2, 8);
      assertEquals(2, capture.mouseButton());
      assertTrue(capture.matches(owner, workspace, 8));
      assertFalse(capture.matches(UUID.randomUUID(), workspace, 8));
      assertFalse(capture.matches(owner, workspace, 9));
      assertFalse(capture.matches(owner, workspace, 0));
      assertThrows(UnsupportedOperationException.class, () -> capture.targets().clear());
   }

   @Test
   void groupCaptureFailsWhenAnyTargetDisappears() {
      var workspace = populated();
      workspace.addParts(List.of(ClientSelectionPart.empty(ClientSelectionPart.Source.CLIPBOARD)));
      UUID owner = UUID.randomUUID();
      var capture = SelectionDragCapture.create(owner, workspace, workspace.parts(), 1, 2);
      workspace.selectOnly(2);
      workspace.removeSelectedParts();
      assertFalse(capture.matches(owner, workspace, 2));
   }

   private static ClientOperationWorkspace populated() {
      var workspace = new ClientOperationWorkspace();
      workspace.addParts(List.of(ClientSelectionPart.empty(ClientSelectionPart.Source.CLIPBOARD)));
      return workspace;
   }
}
