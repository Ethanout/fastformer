package io.github.fastformer.client.operation.controller;

import com.mojang.logging.LogUtils;
import io.github.fastformer.client.input.FastPlaceClientInput;
import io.github.fastformer.client.input.drag.SelectionGestureState;
import io.github.fastformer.client.interaction.InteractionObject;
import io.github.fastformer.client.interaction.SelectionInteractionScene;
import io.github.fastformer.client.interaction.intent.OperationInteractionIntent;
import io.github.fastformer.client.operation.clipboard.OperationClipboardActions;
import io.github.fastformer.client.operation.clipboard.OperationClipboardRepository;
import io.github.fastformer.client.operation.input.SelectionConfirmation;
import io.github.fastformer.client.operation.preview.OperationPreviewState;
import io.github.fastformer.client.operation.render.SourceBlockRenderMask;
import io.github.fastformer.client.operation.selection.ClientSelectionSession;
import io.github.fastformer.client.operation.selection.ClientSelectionState;
import io.github.fastformer.client.operation.selection.SelectionBlockCapture;
import io.github.fastformer.client.operation.selection.SelectionDraftEvent;
import io.github.fastformer.client.operation.selection.SelectionDraftPreview;
import io.github.fastformer.client.operation.selection.SelectionDraftResult;
import io.github.fastformer.client.operation.selection.SelectionToolPreference;
import io.github.fastformer.client.operation.selection.SmartSelectionCapture;
import io.github.fastformer.client.operation.selection.SmartSelectionEdits;
import io.github.fastformer.client.operation.transform.OperationTransforms;
import io.github.fastformer.client.operation.workspace.ClientOperationEventStack;
import io.github.fastformer.client.operation.workspace.ClientOperationWorkspace;
import io.github.fastformer.client.operation.workspace.SelectionDeletionPlan;
import io.github.fastformer.client.operation.workspace.WorkspaceContentPreparer;
import io.github.fastformer.client.placement.ClientPlacementRouter;
import io.github.fastformer.client.session.ClientSessionSystem;
import io.github.fastformer.client.session.draft.ClientOperationDraft;
import io.github.fastformer.client.session.draft.OperationDraftIdentity;
import io.github.fastformer.client.session.receipt.OperationSubmissionReceipt;
import io.github.fastformer.client.session.scope.ClientScope;
import io.github.fastformer.client.session.submission.OperationDraftSettlement;
import io.github.fastformer.client.session.submission.OperationSubmissionOrigin;
import io.github.fastformer.fastplace.selection.OperationSelectionMode;
import io.github.fastformer.fastplace.selection.OperationSelectionVolume;
import io.github.fastformer.fastplace.selection.OperationStackRegion;
import io.github.fastformer.fastplace.session.FastPlaceActivity;
import io.github.fastformer.protocol.c2s.workspace.OperationWorkspaceReceiptQueryPayload;
import io.github.fastformer.protocol.callback.OperationCallbackScope;
import io.github.fastformer.protocol.s2c.operation.OperationPreviewPayload;
import io.github.fastformer.protocol.s2c.workspace.OperationWorkspaceReceiptPayload;
import io.github.fastformer.protocol.s2c.workspace.OperationWorkspaceResultPayload;
import io.github.fastformer.protocol.s2c.world.WorldHistoryEventPayload;
import io.github.fastformer.workspace.model.ClientSelectionPart;
import io.github.fastformer.workspace.model.WorkspaceTransform;
import io.github.fastformer.workspace.preview.WorkspacePreviewComposer;
import io.github.fastformer.workspace.submission.OperationSubmissionOutcome;
import io.github.fastformer.workspace.submission.OperationWorkspacePlan;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

/** Owns editable operations, captures, and submission state for one client scope. */
public final class OperationContext {
   private static final Logger LOGGER = LogUtils.getLogger();
   private final java.util.LinkedHashSet<BlockPos> failedTargets = new java.util.LinkedHashSet<>();
   private List<ClientSelectionPart> failedWorkspaceDraft = List.of();
   private final SourceBlockRenderMask sourceMask = new SourceBlockRenderMask();
   private final OperationClipboardActions clipboard;
   private final OperationTransforms transforms;
   private String lastOperationFailureKey;
   private final WorkspaceSubmissionTracker submission;
   private final OperationPreviewState preview = new OperationPreviewState();

   private final ClientSelectionSession selection;

   public OperationContext(ClientSelectionSession selection, OperationClipboardRepository clipboard) {
      this(selection, clipboard, new WorkspaceSubmissionTracker(20 * 30, 20 * 2));
   }

   OperationContext(ClientSelectionSession selection, OperationClipboardRepository clipboard, WorkspaceSubmissionTracker submission) {
      this.selection = java.util.Objects.requireNonNull(selection, "selection");
      this.clipboard = new OperationClipboardActions(selection.workspace(), clipboard,
         this::workspaceSubmissionPending, this::workspaceEditInProgress, this::synchronizeDerivedWorkspaceState);
      this.submission = java.util.Objects.requireNonNull(submission, "submission");
      this.transforms = new OperationTransforms(selection.workspace(), this::workspaceSubmissionPending,
         () -> smartCapture.pending() || draftCapture.pending(), this::ownsCurrentInteraction,
         this::captureFailed, this::synchronizeDerivedWorkspaceState);
   }

   public ClientOperationWorkspace workspace() {
      return selectionSession().workspace();
   }

   public SelectionInteractionScene interactionScene() {
      return selectionSession().interactionScene();
   }

   public void selectWorkspacePart(int partId, boolean toggle) {
      if (toggle) workspace().toggleSelected(partId);
      else workspace().selectOnly(partId);
      selectionSession().publishInteractionScene();
   }

   public void selectAllWorkspaceParts() {
      workspace().selectAll();
      selectionSession().publishInteractionScene();
   }

   public void toggleAllWorkspaceParts() {
      workspace().toggleSelectAll();
      selectionSession().publishInteractionScene();
   }

   public SelectionGestureState selectionGestures() {
      return selectionSession().gestures();
   }

   public void updateVisualHover(OperationInteractionIntent intent) {
      selectionSession().updateHover(intent);
   }

   public OperationInteractionIntent visualHoverIntent() {
      return selectionSession().hoveredIntent();
   }

   public boolean hoveredLabel(InteractionObject label) {
      var session = selectionSession();
      var part = session.interactionScene().parts().get(session.hoveredPartId());
      return label != null && part != null && part.label() != null && label.id().equals(part.label().id());
   }

   private ClientSelectionSession selectionSession() {
      return selection;
   }

   private boolean ownsCurrentInteraction() {
      return ClientSessionSystem.instance().selectionSession() == selection;
   }

   private ClientScope currentScope() {
      return ownsCurrentInteraction() ? ClientSessionSystem.instance().currentScope() : null;
   }

   public SourceBlockRenderMask sourceMask() {
      return sourceMask;
   }

   public boolean active() {
      return !workspace().isEmpty();
   }

   /**
    * Local undo steps that the workspace can still revert.
    *
    * <p>This value is independent of {@link #active()}. The chain keeps the step
    * that removed the last part, so an empty workspace can still own undo duty.</p>
    */
   public int workspaceUndoDepth() {
      return workspace().undoSize();
   }

   public void applyWorldHistoryEvent(WorldHistoryEventPayload event) {
      var minecraft = Minecraft.getInstance();
      if (minecraft == null || minecraft.level == null || !minecraft.level.dimension().location().equals(event.dimension())) return;
      selectionSession().onWorldHistory(event.kind());
   }

   public boolean canRedoWorldHistory() { return workspace().isEmpty() && !selectionSession().hasDraft(); }

   /** True while a workspace transform gesture holds an open edit. */
   public boolean workspaceEditInProgress() {
      return workspace().editing() || deleteCapture.pending() || smartCapture.pending();
   }

   /**
    * True while the server has an operation-selection preview, including the
    * AABB one-point phase before the client workspace contains any blocks.
    */
   public boolean selectionSessionActive() {
      return preview.live().active() || selectionSession().retained();
   }

   public void deselectAllWorkspaceParts() {
      workspace().clearSelectionForNewDraft();
      selectionSession().publishInteractionScene();
   }

   public void enterLocalSelectionSession() {
      selectionSession().startNewSession();
   }

   public OperationDraftIdentity remoteSelectionIdentity() {
      return OperationDraftIdentity.from(preview.live());
   }

   public long remoteSelectionRevision() {
      return preview.live().operationRevision();
   }

   public OperationCallbackScope remoteSelectionCallbackScope() {
      return preview.live().callbackScope();
   }

   public boolean operationSelectionReady() {
      return preview.live().active() && selectionReady(preview.live());
   }

   public boolean operationSelectionConfirmed() {
      return preview.live().active() && preview.live().operationSelectionConfirmed();
   }

   public boolean operationAdjustmentStarted() {
      return preview.live().active() && preview.live().operationAdjustmentStarted();
   }

   public boolean operationPrism() {
      return preview.live().active() && preview.live().operationSelectionMode() == OperationSelectionMode.PRISM;
   }

   public boolean operationCuboid() {
      return preview.live().active() && preview.live().operationSelectionMode() == OperationSelectionMode.CUBOID;
   }

   /** Single authoritative state projection used by input and preview code. */
   public ClientSelectionState interactionState() {
      return selectionSession().state(preview.live().active() && !selectionReady(preview.live()));
   }

   public void setAltMode(boolean enabled) {
      selectionSession().setAltHeld(enabled);
   }

   public boolean selectionDraftActive() {
      return selectionSession().hasDraft();
   }

   public boolean remoteSelectionPointing() {
      return preview.live().active() && !selectionReady(preview.live())
         && !active() && !selectionDraftActive() && !selectionSession().retained();
   }

   public boolean synchronize(OperationPreviewPayload payload) {
      var update = preview.receive(payload, ClientSessionSystem.instance().currentDraftPending());
      if (update.discardStoredDraft()) ClientSessionSystem.instance().discardCurrentDraft();
      if (!update.accepted()) return false;
      if (!payload.active()) {
         if (!selectionSession().retained() && shouldClearWorkspaceAfterSnapshot(
            update.previousActive(), workspaceSubmissionPending()
         )) {
            clearWorkspace();
         }
         return true;
      }
      if (selectionSession().retained()) return true;
      if (!workspace().isEmpty() || selectionDraftActive() || !selectionReady(payload)) {
         return true;
      }
      OperationSelectionVolume selection = OperationSelectionVolume.create(
         payload.operationSelectionMode(),
         payload.points(),
         payload.operationPrismBasePointCount(),
         payload.operationMinOffset(),
         payload.operationMaxOffset(),
         payload.operationHullInflation()
      );
      if (payload.operationSelectionMode() == OperationSelectionMode.CUBOID
         && payload.selectionMin() != null && payload.selectionMax() != null) {
         selection = OperationSelectionVolume.cuboid(
            payload.selectionMin(), payload.selectionMax(),
            payload.points().isEmpty() ? null : payload.points().getFirst(),
            payload.points().size() < 2 ? null : payload.points().get(1)
         );
      }
      if (selection == null) {
         return true;
      }
      OperationSelectionVolume capturedSelection = selection;
      var owner = workspace();
      WorkspaceTransform transform = new WorkspaceTransform(
         Vec3.atLowerCornerOf(payload.operationTranslation()),
         payload.operationRotation(),
         new OperationStackRegion(
            payload.operationStackMin(), payload.operationStackMax()
         )
      );
      draftCapture.start(capturedSelection, null,
         () -> ownsCurrentInteraction() && workspace() == owner && owner.isEmpty() && preview.live() == payload,
         blocks -> {
            owner.addParts(List.of(new ClientSelectionPart(0, ClientSelectionPart.Source.WORLD, capturedSelection, blocks, transform, false)));
            owner.clearHistory();
            synchronizeDerivedWorkspaceState();
         }, this::captureFailed);
      return true;
   }

   /** Target positions rejected for the unchanged workspace draft. */
   public java.util.Set<BlockPos> failedWorkspaceTargets() {
      return failedTargetsForDraft(
         failedWorkspaceDraft, failedTargets, workspace().parts()
      );
   }

   static java.util.Set<BlockPos> failedTargetsForDraft(
      List<ClientSelectionPart> failedDraft,
      java.util.Set<BlockPos> failedTargets,
      List<ClientSelectionPart> currentDraft
   ) {
      if (failedDraft == null || failedTargets == null || currentDraft == null
         || !failedDraft.equals(currentDraft)) {
         return java.util.Set.of();
      }
      return java.util.Set.copyOf(failedTargets);
   }

   private void rememberFailedWorkspaceTargets(List<BlockPos> failedTargets) {
      failedTargets.clear();
      failedTargets.addAll(failedTargets);
      failedWorkspaceDraft = workspace().parts();
   }

   private void clearFailedWorkspaceTargets() {
      failedTargets.clear();
      failedWorkspaceDraft = List.of();
   }

   public boolean reconnectRestorePending() {
      return preview.restorePending();
   }

   /** Applies only the server's immutable selection snapshot after explicit user confirmation. */
   public boolean confirmReconnectRestore() {
      OperationPreviewPayload pending = preview.takePending();
      if (pending == null) return false;
      ClientSessionSystem.instance().restoreCurrentDraft(OperationDraftIdentity.from(pending));
      // The world-session boundary cleared the source mask. A restored draft keeps world
      // source references, and the snapshot below is skipped while the workspace is not
      // empty, so the mask must be rebuilt here or a restored move shows its source again.
      synchronizeDerivedWorkspaceState();
      return synchronize(pending);
   }

   public void dismissReconnectRestore() {
      preview.takePending();
      ClientSessionSystem.instance().discardCurrentDraft();
   }

   public OperationTransforms transforms() {
      return transforms;
   }

   public OperationClipboardActions clipboard() {
      return clipboard;
   }

   public String lastOperationFailureKey(String fallback) {
      String key = lastOperationFailureKey;
      lastOperationFailureKey = null;
      return key == null ? fallback : key;
   }

   public boolean deleteSelection(Minecraft minecraft, BlockPos candidate) {
      if (workspaceSubmissionPending() || workspaceEditInProgress() || minecraft == null || minecraft.level == null) return false;
      adoptRemoteDraft();
      var selected = workspace().selectedIds();
      boolean draft = selectionDraftActive();
      if (selected.isEmpty() && !draft) return false;
      var parts = new java.util.ArrayList<>(SelectionDeletionPlan.parts(workspace().parts(), selected));
      if (!draft) return submitDeletion(minecraft, parts, selected, false);
      var volume = SelectionDraftPreview.volume(selectionDraft(), candidate);
      if (volume == null) return false;
      // The server selection may close while the client scans the preview box.
      // Retain the draft so the later workspace submission stays client-owned
      // and does not fail the input-state identity check.
      selectionSession().retain();
      var owner = workspace();
      var session = selectionSession();
      var before = draftSnapshot();
      long revision = owner.revision();
      var protectedSources = SelectionDeletionPlan.protectedSources(owner.parts(), selected);
      int draftId = owner.partIds().stream().mapToInt(Integer::intValue).max().orElse(0) + 1;
      return deleteCapture.start(volume, null,
         () -> ownsCurrentInteraction() && workspace() == owner && selectionSession() == session && owner.revision() == revision && before.equals(draftSnapshot()), blocks -> {
            var deletable = new java.util.LinkedHashMap<>(blocks);
            deletable.keySet().removeAll(protectedSources);
            if (!deletable.isEmpty()) parts.add(new OperationWorkspacePlan.Part(draftId, ClientSelectionPart.Source.WORLD,
               deletable, WorkspaceTransform.IDENTITY, true));
            submitDeletion(minecraft, parts, selected, true);
         }, this::captureFailed);
   }

   private boolean submitDeletion(Minecraft minecraft, List<OperationWorkspacePlan.Part> parts,
      java.util.Set<Integer> selected, boolean draft) {
      submittedPartIds = java.util.Set.copyOf(selected);
      submittedDraft = draft;
      return submitPartsOrFinishLocal(minecraft, parts);
   }

   private boolean submitPartsOrFinishLocal(Minecraft minecraft, List<OperationWorkspacePlan.Part> parts) {
      if (parts.isEmpty()) {
         finishSubmittedParts();
         return true;
      }
      boolean submitted = submitWorkspace(minecraft, parts);
      if (!submitted) { submittedPartIds = null; submittedDraft = false; captureFailed(lastOperationFailureKey("fastformer.message.operation_submit_invalid")); }
      return submitted;
   }

   private void finishSubmittedParts() {
      var remaining = SelectionDeletionPlan.remaining(workspace(), submittedPartIds);
      var draft = selectionDraft();
      boolean keepDraft = !submittedDraft;
      clearWorkspace();
      workspace().restoreDraftState(remaining);
      if (keepDraft) selectionSession().restoreDraftState(draft);
      if (!workspace().isEmpty() || selectionDraftActive()) selectionSession().retain();
      preview.clearSelection(draftSelectionMode());
      synchronizeDerivedWorkspaceState();
   }

   public boolean cancelLastSelection() {
      if (workspaceSubmissionPending()) return false;
      if (smartCapture.pending()) { smartCapture.cancel(); settleSelectionRemoval(); return true; }
      adoptRemoteDraft();
      selectionSession().retain();
      if (workspace().editing()) {
         transforms.cancel();
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
         sourceMask.clear();
         preview.clearSelection(draftSelectionMode());
      } else {
         selectionSession().retain();
      }
      synchronizeDerivedWorkspaceState();
   }

   private void adoptRemoteDraft() {
      if (!remoteSelectionPointing()) return;
      selectionSession().restoreDraftState(new ClientSelectionSession.DraftState(preview.live().operationSelectionMode(),
         preview.live().points(), preview.live().operationPrismBasePointCount(), preview.live().selectionMin(), preview.live().selectionMax(),
         preview.live().operationSelectionMode() == OperationSelectionMode.CUBOID && preview.live().points().size() == 1
            && !preview.live().hasFirst() && preview.live().hasSecond()));
      selectionSession().retain();
   }

   public boolean removeSelectedParts() {
      if (workspaceSubmissionPending()) {
         return false;
      }
      boolean changed = workspace().removeSelectedParts();
      if (changed) settleSelectionRemoval();
      return changed;
   }

   public boolean handleCreateClick(int mouseButton, BlockPos point) {
      if (smartTool()) return handleSmartClick(mouseButton, point, false);
      DraftSnapshot before = draftSnapshot();
      boolean handled = handleCreateClickInternal(mouseButton, point);
      recordDraftEvent(before);
      return handled;
   }

   private boolean handleCreateClickInternal(int mouseButton, BlockPos point) {
      return handleDraftClick(mouseButton, point, false);
   }

   /** Alt-prefixed creation deliberately bypasses all existing-part hit testing. */
   public boolean handleAltCreateClick(int mouseButton, BlockPos point) {
      if (smartTool()) return handleSmartClick(mouseButton, point, true);
      DraftSnapshot before = draftSnapshot();
      boolean handled = handleAltCreateClickInternal(mouseButton, point);
      recordDraftEvent(before);
      return handled;
   }

   private boolean handleAltCreateClickInternal(int mouseButton, BlockPos point) {
      return handleDraftClick(mouseButton, point, true);
   }

   public OperationSelectionMode draftSelectionMode() {
      return selectionSession().selectionMode();
   }

   public ClientSelectionSession.DraftState selectionDraft() {
      return selectionSession().draftState();
   }

   public void cycleIdleSelectionTool() {
      if (active() || selectionDraftActive() || selectionSessionActive()) return;
      var next = SelectionToolPreference.get().next();
      SelectionToolPreference.set(next);
      selectionSession().setSelectionMode(next);
   }

   public boolean cycleDraftMode() {
      if (!active() && !selectionDraftActive() && !selectionSessionActive()) return false;
      if (workspace().locked() || workspace().editing() || smartCapture.pending() || draftCapture.pending()) return true;
      adoptRemoteDraft();
      selectionSession().retain();
      var session = selectionSession();
      var before = session.draftState();
      var next = draftSelectionMode().next();
      session.clearDraft();
      session.setSelectionMode(next);
      SelectionToolPreference.set(next);
      workspace().pushEvent(() -> session.restoreDraftEdit(before), ClientOperationEventStack.Retention.of(before.points().size() + 1));
      settleSelectionRemoval();
      return true;
   }

   private boolean handleDraftClick(int mouseButton, BlockPos point, boolean alt) {
      if (mouseButton == 0 && draftSelectionMode() == OperationSelectionMode.CUBOID) {
         if (workspaceSubmissionPending() || workspace().locked() || workspace().editing()) return false;
         adoptRemoteDraft();
         draftCapture.cancel();
         boolean changed = selectionSession().undoCuboidPoint();
         if (changed) settleSelectionRemoval();
         return changed;
      }
      if (workspaceSubmissionPending() || workspace().locked() || workspace().editing() || draftCapture.pending()
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
         || finishDraft(selectionSession().prismBaseCount());
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

   public boolean confirmSelection(Minecraft minecraft, BlockPos candidate) {
      adoptRemoteDraft();
      var action = SelectionConfirmation.decide(
         workspace(), workspaceSubmissionPending() || smartCapture.pending() || draftCapture.pending());
      return switch (action) {
         case BLOCKED -> true;
         case SUBMIT_ALL -> selectionDraftActive()
            ? completePreview(candidate, () -> {
               if (!submitSelectionParts(minecraft)) captureFailed(lastOperationFailureKey("fastformer.message.operation_submit_invalid"));
            }) : submitSelectionParts(minecraft);
      };
   }

   private boolean completePreview(BlockPos candidate, Runnable afterCapture) {
      var volume = SelectionDraftPreview.volume(selectionDraft(), candidate);
      return volume != null && captureDraft(volume, true, afterCapture);
   }

   public boolean fixActiveSelection() {
      if (workspaceSubmissionPending() || workspace().editing() || smartCapture.pending() || draftCapture.pending()) return false;
      var latest = workspace().selections().topPart().filter(ClientSelectionPart::isOriginalSelection).orElse(null);
      if (latest == null || !workspace().beginEdit()) return false;
      workspace().updatePart(latest.fixed());
      workspace().finishEdit();
      synchronizeDerivedWorkspaceState();
      return true;
   }

   public boolean undo() {
      if (smartCapture.pending()) { smartCapture.cancel(); settleSelectionRemoval(); return true; }
      if (workspaceSubmissionPending()) {
         return false;
      }
      boolean changed = workspace().undo();
      if (changed) settleSelectionRemoval();
      return changed;
   }

   public boolean submitWorkspace(Minecraft minecraft) {
      submittedPartIds = null;
      submittedDraft = false;
      return submitWorkspace(minecraft, WorkspaceContentPreparer.submissionParts(workspace().parts()));
   }

   private boolean submitSelectionParts(Minecraft minecraft) {
      var chosen = workspace().parts();
      submittedPartIds = chosen.stream().map(ClientSelectionPart::id).collect(java.util.stream.Collectors.toSet());
      submittedDraft = true;
      var parts = WorkspaceContentPreparer.confirmedParts(chosen);
      return submitPartsOrFinishLocal(minecraft, parts);
   }

   private boolean submitWorkspace(Minecraft minecraft, List<OperationWorkspacePlan.Part> parts) {
      lastOperationFailureKey = null;
      if (workspaceSubmissionPending() || workspace().editing()
         || minecraft == null || minecraft.getConnection() == null || workspace().isEmpty() && !selectionDraftActive()) {
         lastOperationFailureKey = workspaceSubmissionPending()
            ? "fastformer.message.operation_submit_pending"
            : "fastformer.message.operation_submit_invalid";
         return false;
      }
      try {
         if (parts.isEmpty()) {
            lastOperationFailureKey = "fastformer.message.operation_submit_no_content";
            return false;
         }
         OperationWorkspacePlan plan = new OperationWorkspacePlan(parts);
         if (!ClientPlacementRouter.canSubmitWorkspace(minecraft)) {
            lastOperationFailureKey = "fastformer.message.operation_submit_unavailable";
            return false;
         }
         UUID transferId = UUID.randomUUID();
         if (!FastPlaceClientInput.beginWorkspaceRequest(transferId)) {
            lastOperationFailureKey = "fastformer.message.operation_submit_state_changed";
            return false;
         }
         // The server preview is still active here. It turns inactive when the server
         // queues the task, so this is the last moment that can read the identity.
         OperationDraftIdentity identity = OperationDraftIdentity.from(preview.live());
         boolean localOnly = !selectionDraftActive() && workspace().parts().stream()
            .allMatch(part -> part.source() == ClientSelectionPart.Source.CLIPBOARD);
         OperationSubmissionOrigin origin = localOnly
            ? OperationSubmissionOrigin.LOCAL_ONLY : selectionSession().clientOwned()
               ? OperationSubmissionOrigin.CLIENT_SELECTION : OperationSubmissionOrigin.SERVER_SELECTION;
         if (origin != OperationSubmissionOrigin.SERVER_SELECTION) {
            identity = null;
         }
         if (origin == OperationSubmissionOrigin.SERVER_SELECTION && identity == null) {
            // A server selection that vanished cannot be confirmed after a reconnect, and
            // a draft without it could never return. Do not send work that cannot be
            // recovered.
            lastOperationFailureKey = "fastformer.message.operation_submit_state_changed";
            submission.clear();
            workspace().setLocked(false);
            FastPlaceClientInput.abortWorkspaceRequest(transferId);
            synchronizeDerivedWorkspaceState();
            return false;
         }
         submission.begin(transferId, identity, selectionSession().interactionOwnerId());
         var playerSession = currentScope();
         if (playerSession != null) {
            var remainder = submittedPartIds == null ? null
               : SelectionDeletionPlan.remaining(workspace(), submittedPartIds);
            playerSession.setSubmissionRemainder(remainder == null || remainder.parts().isEmpty() && (submittedDraft || !selectionDraftActive()) ? null
               : new ClientOperationDraft.Remainder(
                  remainder, submittedDraft ? new ClientSelectionSession().draftState() : selectionDraft()));
         }
         workspace().setLocked(true);
         var owner = workspace();
         var connection = minecraft.getConnection();
         var level = minecraft.level;
         boolean queued = ClientSessionSystem.instance().persistSubmittedDraft(identity, origin, transferId, plan,
            () -> ownsCurrentInteraction() && workspace() == owner && transferId.equals(submission.transferId())
               && minecraft.getConnection() == connection && minecraft.level == level,
            submission -> {
               if (submission == null) {
                  abortSubmission(); owner.setLocked(false);
                  FastPlaceClientInput.abortWorkspaceRequest(transferId);
                  synchronizeDerivedWorkspaceState();
               } else {
                  try { submission.send(); }
                  catch (RuntimeException exception) {
                     abortSubmission(); owner.setLocked(false);
                     FastPlaceClientInput.abortWorkspaceRequest(transferId);
                     captureFailed("fastformer.message.operation_submit_unavailable");
                  }
               }
            });
         if (!queued) {
            abortSubmission(); owner.setLocked(false);
            FastPlaceClientInput.abortWorkspaceRequest(transferId);
            lastOperationFailureKey = "fastformer.message.operation_submit_storage_failed";
         }
         return queued;
      } catch (RuntimeException exception) {
         lastOperationFailureKey = "fastformer.message.operation_submit_storage_failed";
         UUID failedTransferId = submission.transferId();
         abortSubmission();
         workspace().setLocked(false);
         FastPlaceClientInput.abortWorkspaceRequest(failedTransferId);
         synchronizeDerivedWorkspaceState();
         return false;
      }
   }

   /**
    * Sends the receipt query for every submission that still waits for a result.
    *
    * <p>The client calls this after a login settles. A submission that the server
    * completes while the player is away has no receiver, so the answer must come from
    * this query.</p>
    */
   public void queryOutstandingSubmissionReceipts() {
      Minecraft minecraft = Minecraft.getInstance();
      if (minecraft == null || minecraft.getConnection() == null || minecraft.level == null) {
         return;
      }
      ResourceLocation dimension = minecraft.level.dimension().location();
      List<OperationSubmissionReceipt> open =
         ClientSessionSystem.instance().openSubmissionReceipts();
      if (open.isEmpty()) {
         return;
      }
      List<UUID> transferIds = new java.util.ArrayList<>(open.size());
      for (OperationSubmissionReceipt receipt : open) {
         // A receipt that already reached a final state never needs a query. Only an
         // open submission can change, so only an open submission is asked about.
         // A receipt of another environment is not this connection's business.
         if (receipt.outcome().open() && receipt.dimension().equals(dimension)) {
            transferIds.add(receipt.transferId());
         }
      }
      if (transferIds.isEmpty()) {
         return;
      }
      sendReceiptQuery(dimension, transferIds);
   }

   private void sendReceiptQuery(ResourceLocation dimension, List<UUID> transferIds) {
      try {
         int limit = OperationWorkspaceReceiptQueryPayload
            .MAX_TRANSFER_IDS;
         // A large set needs more than one packet. Each query stays inside the limit.
         for (int start = 0; start < transferIds.size(); start += limit) {
            List<UUID> batch = List.copyOf(
               transferIds.subList(start, Math.min(start + limit, transferIds.size()))
            );
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(
               new OperationWorkspaceReceiptQueryPayload(
                  dimension, batch
               )
            );
         }
      } catch (RuntimeException exception) {
         LOGGER.warn("Unable to query the FastFormer workspace submission receipts", exception);
      }
   }

   /**
    * Applies the server answer to the recorded receipts.
    *
    * <p>The answer never triggers a new submission. It decides only whether the draft
    * can be dropped, must stay, or must stay with a warning.</p>
    *
    * <p>Every entry must match the recorded receipt on dimension and transfer id. An
    * answer for another environment must not settle a submission of this one.</p>
    */
   public void applyWorkspaceReceipt(OperationWorkspaceReceiptPayload payload) {
      if (payload == null) {
         return;
      }
      Minecraft minecraft = Minecraft.getInstance();
      if (minecraft == null || minecraft.level == null) {
         return;
      }
      if (!payload.dimension().equals(minecraft.level.dimension().location())) {
         return;
      }
      ClientSessionSystem sessions = ClientSessionSystem.instance();
      for (OperationWorkspaceReceiptPayload.Entry entry : payload.entries()) {
         OperationSubmissionReceipt receipt = sessions.submissionReceipt(entry.transferId()).orElse(null);
         if (receipt == null || !receipt.dimension().equals(payload.dimension())) {
            continue;
         }
         if (receipt.outcome() == OperationSubmissionOutcome.APPLIED) {
            // The client already knows that the world write happened. No later answer may
            // replace that knowledge, including an UNKNOWN from a server ledger that
            // restarted. Only the local cleanup can still be owed.
            if (receipt.needsCleanup()) {
               OperationDraftSettlement.Applied applied =
                  sessions.settleAppliedSubmission(entry.transferId());
               showSubmissionMessage(applied.confirmed()
                  ? "fastformer.message.operation_submit_success"
                  : "fastformer.message.operation_submit_cleanup_pending");
            }
            continue;
         }
         if (entry.outcome() == receipt.outcome()) {
            continue;
         }
         if (entry.outcome() == OperationSubmissionOutcome.APPLIED) {
            // The applied result is recorded with a pending cleanup, then the two draft
            // copies are cleared, then the cleanup is recorded. A false confirmation means
            // a copy could not be cleared or the record could not be saved. The client then
            // keeps the pending cleanup and repeats it at the next boundary, instead of
            // claiming a success that it cannot promise.
            OperationDraftSettlement.Applied applied =
               sessions.settleAppliedSubmission(entry.transferId());
            if (applied.confirmed()) {
               showSubmissionMessage("fastformer.message.operation_submit_success");
            } else {
               showSubmissionMessage("fastformer.message.operation_submit_cleanup_pending");
            }
            continue;
         }
         // The state is final for this receipt. A receipt that stays open would be
         // queried again on every login, so every answer ends the wait.
         sessions.updateSubmissionReceipt(
            entry.transferId(), entry.outcome(), System.currentTimeMillis()
         );
         if (entry.outcome() == OperationSubmissionOutcome.FAILED_RETRYABLE) {
            // The draft stays, and the player decides when to send it again.
            showSubmissionMessage("fastformer.message.operation_submit_failed_retry");
         } else if (entry.outcome() == OperationSubmissionOutcome.FAILED_NONRETRYABLE) {
            showSubmissionMessage("fastformer.message.operation_submit_failed_final");
         } else if (entry.outcome() == OperationSubmissionOutcome.RECOVERY_REQUIRED) {
            showSubmissionMessage("fastformer.message.operation_submit_recovery");
         } else if (entry.outcome() == OperationSubmissionOutcome.UNKNOWN) {
            // The server holds no record. The outcome is unknown, not failed, so the
            // client keeps the draft, claims nothing, and never resends on its own.
            showSubmissionMessage("fastformer.message.operation_submit_unverified");
         }
      }
   }

   /**
    * Ends the durable receipt of one submission with the result that arrived online.
    *
    * <p>The server sends the result when the client is still connected. The receipt then
    * has no open state left, so a later reconnect does not query it again.</p>
    */
   private void settleReceipt(UUID transferId, OperationSubmissionOutcome outcome) {
      if (transferId == null) {
         return;
      }
      ClientSessionSystem.instance().updateSubmissionReceipt(
         transferId, outcome, System.currentTimeMillis()
      );
   }

   private void showSubmissionMessage(String translationKey) {
      Minecraft minecraft = Minecraft.getInstance();
      if (minecraft == null || minecraft.player == null) {
         return;
      }
      minecraft.player.displayClientMessage(Component.translatable(translationKey), false);
   }

   public void applyWorkspaceResult(OperationWorkspaceResultPayload payload) {
      if (payload == null || !submission.accepts(
         payload.transferId(), selectionSession().interactionOwnerId()
      )) {
         return;
      }
      submission.clear();
      workspace().setLocked(false);
      if (payload.accepted()) {
         // The completed move leaves the source mask empty. The mask needs no per-part
         // resolve here, because the server already wrote that new state.
         sourceMask.complete(java.util.Map.of());
         // The applied result is recorded, then the two draft copies are cleared. The live
         // workspace goes below, because this path holds the lock on the submitted work and
         // the server already wrote it.
         OperationDraftSettlement.Applied applied =
            ClientSessionSystem.instance().settleAppliedSubmission(payload.transferId());
         Minecraft minecraft = Minecraft.getInstance();
         if (minecraft != null && minecraft.player != null) {
            minecraft.player.displayClientMessage(
               Component.translatable(applied.confirmed()
                  ? "fastformer.message.operation_submit_success"
                  : "fastformer.message.operation_submit_cleanup_pending"),
               true
            );
         }
         if (submittedPartIds != null) finishSubmittedParts();
         else clearWorkspace();
         FastPlaceClientInput.acknowledgeWorkspaceRequest(payload.transferId());
         return;
      }
      if (!shouldRetainWorkspaceAfterFailure(payload.retryable()) && submittedPartIds == null) {
         // The player cannot send this work again, and the live workspace goes. The durable
         // copy goes with it, so a later reconnect cannot restore work that the server
         // already refused for good.
         settleReceipt(payload.transferId(), OperationSubmissionOutcome.FAILED_NONRETRYABLE);
         boolean clearedDraft = ClientSessionSystem.instance()
            .clearDraftCopies(payload.transferId()).confirmed();
         clearWorkspace();
         Minecraft minecraft = Minecraft.getInstance();
         if (minecraft != null && minecraft.player != null) {
            minecraft.player.displayClientMessage(
               Component.translatable(clearedDraft
                  ? "fastformer.message.operation_submit_discarded"
                  : "fastformer.message.operation_submit_cleanup_pending"),
               true
            );
         }
         FastPlaceClientInput.acknowledgeWorkspaceRequest(payload.transferId());
         return;
      }
      // The workspace stays for editing and for a retry, so the durable copy stays too.
      clearSubmissionRemainder();
      submittedPartIds = null;
      submittedDraft = false;
      settleReceipt(payload.transferId(), OperationSubmissionOutcome.FAILED_RETRYABLE);
      synchronizeDerivedWorkspaceState();
      if (!payload.failedPartIds().isEmpty()) {
         workspace().selectOnly(payload.failedPartIds().getFirst());
         for (int index = 1; index < payload.failedPartIds().size(); index++) {
            workspace().toggleSelected(payload.failedPartIds().get(index));
         }
      }
      rememberFailedWorkspaceTargets(payload.failedTargetPositions());
      // Target positions identify world conflicts; the preview renderer will rebuild
      // from the retained draft after the rejection and drop those stale ghosts.
      Minecraft minecraft = Minecraft.getInstance();
      if (minecraft != null && minecraft.player != null) {
         minecraft.player.displayClientMessage(
            Component.translatable(payload.failure().messageKey()), true
         );
      }
      FastPlaceClientInput.acknowledgeWorkspaceRequest(payload.transferId());
   }

   private void clearSubmissionRemainder() {
      var session = currentScope();
      if (session != null) session.setSubmissionRemainder(null);
   }

   private void abortSubmission() {
      submission.clear();
      clearSubmissionRemainder();
      submittedPartIds = null;
      submittedDraft = false;
   }

   public void clearWorkspace() {
      clearSubmissionRemainder();
      clearTransientOperationState();
      selectionSession().clearLiveInteraction();
      WorkspacePreviewComposer.invalidatePartGeometry();
      clearFailedWorkspaceTargets();
      sourceMask.clear();
      submission.clear();
   }

   private void clearTransientOperationState() {
      draftCapture.cancel(); transforms.cancel(); deleteCapture.cancel(); smartCapture.cancel();
      submittedPartIds = null;
      submittedDraft = false;

      lastOperationFailureKey = null;
   }

   static boolean shouldClearWorkspaceAfterSnapshot(
      boolean previousServerOperationActive,
      boolean submissionPending
   ) {
      return !submissionPending && previousServerOperationActive;
   }

   static boolean shouldRetainWorkspaceAfterFailure(boolean retryable) {
      return retryable;
   }

   /** Ends editable client state when the connection closes. */
   public void onDisconnected() {
      clearFailedWorkspaceTargets();
      ClientScope session = currentScope();
      if (session != null) {
         // The server preview is already inactive when the task was queued, and the
         // server may have cancelled the selection. Use the identity that the send
         // captured, so a submitted draft still reaches the disk.
         ClientSessionSystem.instance().suspendCurrentDraft(
            submission.identity(), outstandingSubmissionOrigin(), submission.transferId()
         );
      } else {
         selectionSession().clearLiveInteraction();
      }
      leaveEnvironment();
      if (ownsCurrentInteraction()) ClientSessionSystem.instance().clearUnboundInteraction();
      WorkspacePreviewComposer.invalidatePartGeometry();
   }

   /** Cancels world-bound work while leaving the scope's retained draft to its owner. */
   public void leaveEnvironment() {
      clearFailedWorkspaceTargets();
      clearTransientOperationState();
      workspace().cancelEdit();
      submission.clear();
      preview.leaveEnvironment();
      sourceMask.clear();
   }

   /**
    * Origin to record when the disconnect has no server identity for the live draft.
    *
    * <p>Only a workspace that holds no world part at all is proven client-only. Every
    * other workspace needs a server identity, so the draft is kept as already staged
    * instead of being overwritten without one.</p>
    */
   private OperationSubmissionOrigin outstandingSubmissionOrigin() {
      boolean localOnly = !workspace().parts().isEmpty() && workspace().parts().stream()
         .allMatch(part -> part.source() == ClientSelectionPart.Source.CLIPBOARD);
      return localOnly ? OperationSubmissionOrigin.LOCAL_ONLY
         : selectionSession().clientOwned() ? OperationSubmissionOrigin.CLIENT_SELECTION : null;
   }

   /** Ends a reconnect boundary once the snapshot that followed it has settled. */
   public void onClientTick() {
      ClientSessionSystem.instance().drainStorageResults();
      long captureDeadline = System.nanoTime() + SelectionBlockCapture.NANOS_PER_TICK;
      draftCapture.tick(captureDeadline); transforms.tick(captureDeadline); deleteCapture.tick(captureDeadline);
      smartCapture.tick(captureDeadline);
      selectionSession().publishInteractionScene();
      WorkspacePreviewComposer.prunePartGeometry();
      if (workspaceSubmissionPending() && submission.tick()) {
         UUID expiredTransferId = submission.transferId();
         abortSubmission();
         workspace().setLocked(false);
         // Keep the draft available for retry. A late result cannot affect a
         // later submission because its transfer id is no longer current.
         FastPlaceClientInput.expireWorkspaceRequest(expiredTransferId);
         synchronizeDerivedWorkspaceState();
         Minecraft minecraft = Minecraft.getInstance();
         if (minecraft != null && minecraft.player != null) {
            minecraft.player.displayClientMessage(
               Component.translatable("fastformer.message.operation_submit_timeout"), true
            );
         }
      }
      if (preview.tick()) {
         // The environment returned a snapshot, so the connection is live and the scope
         // is bound. Any submission that was still open may have finished while the
         // player was away, so ask once for its result.
         queryOutstandingSubmissionReceipts();
      }
      // Silence is not a server rejection. The wait owns no draft and discards none on timeout.
   }

   public void observeServerActivity(FastPlaceActivity activity) {
      if (workspaceSubmissionPending()) {
         submission.observeActivity(activity);
      }
   }

   public boolean workspaceSubmissionPending() {
      return deleteCapture.pending() || submission.pending(selectionSession().interactionOwnerId());
   }

   private final SelectionBlockCapture draftCapture = new SelectionBlockCapture();
   private final SmartSelectionCapture smartCapture = new SmartSelectionCapture();

   public boolean smartTool() { return draftSelectionMode() == OperationSelectionMode.SMART; }
   public boolean smartEditing() {
      return smartCapture.pending() || SmartSelectionEdits.editing(workspace());
   }
   public boolean smartSampling() { return smartCapture.pending(); }

   private boolean handleSmartClick(int button, BlockPos position, boolean alternative) {
      if (position == null || workspaceSubmissionPending() || workspaceEditInProgress() || workspace().locked()) return false;
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
         synchronizeDerivedWorkspaceState();
         return true;
      }
      var owner = workspace();
      var session = selectionSession();
      long revision = owner.revision();
      return smartCapture.start(position, alternative,
         () -> ownsCurrentInteraction() && owner == workspace() && session == selectionSession() && revision == owner.revision() && smartTool(), blocks -> {
            SmartSelectionEdits.add(owner, blocks);
            synchronizeDerivedWorkspaceState();
         }, this::captureFailed);
   }
   private final SelectionBlockCapture deleteCapture = new SelectionBlockCapture();
   private java.util.Set<Integer> submittedPartIds;
   private boolean submittedDraft;
   private void captureFailed(String key) {
      lastOperationFailureKey = key;
      var player = Minecraft.getInstance().player;
      if (player != null) player.displayClientMessage(Component.translatable(key), true);
   }

   private boolean finishDraft(int prismBaseCount) {
      OperationSelectionVolume selection = OperationSelectionVolume.create(
         selectionSession().selectionMode(),
         selectionSession().draftPoints(),
         prismBaseCount,
         BlockPos.ZERO,
         BlockPos.ZERO,
         0
      );
      if (selection == null) {
         return false;
      }
      if (selection.mode() == OperationSelectionMode.CUBOID) {
         selection = selection.expandCuboidTo(selectionSession().draftMinPoint())
            .expandCuboidTo(selectionSession().draftMaxPoint());
      }
      return captureDraft(selection, false, null);
   }

   private boolean captureDraft(OperationSelectionVolume selection, boolean recordCompletion, Runnable afterCapture) {
      var capturedSelection = selection;
      var owner = workspace();
      var session = selectionSession();
      var before = draftSnapshot();
      return draftCapture.start(selection, null,
         () -> ownsCurrentInteraction() && workspace() == owner && selectionSession() == session && before.equals(draftSnapshot()), blocks -> {
            var part = new ClientSelectionPart(0, ClientSelectionPart.Source.WORLD,
               capturedSelection, blocks, WorkspaceTransform.IDENTITY, false);
            if (owner.addPartsWithoutHistory(List.of(part))) {
               session.clearDraft();
               if (recordCompletion) recordDraftEvent(before);
               synchronizeDerivedWorkspaceState();
               if (afterCapture != null) afterCapture.run();
            }
         }, this::captureFailed);
   }

   private DraftSnapshot draftSnapshot() {
      return new DraftSnapshot(
         selectionSession().selectionMode(),
         selectionSession().draftPoints(),
         selectionSession().prismBaseCount(),
         selectionSession().draftMinPoint(),
         selectionSession().draftMaxPoint(),
         selectionSession().draftState().secondPointOnly(),
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
         selectionSession().restoreDraftEdit(new ClientSelectionSession.DraftState(
            before.mode(), before.points(), before.prismBaseCount(), before.minPoint(), before.maxPoint(), before.secondPointOnly()
         ));
         workspace().restoreSelectionStateWithoutHistory(before.selection());
      }, ClientOperationEventStack.Retention.of(
         1 + before.points().size() + before.parts().size() + before.interactionIdentities().size() + before.selection().ids().size(),
         before.parts().stream().flatMap(part -> java.util.stream.Stream.of(part.blocks(), part.sourceSnapshot()))
            .map(blocks -> ClientOperationEventStack.SharedPayload.of(blocks, blocks.size())).toList()
      ));
   }

   private boolean selectionReady(OperationPreviewPayload payload) {
      OperationSelectionMode mode = payload.operationSelectionMode();
      return mode == OperationSelectionMode.CUBOID
         ? payload.hasFirst() && payload.hasSecond()
         : mode == OperationSelectionMode.PRISM
            ? payload.operationPrismBasePointCount() >= 3
               && payload.points().size() > payload.operationPrismBasePointCount()
            : payload.points().size() >= mode.requiredPoints();
   }

   private void synchronizeDerivedWorkspaceState() {
      selectionSession().publishInteractionScene();
      sourceMask.replace(SourceBlockRenderMask.maskedSourcePositions(workspace().parts()));
   }

   private record DraftSnapshot(
      OperationSelectionMode mode,
      List<BlockPos> points,
      int prismBaseCount,
      BlockPos minPoint,
      BlockPos maxPoint,
      boolean secondPointOnly,
      List<ClientSelectionPart> parts,
      java.util.Map<Integer, Long> interactionIdentities,
      ClientOperationWorkspace.SelectionState selection
   ) {
   }

}
