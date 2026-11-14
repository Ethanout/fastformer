package io.github.fastformer.client.operation.edit;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.client.operation.selection.ClientSelectionSession;
import io.github.fastformer.client.operation.selection.SelectionCaptureFixture;
import io.github.fastformer.client.operation.submission.OperationSubmissions;
import io.github.fastformer.fastplace.selection.OperationSelectionMode;
import io.github.fastformer.protocol.s2c.operation.OperationPreviewPayload;
import io.github.fastformer.testkit.SubmissionFixture;
import io.github.fastformer.workspace.model.ClientSelectionPart;
import io.github.fastformer.workspace.model.WorkspaceTransform;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class SelectionDeletionsTest {
   @Test
   void deletingAnUnfinishedPointScansThePreviewAndCompletesAnEmptyRegionLocally() {
      var editor = new Editor();
      editor.point();
      assertTrue(editor.deletions.delete(new BlockPos(1, 1, 1)));
      assertTrue(editor.deletions.pending());
      assertTrue(editor.session.retained());
      editor.deletions.tick(Long.MAX_VALUE);
      assertEquals(8, editor.scan.reads.size());
      assertFalse(editor.session.hasDraft());
      assertFalse(editor.deletions.pending());
      assertTrue(editor.ports.prepared.isEmpty());
      assertTrue(editor.failures.isEmpty());
   }

   @Test
   void cancelAndWorldChangeNeverCompleteOrClearTheDraft() {
      var cancelled = new Editor();
      cancelled.point();
      assertTrue(cancelled.deletions.delete(BlockPos.ZERO));
      cancelled.deletions.cancel();
      cancelled.deletions.tick(Long.MAX_VALUE);
      assertTrue(cancelled.session.hasDraft());
      assertTrue(cancelled.scan.reads.isEmpty());

      var changed = new Editor();
      changed.point();
      assertTrue(changed.deletions.delete(BlockPos.ZERO));
      changed.scan.changeWorld();
      changed.deletions.tick(Long.MAX_VALUE);
      assertFalse(changed.deletions.pending());
      assertTrue(changed.session.hasDraft());
      assertTrue(changed.ports.prepared.isEmpty());
   }

   @Test
   void changedDraftAndLostOwnerInvalidateTheScanBaseline() {
      var changed = new Editor();
      changed.point();
      assertTrue(changed.deletions.delete(BlockPos.ZERO));
      changed.session.clearDraft();
      changed.deletions.tick(Long.MAX_VALUE);
      assertTrue(changed.scan.reads.isEmpty());
      assertFalse(changed.deletions.pending());

      var left = new Editor();
      left.point();
      assertTrue(left.deletions.delete(BlockPos.ZERO));
      left.available.set(false);
      left.deletions.tick(Long.MAX_VALUE);
      assertTrue(left.scan.reads.isEmpty());
      assertTrue(left.session.hasDraft());
   }

   @Test
   void unloadedWorldReportsFailureAndKeepsTheDraftEditable() {
      var editor = new Editor();
      editor.point();
      editor.scan.loaded = false;
      assertTrue(editor.deletions.delete(BlockPos.ZERO));
      editor.deletions.tick(Long.MAX_VALUE);
      assertEquals(List.of("fastformer.message.operation_capture_failed"), editor.failures);
      assertTrue(editor.session.hasDraft());
      assertFalse(editor.deletions.pending());
      assertFalse(editor.session.workspace().locked());
   }

   @Test
   void deletingASelectedClipboardPartKeepsTheOtherEditorAndUnselectedPart() {
      var editor = new Editor();
      var other = new Editor();
      editor.session.workspace().addParts(List.of(part(1), part(2)));
      other.session.workspace().addParts(List.of(part(1)));
      editor.session.workspace().selectOnly(1);
      assertTrue(editor.deletions.delete(null));
      assertEquals(List.of(2), editor.session.workspace().parts().stream().map(ClientSelectionPart::id).toList());
      assertEquals(1, other.session.workspace().size());
      assertTrue(editor.ports.prepared.isEmpty());
   }

   @Test
   void unavailableOrOpenEditPreventsDeletionAndScanning() {
      var editor = new Editor();
      editor.point();
      editor.available.set(false);
      assertFalse(editor.deletions.delete(BlockPos.ZERO));
      editor.available.set(true);
      editor.session.workspace().addParts(List.of(part(1)));
      assertTrue(editor.session.workspace().beginEdit());
      assertFalse(editor.deletions.delete(BlockPos.ZERO));
      assertFalse(editor.deletions.pending());
      assertTrue(editor.session.hasDraft());
   }

   private static ClientSelectionPart part(int id) {
      return new ClientSelectionPart(id, ClientSelectionPart.Source.CLIPBOARD, null, Map.of(), WorkspaceTransform.IDENTITY, false);
   }

   private static final class Editor {
      final ClientSelectionSession session = new ClientSelectionSession();
      final AtomicBoolean available = new AtomicBoolean(true);
      final SubmissionFixture ports = new SubmissionFixture();
      final SelectionCaptureFixture scan = new SelectionCaptureFixture();
      final List<String> failures = new ArrayList<>();
      final SelectionEdits edits = new SelectionEdits(session, OperationPreviewPayload::inactive, () -> false,
         () -> false, available::get, () -> {}, () -> {}, () -> {}, failures::add);
      final OperationSubmissions submissions = new OperationSubmissions(session, () -> null, scan.capture::pending,
         () -> ports, ports, new OperationSubmissions.Effects(session::clearLiveInteraction,
            () -> {}, () -> {}, ignored -> {}, failures::add, ignored -> {}, () -> {}));
      final SelectionDeletions deletions = new SelectionDeletions(session, () -> edits, () -> submissions,
         available::get, failures::add, scan.capture);

      void point() {
         session.setSelectionMode(OperationSelectionMode.CUBOID);
         assertTrue(edits.handleCreateClick(1, BlockPos.ZERO));
      }
   }
}
