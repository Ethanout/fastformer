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
import io.github.fastformer.client.operation.edit.SelectionEdits;
import io.github.fastformer.client.operation.submission.OperationReceipts;
import io.github.fastformer.client.operation.render.SourceBlockRenderMask;
import io.github.fastformer.client.operation.selection.ClientSelectionSession;
import io.github.fastformer.client.operation.selection.ClientSelectionState;
import io.github.fastformer.client.operation.selection.SelectionBlockCapture;
import io.github.fastformer.client.operation.selection.SelectionDraftPreview;
import io.github.fastformer.client.operation.transform.OperationTransforms;
import io.github.fastformer.client.operation.workspace.ClientOperationWorkspace;
import io.github.fastformer.client.operation.workspace.SelectionDeletionPlan;
import io.github.fastformer.client.operation.workspace.WorkspaceContentPreparer;
import io.github.fastformer.client.placement.ClientPlacementRouter;
import io.github.fastformer.client.session.ClientSessionSystem;
import io.github.fastformer.client.session.draft.ClientOperationDraft;
import io.github.fastformer.client.session.draft.OperationDraftIdentity;
import io.github.fastformer.client.session.scope.ClientScope;
import io.github.fastformer.client.session.submission.OperationDraftSettlement;
import io.github.fastformer.client.session.submission.OperationSubmissionOrigin;
import io.github.fastformer.fastplace.selection.OperationSelectionMode;
import io.github.fastformer.fastplace.selection.OperationSelectionVolume;
import io.github.fastformer.fastplace.selection.OperationStackRegion;
import io.github.fastformer.fastplace.session.FastPlaceActivity;
import io.github.fastformer.protocol.callback.OperationCallbackScope;
import io.github.fastformer.protocol.s2c.operation.OperationPreviewPayload;
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
   private final SelectionEdits selectionEdits;
   private String lastOperationFailureKey;
   private final WorkspaceSubmissionTracker submission;
   private final OperationPreviewState preview = new OperationPreviewState();
   private final OperationReceipts receipts = new OperationReceipts(ClientSessionSystem::instance,
      () -> {
         var minecraft = Minecraft.getInstance();
         return !ownsCurrentInteraction() || minecraft == null || minecraft.getConnection() == null || minecraft.level == null
            ? null : minecraft.level.dimension().location();
      }, net.neoforged.neoforge.network.PacketDistributor::sendToServer,
      this::showSubmissionMessage, System::currentTimeMillis,
      failure -> LOGGER.warn("Unable to query the FastFormer workspace submission receipts", failure));

   private final ClientSelectionSession selection;

   public OperationContext(ClientSelectionSession selection, OperationClipboardRepository clipboard) {
      this(selection, clipboard, new WorkspaceSubmissionTracker(20 * 30, 20 * 2));
   }

   OperationContext(ClientSelectionSession selection, OperationClipboardRepository clipboard, WorkspaceSubmissionTracker submission) {
      this.selection = java.util.Objects.requireNonNull(selection, "selection");
      this.clipboard = new OperationClipboardActions(selection.workspace(), clipboard,
         this::workspaceSubmissionPending, this::workspaceEditInProgress, this::synchronizeDerivedWorkspaceState);
      this.submission = java.util.Objects.requireNonNull(submission, "submission");
      this.selectionEdits = new SelectionEdits(selection, preview::live,
         () -> selectionReady(preview.live()), this::workspaceSubmissionPending, this::ownsCurrentInteraction,
         this::cancelTransforms, this::synchronizeDerivedWorkspaceState,
         () -> { sourceMask.clear(); preview.clearSelection(selection.selectionMode()); }, this::captureFailed);
      this.transforms = new OperationTransforms(selection.workspace(), this::workspaceSubmissionPending,
         selectionEdits::capturePending, this::ownsCurrentInteraction,
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
      return workspace().editing() || deleteCapture.pending() || selectionEdits.smartSampling();
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
      if (!workspace().isEmpty() || selectionEdits.selectionDraftActive() || !selectionReady(payload)) {
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
      selectionEdits.captureRemote(capturedSelection, transform, () -> preview.live() == payload);
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

   private void rememberFailedWorkspaceTargets(List<BlockPos> positions) {
      failedTargets.clear();
      failedTargets.addAll(positions);
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

   private void cancelTransforms() { transforms.cancel(); }

   public SelectionEdits selectionEdits() { return selectionEdits; }

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
      selectionEdits.adoptRemoteDraft();
      var selected = workspace().selectedIds();
      boolean draft = selectionEdits.selectionDraftActive();
      if (selected.isEmpty() && !draft) return false;
      var parts = new java.util.ArrayList<>(SelectionDeletionPlan.parts(workspace().parts(), selected));
      if (!draft) return submitDeletion(minecraft, parts, selected, false);
      var volume = SelectionDraftPreview.volume(selectionEdits.selectionDraft(), candidate);
      if (volume == null) return false;
      // The server selection may close while the client scans the preview box.
      // Retain the draft so the later workspace submission stays client-owned
      // and does not fail the input-state identity check.
      selectionSession().retain();
      var owner = workspace();
      var session = selectionSession();
      var unchanged = selectionEdits.unchanged();
      long revision = owner.revision();
      var protectedSources = SelectionDeletionPlan.protectedSources(owner.parts(), selected);
      int draftId = owner.partIds().stream().mapToInt(Integer::intValue).max().orElse(0) + 1;
      return deleteCapture.start(volume, null,
         () -> ownsCurrentInteraction() && workspace() == owner && selectionSession() == session && owner.revision() == revision && unchanged.getAsBoolean(), blocks -> {
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
      var draft = selectionEdits.selectionDraft();
      boolean keepDraft = !submittedDraft;
      clearWorkspace();
      workspace().restoreDraftState(remaining);
      if (keepDraft) selectionSession().restoreDraftState(draft);
      if (!workspace().isEmpty() || selectionEdits.selectionDraftActive()) selectionSession().retain();
      preview.clearSelection(selectionEdits.draftSelectionMode());
      synchronizeDerivedWorkspaceState();
   }

   public boolean confirmSelection(Minecraft minecraft, BlockPos candidate) {
      selectionEdits.adoptRemoteDraft();
      var action = SelectionConfirmation.decide(
         workspace(), workspaceSubmissionPending() || selectionEdits.capturePending());
      return switch (action) {
         case BLOCKED -> true;
         case SUBMIT_ALL -> selectionEdits.selectionDraftActive()
            ? selectionEdits.completePreview(candidate, () -> {
               if (!submitSelectionParts(minecraft)) captureFailed(lastOperationFailureKey("fastformer.message.operation_submit_invalid"));
            }) : submitSelectionParts(minecraft);
      };
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
         || minecraft == null || minecraft.getConnection() == null || workspace().isEmpty() && !selectionEdits.selectionDraftActive()) {
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
         boolean localOnly = !selectionEdits.selectionDraftActive() && workspace().parts().stream()
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
            playerSession.setSubmissionRemainder(remainder == null || remainder.parts().isEmpty() && (submittedDraft || !selectionEdits.selectionDraftActive()) ? null
               : new ClientOperationDraft.Remainder(
                  remainder, submittedDraft ? new ClientSelectionSession().draftState() : selectionEdits.selectionDraft()));
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

   public OperationReceipts receipts() {
      return receipts;
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
      selectionEdits.cancelCaptures(); transforms.cancel(); deleteCapture.cancel();
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
      selectionEdits.tick(captureDeadline); transforms.tick(captureDeadline); deleteCapture.tick(captureDeadline);
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
         receipts.query();
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

   private final SelectionBlockCapture deleteCapture = new SelectionBlockCapture();
   private java.util.Set<Integer> submittedPartIds;
   private boolean submittedDraft;
   private void captureFailed(String key) {
      lastOperationFailureKey = key;
      var player = Minecraft.getInstance().player;
      if (player != null) player.displayClientMessage(Component.translatable(key), true);
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


}
