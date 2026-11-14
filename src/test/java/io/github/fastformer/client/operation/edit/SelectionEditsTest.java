package io.github.fastformer.client.operation.edit;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.client.operation.selection.ClientSelectionSession;
import io.github.fastformer.common.selection.OperationSelectionMode;
import io.github.fastformer.protocol.s2c.operation.OperationPreviewPayload;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class SelectionEditsTest {
   @Test
   void independentPointEditsAndUndoKeepEachSessionAndHistoryIsolated() {
      var first = new ClientSelectionSession();
      var second = new ClientSelectionSession();
      var empty = new AtomicInteger();
      var edits = edits(first, () -> false, () -> true, empty);
      var other = edits(second, () -> false, () -> true, new AtomicInteger());
      assertTrue(edits.handleCreateClick(1, BlockPos.ZERO));
      assertEquals(List.of(BlockPos.ZERO), edits.selectionDraft().points());
      assertFalse(other.selectionDraftActive());
      assertTrue(edits.handleCreateClick(0, null));
      assertFalse(edits.selectionDraftActive());
      assertEquals(1, empty.get());
      assertTrue(edits.undo());
      assertEquals(List.of(BlockPos.ZERO), edits.selectionDraft().points());
      assertFalse(other.undo());
      assertFalse(other.selectionDraftActive());
   }

   @Test
   void frozenBaselineDetectsDraftChangesAndLeavingItsOwner() {
      var session = new ClientSelectionSession();
      var current = new AtomicBoolean(true);
      var edits = edits(session, () -> false, current::get, new AtomicInteger());
      var unchanged = edits.unchanged();
      assertTrue(unchanged.getAsBoolean());
      assertTrue(edits.handleCreateClick(1, BlockPos.ZERO));
      assertFalse(unchanged.getAsBoolean());
      var withPoint = edits.unchanged();
      assertTrue(withPoint.getAsBoolean());
      current.set(false);
      assertFalse(withPoint.getAsBoolean());
   }

   @Test
   void pendingSubmissionRejectsSelectionMutationWithoutAddingHistory() {
      var session = new ClientSelectionSession();
      var edits = edits(session, () -> true, () -> true, new AtomicInteger());
      assertFalse(edits.handleCreateClick(1, BlockPos.ZERO));
      assertFalse(edits.cancelLastSelection());
      assertFalse(edits.removeSelectedParts());
      assertFalse(edits.fixActiveSelection());
      assertFalse(edits.undo());
      assertTrue(session.workspace().isEmpty());
      assertEquals(0, session.workspace().undoSize());
      assertFalse(edits.capturePending());
   }

   private static SelectionEdits edits(ClientSelectionSession session, java.util.function.BooleanSupplier pending,
      java.util.function.BooleanSupplier current, AtomicInteger empty) {
      session.setSelectionMode(OperationSelectionMode.CUBOID);
      return new SelectionEdits(session, OperationPreviewPayload::inactive, () -> false, pending, current,
         () -> session.workspace().cancelEdit(), () -> {}, empty::incrementAndGet, key -> fail(key));
   }
}
