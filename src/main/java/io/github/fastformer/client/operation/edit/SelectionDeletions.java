package io.github.fastformer.client.operation.edit;

import io.github.fastformer.client.operation.selection.ClientSelectionSession;
import io.github.fastformer.client.operation.selection.SelectionBlockCapture;
import io.github.fastformer.client.operation.selection.SelectionDraftPreview;
import io.github.fastformer.client.operation.submission.OperationSubmissions;
import io.github.fastformer.client.operation.submission.SubmissionScope;
import io.github.fastformer.client.operation.workspace.SelectionDeletionPlan;
import io.github.fastformer.common.workspace.model.ClientSelectionPart;
import io.github.fastformer.common.workspace.model.WorkspaceTransform;
import io.github.fastformer.workspace.submission.OperationWorkspacePlan;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;

/** Owns deletion of selected sources and the cancellable scan of an unfinished draft. */
public final class SelectionDeletions {
   private final ClientSelectionSession session;
   private final Supplier<SelectionEdits> edits;
   private final Supplier<OperationSubmissions> submissions;
   private final BooleanSupplier available;
   private final Consumer<String> failed;
   private final SelectionBlockCapture capture;

   public SelectionDeletions(ClientSelectionSession session, Supplier<SelectionEdits> edits,
      Supplier<OperationSubmissions> submissions, BooleanSupplier available, Consumer<String> failed,
      SelectionBlockCapture capture) {
      this.session = Objects.requireNonNull(session);
      this.edits = Objects.requireNonNull(edits);
      this.submissions = Objects.requireNonNull(submissions);
      this.available = Objects.requireNonNull(available);
      this.failed = Objects.requireNonNull(failed);
      this.capture = Objects.requireNonNull(capture);
   }

   public boolean pending() { return capture.pending(); }
   public void tick(long deadline) { capture.tick(deadline); }
   public void cancel() { capture.cancel(); }

   public boolean delete(BlockPos candidate) {
      var workspace = session.workspace();
      var editor = edits.get();
      if (!available.getAsBoolean() || pending() || submissions.get().pending()
         || workspace.editing() || editor.smartSampling()) return false;
      editor.adoptRemoteDraft();
      var selected = Set.copyOf(workspace.selectedIds());
      boolean draft = editor.selectionDraftActive();
      if (selected.isEmpty() && !draft) return false;
      var parts = new ArrayList<>(SelectionDeletionPlan.parts(workspace.parts(), selected));
      if (!draft) return submit(parts, selected, SubmissionScope.DraftDisposition.KEEP);
      var volume = SelectionDraftPreview.volume(editor.selectionDraft(), candidate);
      if (volume == null) return false;
      // Server pointing may end during the scan. The eventual submission must retain
      // client ownership rather than depend on a server identity that has disappeared.
      session.retain();
      var unchanged = editor.unchanged();
      long revision = workspace.revision();
      var protectedSources = SelectionDeletionPlan.protectedSources(workspace.parts(), selected);
      int draftId = workspace.partIds().stream().mapToInt(Integer::intValue).max().orElse(0) + 1;
      return capture.start(volume, null,
         () -> available.getAsBoolean() && workspace.revision() == revision && unchanged.getAsBoolean(), blocks -> {
            var deletable = new LinkedHashMap<>(blocks);
            deletable.keySet().removeAll(protectedSources);
            if (!deletable.isEmpty()) parts.add(new OperationWorkspacePlan.Part(draftId, ClientSelectionPart.Source.WORLD,
               deletable, WorkspaceTransform.IDENTITY, true));
            submit(parts, selected, SubmissionScope.DraftDisposition.REMOVE);
         }, failed);
   }

   private boolean submit(List<OperationWorkspacePlan.Part> parts, Set<Integer> selected, SubmissionScope.DraftDisposition draft) {
      var scope = new SubmissionScope.SelectionParts(selected, draft);
      if (parts.isEmpty()) {
         submissions.get().finish(scope);
         return true;
      }
      var result = submissions.get().submit(parts, scope);
      if (!result.accepted()) failed.accept(result.failureKey());
      return result.accepted();
   }
}
