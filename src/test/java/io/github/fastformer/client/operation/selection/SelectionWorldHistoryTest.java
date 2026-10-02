package io.github.fastformer.client.operation.selection;

import static org.junit.jupiter.api.Assertions.*;
import io.github.fastformer.fastplace.selection.OperationSelectionVolume;
import io.github.fastformer.network.payload.world.WorldHistoryEventPayload.Kind;
import io.github.fastformer.workspace.model.ClientSelectionPart;
import io.github.fastformer.workspace.model.WorkspaceTransform;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class SelectionWorldHistoryTest {
   @Test void worldUndoAndRedoDoNotRestoreCommittedSelections() {
      var session = session();
      session.onWorldHistory(Kind.RECORD);
      session.clearLiveInteraction();
      for (Kind kind : List.of(Kind.UNDO, Kind.REDO, Kind.UNDO)) {
         session.onWorldHistory(kind);
         assertTrue(session.workspace().isEmpty());
         assertFalse(session.retained());
         assertFalse(session.hasDraft());
         assertEquals(0, session.workspace().undoSize());
      }
   }

   @Test void worldReplayDoesNotReplaceAnUnsubmittedSelection() {
      var session = session();
      var before = session.workspace().parts();
      var selected = session.workspace().selectedIds();
      session.onWorldHistory(Kind.UNDO);
      session.onWorldHistory(Kind.REDO);
      assertEquals(before, session.workspace().parts());
      assertEquals(selected, session.workspace().selectedIds());
   }

   @Test void recordingAWorldEditClearsEarlierLocalUndo() {
      var session = session();
      var workspace = session.workspace();
      var original = workspace.parts().getFirst();
      workspace.beginEdit();
      workspace.updatePart(original.withTranslation(new BlockPos(4, 0, 0)));
      workspace.finishEdit();
      assertTrue(workspace.undoSize() > 0);
      session.onWorldHistory(Kind.RECORD);
      assertEquals(0, workspace.undoSize());
      assertEquals(new net.minecraft.world.phys.Vec3(4, 0, 0), workspace.parts().getFirst().transform().translation());
   }

   private static ClientSelectionSession session() {
      var session = new ClientSelectionSession();
      session.workspace().addParts(List.of(new ClientSelectionPart(1, ClientSelectionPart.Source.WORLD,
         OperationSelectionVolume.cuboid(BlockPos.ZERO, new BlockPos(2, 2, 2), BlockPos.ZERO, new BlockPos(2, 2, 2)),
         Map.of(), WorkspaceTransform.IDENTITY, false)));
      return session;
   }
}
