package io.github.fastformer.client.operation.submission;

import io.github.fastformer.client.operation.selection.ClientSelectionSession;
import io.github.fastformer.client.operation.workspace.SelectionDeletionPlan;
import io.github.fastformer.client.session.draft.ClientOperationDraft;
import java.util.Objects;
import java.util.Set;

/** Immutable ownership of the live content consumed by one submission. */
public sealed interface SubmissionScope permits SubmissionScope.WholeWorkspace, SubmissionScope.SelectionParts {
   enum DraftDisposition { KEEP, REMOVE }

   record WholeWorkspace() implements SubmissionScope {}

   record SelectionParts(Set<Integer> ids, DraftDisposition draft) implements SubmissionScope {
      public SelectionParts {
         ids = Set.copyOf(ids);
         Objects.requireNonNull(draft, "draft");
      }
   }

   /** Content that must survive a successful partial submission, or null when nothing remains. */
   default ClientOperationDraft.Remainder remainder(ClientSelectionSession session) {
      return switch (this) {
         case WholeWorkspace ignored -> null;
         case SelectionParts parts -> {
            var remaining = SelectionDeletionPlan.remaining(session.workspace(), parts.ids());
            var draft = parts.draft() == DraftDisposition.KEEP ? session.draftState()
               : new ClientSelectionSession().draftState();
            yield remaining.parts().isEmpty() && draft.points().isEmpty() ? null
               : new ClientOperationDraft.Remainder(remaining, draft);
         }
      };
   }
}
