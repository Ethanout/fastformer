package io.github.fastformer.client.operation.selection;

import static org.junit.jupiter.api.Assertions.*;
import io.github.fastformer.fastplace.selection.OperationSelectionMode;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SelectionToolMemoryTest {
   @AfterEach void reset() { SelectionToolPreference.set(OperationSelectionMode.CUBOID); }

   @Test void worldHistoryKeepsTheCurrentToolAndDoesNotRestoreTheSubmittedGroup() throws Exception {
      SelectionToolPreference.set(OperationSelectionMode.SMART);
      var session = new ClientSelectionSession();
      session.startNewSession();
      assertEquals(OperationSelectionMode.SMART, session.selectionMode());
      SmartSelectionEdits.add(session.workspace(), SmartSelectionEditsTest.row(0, 2));
      session.onWorldHistory(io.github.fastformer.network.payload.world.WorldHistoryEventPayload.Kind.RECORD);
      session.clearLiveInteraction();
      SelectionToolPreference.set(OperationSelectionMode.CUBOID);
      session.startNewSession();
      assertEquals(OperationSelectionMode.CUBOID, session.selectionMode());
      session.onWorldHistory(io.github.fastformer.network.payload.world.WorldHistoryEventPayload.Kind.UNDO);
      assertEquals(OperationSelectionMode.CUBOID, session.selectionMode());
      assertTrue(session.workspace().isEmpty());
      assertEquals(OperationSelectionMode.CUBOID, SelectionToolPreference.get());
      session.onWorldHistory(io.github.fastformer.network.payload.world.WorldHistoryEventPayload.Kind.REDO);
      assertTrue(session.workspace().isEmpty());
      session.startNewSession();
      assertEquals(OperationSelectionMode.CUBOID, session.selectionMode());
   }

   @Test void restoredCuboidDraftDoesNotBecomeSmartWhenPreferenceDiffers() {
      SelectionToolPreference.set(OperationSelectionMode.SMART);
      var session = new ClientSelectionSession();
      var point = new net.minecraft.core.BlockPos(2, 3, 4);
      session.restoreDraftState(new ClientSelectionSession.DraftState(OperationSelectionMode.CUBOID, List.of(point), 0, null, null, true));
      session.startNewSession();
      assertEquals(OperationSelectionMode.CUBOID, session.selectionMode());
      assertEquals(point, session.draftState().secondPoint());
      session.clearLiveInteraction();
      session.startNewSession();
      assertEquals(OperationSelectionMode.SMART, session.selectionMode());
   }
}
