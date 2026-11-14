package io.github.fastformer.client.operation.edit;

import io.github.fastformer.client.operation.selection.ClientSelectionSession;
import io.github.fastformer.client.operation.selection.SelectionBlockCapture;
import io.github.fastformer.client.operation.selection.SelectionDraftEvent;
import io.github.fastformer.client.operation.selection.SelectionDraftPreview;
import io.github.fastformer.client.operation.selection.SelectionDraftResult;
import io.github.fastformer.client.operation.selection.SelectionToolPreference;
import io.github.fastformer.client.operation.selection.tool.SelectionTools;
import io.github.fastformer.client.operation.selection.SmartSelectionCapture;
import io.github.fastformer.client.operation.selection.SmartSelectionEdits;
import io.github.fastformer.client.operation.workspace.ClientOperationWorkspace;
import io.github.fastformer.client.operation.workspace.ClientOperationEventStack;
import io.github.fastformer.common.selection.OperationSelectionMode;
import io.github.fastformer.common.selection.OperationSelectionVolume;
import io.github.fastformer.common.workspace.model.ClientSelectionPart;
import io.github.fastformer.common.workspace.model.WorkspaceTransform;
import io.github.fastformer.protocol.s2c.operation.OperationPreviewPayload;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/** Owns selection points, smart capture, inverse edits, and local selection lifetime. */
public final class SelectionEdits {
   private final ClientSelectionSession session;
   private final Supplier<OperationPreviewPayload> preview;
   private final BooleanSupplier ready;
   private final BooleanSupplier submissionPending;
   private final BooleanSupplier current;
   private final Runnable cancelTransforms;
   private final Runnable changed;
   private final Runnable empty;
   private final Consumer<String> failed;
   private final SelectionBlockCapture draftCapture = new SelectionBlockCapture();
   private final SmartSelectionCapture smartCapture = new SmartSelectionCapture();

   public SelectionEdits(ClientSelectionSession session, Supplier<OperationPreviewPayload> preview,
      BooleanSupplier ready, BooleanSupplier submissionPending, BooleanSupplier current,
      Runnable cancelTransforms, Runnable changed, Runnable empty, Consumer<String> failed) {
      this.session = Objects.requireNonNull(session, "session");
      this.preview = Objects.requireNonNull(preview, "preview");
      this.ready = Objects.requireNonNull(ready, "ready");
      this.submissionPending = Objects.requireNonNull(submissionPending, "submissionPending");
      this.current = Objects.requireNonNull(current, "current");
      this.cancelTransforms = Objects.requireNonNull(cancelTransforms, "cancelTransforms");
      this.changed = Objects.requireNonNull(changed, "changed");
      this.empty = Objects.requireNonNull(empty, "empty");
      this.failed = Objects.requireNonNull(failed, "failed");
   }

   private ClientSelectionSession selectionSession() { return session; }
   private ClientOperationWorkspace workspace() { return session.workspace(); }
   private boolean active() { return !workspace().isEmpty(); }
   private boolean selectionSessionActive() { return preview.get().active() || session.retained(); }
   private boolean workspaceEditInProgress() { return workspace().editing() || submissionPending.getAsBoolean() || smartCapture.pending(); }

   public boolean capturePending() { return smartCapture.pending() || draftCapture.pending(); }
   public void tick(long deadline) { draftCapture.tick(deadline); smartCapture.tick(deadline); }
   public void cancelCaptures() { draftCapture.cancel(); smartCapture.cancel(); }

   /** Freezes the edit baseline without exposing the private inverse-history representation. */
   public BooleanSupplier unchanged() {
      var before = draftSnapshot();
      return () -> current.getAsBoolean() && before.equals(draftSnapshot());
   }

   public void captureRemote(OperationSelectionVolume volume, WorkspaceTransform transform, BooleanSupplier owned) {
      var owner = workspace();
      draftCapture.start(volume, null,
         () -> current.getAsBoolean() && owner.isEmpty() && owned.getAsBoolean(),
         blocks -> {
            owner.addParts(List.of(new ClientSelectionPart(0, ClientSelectionPart.Source.WORLD, volume, blocks, transform, false)));
            owner.clearHistory();
            changed.run();
         }, failed);
   }

   public boolean cancelLastSelection() {
      if (submissionPending.getAsBoolean()) return false;
      if (smartCapture.pending()) { smartCapture.cancel(); settleSelectionRemoval(); return true; }
      adoptRemoteDraft();
      selectionSession().retain();
      if (workspace().editing()) {
         cancelTransforms.run();
      }
      draftCapture.cancel();
      if (selectionSession().stack().peek().isEmpty()) {
         settleSelectionRemoval();
         return true;
      }
      if (!workspace().popSelection()) return false;
      settleSelectionRemoval();
      return true;
   }

   private void settleSelectionRemoval() {
      if (selectionSession().stack().peek().isEmpty()) {
         selectionSession().clearTransientInteraction();
         empty.run();
      } else {
         selectionSession().retain();
      }
      this.changed.run();
   }

   public void adoptRemoteDraft() {
      if (!remoteSelectionPointing()) return;
      selectionSession().restoreDraftState(new ClientSelectionSession.DraftState(preview.get().operationSelectionMode(),
         preview.get().points(), preview.get().operationPrismBasePointCount(), preview.get().selectionMin(), preview.get().selectionMax(),
         preview.get().operationSelectionMode() == OperationSelectionMode.CUBOID && preview.get().points().size() == 1
            && !preview.get().hasFirst() && preview.get().hasSecond()));
      selectionSession().retain();
   }

   public boolean removeSelectedParts() {
      if (submissionPending.getAsBoolean()) {
         return false;
      }
      boolean changed = workspace().removeSelectedParts();
      if (changed) settleSelectionRemoval();
      return changed;
   }

   public boolean handleCreateClick(int mouseButton, BlockPos point) {
      if (smartTool()) return handleSmartClick(mouseButton, point, false);
      DraftSnapshot before = draftSnapshot();
      boolean handled = handleDraftClick(mouseButton, point, false);
      recordDraftEvent(before);
      return handled;
   }

   /** Alt-prefixed creation deliberately bypasses all existing-part hit testing. */
   public boolean handleAltCreateClick(int mouseButton, BlockPos point) {
      if (smartTool()) return handleSmartClick(mouseButton, point, true);
      DraftSnapshot before = draftSnapshot();
      boolean handled = handleDraftClick(mouseButton, point, true);
      recordDraftEvent(before);
      return handled;
   }

   public OperationSelectionMode draftSelectionMode() {
      return selectionSession().selectionMode();
   }

   public ClientSelectionSession.DraftState selectionDraft() {
      return selectionSession().draftState();
   }

   public void cycleIdleSelectionTool() {
      if (active() || selectionDraftActive() || selectionSessionActive()) return;
      var next = SelectionTools.next(SelectionToolPreference.id());
      SelectionToolPreference.select(next);
      selectionSession().setSelectionTool(next);
   }

   public boolean cycleDraftMode() {
      if (!active() && !selectionDraftActive() && !selectionSessionActive()) return false;
      if (workspace().locked() || workspace().editing() || smartCapture.pending() || draftCapture.pending()) return true;
      adoptRemoteDraft();
      selectionSession().retain();
      var session = selectionSession();
      var before = session.draftState();
      var next = SelectionTools.next(session.selectionToolId());
      session.clearDraft();
      session.setSelectionTool(next);
      SelectionToolPreference.select(next);
      workspace().pushEvent(() -> session.restoreDraftEdit(before), ClientOperationEventStack.Retention.of(before.points().size() + 1));
      settleSelectionRemoval();
      return true;
   }

   private boolean handleDraftClick(int mouseButton, BlockPos point, boolean alt) {
      if (mouseButton == 0 && selectionSession().selectionTool().undoPreviousPoint()) {
         if (submissionPending.getAsBoolean() || workspace().locked() || workspace().editing()) return false;
         adoptRemoteDraft();
         draftCapture.cancel();
         boolean changed = selectionSession().undoCuboidPoint();
         if (changed) settleSelectionRemoval();
         return changed;
      }
      if (submissionPending.getAsBoolean() || workspace().locked() || workspace().editing() || draftCapture.pending()
         || workspace().size() >= ClientOperationWorkspace.MAX_PARTS) {
         return false;
      }
      if (point == null) return false;
      var event = SelectionDraftEvent.fromMouse(mouseButton, point, alt);
      var result = selectionSession().onDraftEvent(event);
      if (!result.handled()) {
         return false;
      }
      boolean handled = result != SelectionDraftResult.READY
         || finishDraft();
      if (mouseButton == 0 && handled) settleSelectionRemoval();
      return handled;
   }

   public boolean activeSelectionTransformed() {
      return active() && workspace().part(workspace().activeId()).map(ClientSelectionPart::transformed).orElse(false);
   }

   public boolean canStartSelectionDraft() {
      return smartTool() && selectionSessionActive() || selectionDraftActive()
         || (selectionSession().retained() || active())
            && selectionSession().stack().topPart().filter(ClientSelectionPart::canAdjustGeometry).isEmpty();
   }


   public boolean completePreview(BlockPos candidate, Runnable afterCapture) {
      var volume = SelectionDraftPreview.volume(selectionDraft(), candidate);
      return volume != null && captureDraft(volume, true, afterCapture);
   }

   public boolean fixActiveSelection() {
      if (submissionPending.getAsBoolean() || workspace().editing() || smartCapture.pending() || draftCapture.pending()) return false;
      var latest = workspace().selections().topPart().filter(ClientSelectionPart::isOriginalSelection).orElse(null);
      if (latest == null || !workspace().beginEdit()) return false;
      workspace().updatePart(latest.fixed());
      workspace().finishEdit();
      this.changed.run();
      return true;
   }

   public boolean undo() {
      if (smartCapture.pending()) { smartCapture.cancel(); settleSelectionRemoval(); return true; }
      if (submissionPending.getAsBoolean()) {
         return false;
      }
      boolean changed = workspace().undo();
      if (changed) settleSelectionRemoval();
      return changed;
   }

   public boolean smartTool() { return selectionSession().selectionTool().worldSampling(); }
   public boolean smartEditing() {
      return smartCapture.pending() || SmartSelectionEdits.editing(workspace());
   }
   public boolean smartSampling() { return smartCapture.pending(); }

   private boolean handleSmartClick(int button, BlockPos position, boolean alternative) {
      if (position == null || submissionPending.getAsBoolean() || workspaceEditInProgress() || workspace().locked()) return false;
      selectionSession().retain();
      if (button == 0) {
         SmartSelectionEdits.remove(workspace(), position, alternative);
         settleSelectionRemoval();
         return true;
      }
      if (button != 1) return true;
      if (alternative) {
         var snapshot = SmartSelectionCapture.captureCell(Minecraft.getInstance().level, position);
         SmartSelectionEdits.addCell(workspace(), position, snapshot);
         this.changed.run();
         return true;
      }
      var owner = workspace();
      var session = selectionSession();
      long revision = owner.revision();
      return smartCapture.start(position, alternative,
         () -> current.getAsBoolean() && owner == workspace() && session == selectionSession() && revision == owner.revision() && smartTool(), blocks -> {
            SmartSelectionEdits.add(owner, blocks);
            this.changed.run();
         }, failed);
   }

   private boolean finishDraft() {
      if (!selectionSession().selectionReady()) return false;
      OperationSelectionVolume selection = SelectionDraftPreview.volume(selectionSession().draftState(), null);
      if (selection == null) {
         return false;
      }
      return captureDraft(selection, false, null);
   }

   private boolean captureDraft(OperationSelectionVolume selection, boolean recordCompletion, Runnable afterCapture) {
      var capturedSelection = selection;
      var owner = workspace();
      var session = selectionSession();
      var before = draftSnapshot();
      return draftCapture.start(selection, null,
         () -> current.getAsBoolean() && workspace() == owner && selectionSession() == session && before.equals(draftSnapshot()), blocks -> {
            var part = new ClientSelectionPart(0, ClientSelectionPart.Source.WORLD,
               capturedSelection, blocks, WorkspaceTransform.IDENTITY, false);
            if (owner.addPartsWithoutHistory(List.of(part))) {
               session.clearDraft();
               if (recordCompletion) recordDraftEvent(before);
               this.changed.run();
               if (afterCapture != null) afterCapture.run();
            }
         }, failed);
   }

   private DraftSnapshot draftSnapshot() {
      return new DraftSnapshot(
         selectionSession().draftState(),
         List.copyOf(workspace().parts()),
         workspace().interactionIdentities(),
         workspace().selectionState()
      );
   }

   /** Records one inverse for the complete click, including selection clearing and draft changes. */
   private void recordDraftEvent(DraftSnapshot before) {
      DraftSnapshot after = draftSnapshot();
      if (before.equals(after)) {
         return;
      }
      workspace().pushEvent(() -> {
         workspace().restorePartStatesWithoutHistory(before.parts(), before.interactionIdentities());
         selectionSession().restoreDraftEdit(before.draft());
         workspace().restoreSelectionStateWithoutHistory(before.selection());
      }, ClientOperationEventStack.Retention.of(
         1 + before.draft().points().size() + before.parts().size() + before.interactionIdentities().size() + before.selection().ids().size(),
         before.parts().stream().flatMap(part -> java.util.stream.Stream.of(part.blocks(), part.sourceSnapshot()))
            .map(blocks -> ClientOperationEventStack.SharedPayload.of(blocks, blocks.size())).toList()
      ));
   }


   private record DraftSnapshot(
      ClientSelectionSession.DraftState draft,
      List<ClientSelectionPart> parts,
      java.util.Map<Integer, Long> interactionIdentities,
      ClientOperationWorkspace.SelectionState selection
   ) {
   }

   public boolean selectionDraftActive() {
      return selectionSession().hasDraft();
   }

   public boolean remoteSelectionPointing() {
      return preview.get().active() && !ready.getAsBoolean()
         && !active() && !selectionDraftActive() && !selectionSession().retained();
   }


   public void enterLocalSelectionSession() {
      selectionSession().startNewSession();
   }

}
