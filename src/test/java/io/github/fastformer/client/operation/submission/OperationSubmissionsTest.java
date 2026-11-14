package io.github.fastformer.client.operation.submission;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.client.operation.selection.ClientSelectionSession;
import io.github.fastformer.client.session.draft.ClientOperationDraft;
import io.github.fastformer.fastplace.selection.OperationSelectionMode;
import io.github.fastformer.protocol.s2c.workspace.OperationWorkspaceResultPayload;
import io.github.fastformer.testkit.SubmissionFixture;
import io.github.fastformer.workspace.model.ClientSelectionPart;
import io.github.fastformer.workspace.model.WorkspaceTransform;
import io.github.fastformer.workspace.submission.OperationSubmissionOutcome;
import io.github.fastformer.workspace.submission.OperationWorkspacePlan;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class OperationSubmissionsTest {
   @Test
   void duplicateSubmitCannotReplaceThePartialScopeOfAnInFlightRequest() {
      var fixture = new Editor();
      var ids = new HashSet<>(Set.of(1));
      var scope = new SubmissionScope.SelectionParts(ids, SubmissionScope.DraftDisposition.REMOVE);
      assertTrue(fixture.submit(scope).accepted());
      var transfer = fixture.submissions.transferId();
      ids.add(2);
      assertEquals(SubmissionActionResult.PENDING, fixture.submissions.submitWorkspace());
      assertEquals(transfer, fixture.submissions.transferId());

      fixture.submissions.applyResult(new OperationWorkspaceResultPayload(transfer, true, List.of()));

      assertEquals(List.of(2), fixture.session.workspace().parts().stream().map(ClientSelectionPart::id).toList());
      assertEquals(List.of(transfer), fixture.ports.acknowledged);
      assertFalse(fixture.submissions.pending());
      assertFalse(fixture.session.workspace().locked());
   }

   @Test
   void stalePreparationCannotSendUnlockOrAbortTheNextSubmission() {
      var fixture = new Editor();
      assertTrue(fixture.submit(new SubmissionScope.WholeWorkspace()).accepted());
      var old = fixture.ports.prepared.getFirst();
      fixture.submissions.cancel();
      assertTrue(fixture.submit(new SubmissionScope.WholeWorkspace()).accepted());
      var next = fixture.submissions.transferId();
      var sent = new AtomicInteger();

      old.complete().accept(sent::incrementAndGet);
      old.complete().accept(null);
      fixture.submissions.applyResult(new OperationWorkspaceResultPayload(old.transferId(), true, List.of()));

      assertEquals(0, sent.get());
      assertEquals(next, fixture.submissions.transferId());
      assertTrue(fixture.session.workspace().locked());
      assertTrue(fixture.ports.aborted.isEmpty());
      fixture.ports.prepared.getLast().complete().accept(sent::incrementAndGet);
      assertEquals(1, sent.get());
   }

   @Test
   void preparationRejectionAndSendFailureLeaveTheDraftEditable() {
      var fixture = new Editor();
      fixture.ports.admitted = false;
      assertEquals(SubmissionActionResult.STORAGE_FAILED, fixture.submit(new SubmissionScope.WholeWorkspace()));
      assertFalse(fixture.submissions.pending());
      assertFalse(fixture.session.workspace().locked());
      assertEquals(2, fixture.session.workspace().size());
      assertEquals(1, fixture.ports.aborted.size());

      fixture.ports.admitted = true;
      assertTrue(fixture.submit(new SubmissionScope.WholeWorkspace()).accepted());
      fixture.ports.prepared.getLast().complete().accept(() -> { throw new IllegalStateException("send"); });
      assertFalse(fixture.submissions.pending());
      assertFalse(fixture.session.workspace().locked());
      assertEquals(2, fixture.session.workspace().size());
      assertEquals(SubmissionActionResult.UNAVAILABLE.failureKey(), fixture.messages.getLast());
   }

   @Test
   void nonRetryableFullFailureClearsButPartialFailureRetainsEditableContent() {
      var full = new Editor();
      assertTrue(full.submit(new SubmissionScope.WholeWorkspace()).accepted());
      full.reject(false);
      assertTrue(full.session.workspace().isEmpty());
      assertEquals(List.of(OperationSubmissionOutcome.FAILED_NONRETRYABLE), full.ports.outcomes);

      var partial = new Editor();
      assertTrue(partial.submit(new SubmissionScope.SelectionParts(Set.of(1), SubmissionScope.DraftDisposition.KEEP)).accepted());
      partial.reject(false);
      assertEquals(2, partial.session.workspace().size());
      assertEquals(List.of(OperationSubmissionOutcome.FAILED_RETRYABLE), partial.ports.outcomes);
      assertFalse(partial.session.workspace().locked());
   }

   @Test
   void environmentLeavePreservesRemainderAndInvalidatesFrozenOwnership() {
      var fixture = new Editor();
      assertTrue(fixture.submit(new SubmissionScope.SelectionParts(Set.of(1), SubmissionScope.DraftDisposition.KEEP)).accepted());
      var remainder = fixture.remainder;
      fixture.submissions.leave();
      assertSame(remainder, fixture.remainder);
      assertFalse(fixture.ports.prepared.getFirst().owned().getAsBoolean());
      assertFalse(fixture.session.workspace().locked());
   }

   @Test
   void timeoutKeepsTheDraftAndRejectsTheLateResult() {
      var fixture = new Editor();
      assertTrue(fixture.submit(new SubmissionScope.WholeWorkspace()).accepted());
      var transfer = fixture.submissions.transferId();
      for (int tick = 0; tick < 600; tick++) fixture.submissions.tick();
      assertEquals(List.of(transfer), fixture.ports.expired);
      assertFalse(fixture.submissions.pending());
      fixture.submissions.applyResult(new OperationWorkspaceResultPayload(transfer, true, List.of()));
      assertEquals(2, fixture.session.workspace().size());
      assertTrue(fixture.ports.acknowledged.isEmpty());
   }

   @Test
   void localPartialCompletionKeepsAnUnsubmittedPointAndItsMode() {
      var fixture = new Editor();
      fixture.session.setSelectionMode(OperationSelectionMode.CUBOID);
      fixture.session.restoreDraftState(new ClientSelectionSession.DraftState(OperationSelectionMode.CUBOID,
         List.of(BlockPos.ZERO), 0, BlockPos.ZERO, BlockPos.ZERO, false));
      fixture.submissions.finish(new SubmissionScope.SelectionParts(Set.of(1, 2), SubmissionScope.DraftDisposition.KEEP));
      assertTrue(fixture.session.workspace().isEmpty());
      assertEquals(List.of(BlockPos.ZERO), fixture.session.draftState().points());
      assertTrue(fixture.session.retained());
   }

   @Test
   void localCompletionKeepsAnIdleDraftModeEvenWithoutRemainingPoints() {
      var fixture = new Editor();
      fixture.session.setSelectionMode(OperationSelectionMode.PRISM);
      fixture.submissions.finish(new SubmissionScope.SelectionParts(Set.of(1, 2), SubmissionScope.DraftDisposition.KEEP));
      assertEquals(OperationSelectionMode.PRISM, fixture.session.selectionMode());
      assertFalse(fixture.session.retained());
   }

   @Test
   void resultAndPreparationStayWithinTheOwningEditorAndConnection() {
      var first = new Editor();
      var second = new Editor();
      assertTrue(first.submit(new SubmissionScope.WholeWorkspace()).accepted());
      assertTrue(second.submit(new SubmissionScope.WholeWorkspace()).accepted());
      second.submissions.applyResult(new OperationWorkspaceResultPayload(first.submissions.transferId(), true, List.of()));
      assertTrue(second.submissions.pending());
      assertEquals(2, second.session.workspace().size());
      first.ports.current = false;
      var sent = new AtomicInteger();
      first.ports.prepared.getFirst().complete().accept(sent::incrementAndGet);
      assertEquals(0, sent.get());
      assertTrue(second.session.workspace().locked());
   }

   private static final class Editor {
      final ClientSelectionSession session = new ClientSelectionSession();
      final SubmissionFixture ports = new SubmissionFixture();
      final List<String> messages = new ArrayList<>();
      ClientOperationDraft.Remainder remainder;
      final OperationSubmissions submissions = new OperationSubmissions(session, () -> null, () -> false,
         () -> ports, ports, new OperationSubmissions.Effects(session::clearLiveInteraction, () -> {}, () -> {},
            value -> remainder = value, messages::add, ignored -> {}, () -> {}));

      Editor() {
         session.workspace().addParts(List.of(part(1), part(2)));
      }
      SubmissionActionResult submit(SubmissionScope scope) {
         return submissions.submit(List.of(new OperationWorkspacePlan.Part(1, ClientSelectionPart.Source.CLIPBOARD,
            Map.of(), WorkspaceTransform.IDENTITY, false)), scope);
      }
      void reject(boolean retryable) {
         submissions.applyResult(new OperationWorkspaceResultPayload(submissions.transferId(), false, retryable, List.of(), List.of()));
      }
      static ClientSelectionPart part(int id) {
         return new ClientSelectionPart(id, ClientSelectionPart.Source.CLIPBOARD, null, Map.of(), WorkspaceTransform.IDENTITY, false);
      }
   }
}
