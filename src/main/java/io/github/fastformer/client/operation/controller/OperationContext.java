package io.github.fastformer.client.operation.controller;

import com.mojang.logging.LogUtils;
import io.github.fastformer.client.interaction.SelectionInteractionScene;
import io.github.fastformer.client.operation.clipboard.ClipboardCopy;
import io.github.fastformer.client.operation.clipboard.OperationClipboard;
import io.github.fastformer.client.operation.clipboard.OperationClipboardCodec;
import io.github.fastformer.client.operation.clipboard.OperationClipboardState;
import io.github.fastformer.client.operation.clipboard.OperationClipboardStore;
import io.github.fastformer.client.operation.clipboard.PastePlacement;
import io.github.fastformer.client.operation.render.SourceBlockRenderMask;
import io.github.fastformer.client.operation.preview.OperationPreviewState;
import io.github.fastformer.client.operation.selection.ClientSelectionSession;
import io.github.fastformer.client.operation.selection.ClientSelectionState;
import io.github.fastformer.client.operation.selection.SelectionDraftEvent;
import io.github.fastformer.client.operation.selection.SelectionDraftResult;
import io.github.fastformer.client.operation.workspace.ClientOperationEventStack;
import io.github.fastformer.client.operation.workspace.ClientOperationWorkspace;
import io.github.fastformer.client.operation.workspace.WorkspaceContentPreparer;
import io.github.fastformer.client.placement.ClientPlacementRouter;
import io.github.fastformer.client.session.ClientSessionSystem;
import io.github.fastformer.client.session.draft.ClientOperationDraft;
import io.github.fastformer.client.session.draft.OperationDraftIdentity;
import io.github.fastformer.client.session.receipt.OperationSubmissionReceipt;
import io.github.fastformer.client.session.scope.ClientScope;
import io.github.fastformer.client.session.submission.OperationDraftSettlement;
import io.github.fastformer.client.session.submission.OperationSubmissionOrigin;
import io.github.fastformer.core.math.Axis;
import io.github.fastformer.fastplace.geometry.AxisGizmo;
import io.github.fastformer.fastplace.selection.OperationSelectionMode;
import io.github.fastformer.fastplace.selection.OperationSelectionVolume;
import io.github.fastformer.fastplace.session.FastPlaceActivity;
import io.github.fastformer.protocol.s2c.operation.OperationPreviewPayload;
import io.github.fastformer.protocol.s2c.workspace.OperationWorkspaceReceiptPayload;
import io.github.fastformer.protocol.s2c.workspace.OperationWorkspaceResultPayload;
import io.github.fastformer.workspace.model.ClientSelectionPart;
import io.github.fastformer.workspace.model.WorkspaceTransform;
import io.github.fastformer.workspace.preview.Composition;
import io.github.fastformer.workspace.preview.WorkspacePreviewComposer;
import io.github.fastformer.workspace.submission.OperationSubmissionOutcome;
import io.github.fastformer.workspace.submission.OperationWorkspacePlan;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

/** Owns editable operations, captures, and submission state for one client scope. */
public final class OperationContext {
   private static final Logger LOGGER = LogUtils.getLogger();
   private final java.util.LinkedHashSet<BlockPos> failedTargets = new java.util.LinkedHashSet<>();
   private List<ClientSelectionPart> failedWorkspaceDraft = List.of();
   private static final String CLIPBOARD_FILE_NAME = "fastformer-operation-clipboard.nbt.gz";
   private final SourceBlockRenderMask sourceMask = new SourceBlockRenderMask();
   private final OperationClipboardState clipboard;
   private String lastOperationFailureKey;
   private ClientOperationWorkspace.EditToken lastTooLargeGesture;
   /** Stops the client log from repeating one warning on every mouse press. */
   private boolean missingLevelLogged;
   private final WorkspaceSubmissionTracker submission;
   private final OperationPreviewState preview = new OperationPreviewState();

   private final ClientSelectionSession selection;

   public OperationContext(ClientSelectionSession selection, OperationClipboardState clipboard) {
      this(selection, clipboard, new WorkspaceSubmissionTracker(20 * 30, 20 * 2));
   }

   OperationContext(ClientSelectionSession selection, OperationClipboardState clipboard, WorkspaceSubmissionTracker submission) {
      this.selection = java.util.Objects.requireNonNull(selection, "selection");
      this.clipboard = java.util.Objects.requireNonNull(clipboard, "clipboard");
      this.submission = java.util.Objects.requireNonNull(submission, "submission");
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

   public io.github.fastformer.client.input.drag.SelectionGestureState selectionGestures() {
      return selectionSession().gestures();
   }

   public void updateVisualHover(io.github.fastformer.client.interaction.intent.OperationInteractionIntent intent) {
      selectionSession().updateHover(intent);
   }

   public io.github.fastformer.client.interaction.intent.OperationInteractionIntent visualHoverIntent() {
      return selectionSession().hoveredIntent();
   }

   public boolean hoveredLabel(io.github.fastformer.client.interaction.InteractionObject label) {
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

   public void applyWorldHistoryEvent(io.github.fastformer.protocol.s2c.world.WorldHistoryEventPayload event) {
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

   public void transformSelected(Minecraft minecraft, boolean flip) {
      if (minecraft == null || minecraft.player == null || workspaceSubmissionPending() || workspaceEditInProgress()) return;
      var owner = workspace();
      var selected = owner.selectedParts();
      if (selected.isEmpty()) return;
      var operation = flip
         ? io.github.fastformer.workspace.model.WorkspaceSymmetry.reflection(
            io.github.fastformer.workspace.transform.SelectionShortcutCalculator.viewAxis(minecraft.player.getLookAngle()))
         : io.github.fastformer.workspace.model.WorkspaceSymmetry.quarterTurnY();
      var result = io.github.fastformer.workspace.transform.SelectionShortcutCalculator.calculate(selected, operation);
      if (result.failureKey() != null) {
         captureFailed(result.failureKey());
         return;
      }
      if (!owner.beginEdit()) return;
      result.parts().forEach(owner::updatePart);
      owner.finishEdit();
      synchronizeDerivedWorkspaceState();
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

   public io.github.fastformer.protocol.callback.OperationCallbackScope remoteSelectionCallbackScope() {
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
         new io.github.fastformer.fastplace.selection.OperationStackRegion(
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

   public boolean copySelected() {
      lastOperationFailureKey = null;
      ClipboardCopy copied = OperationClipboard.fromWorkspace(workspace());
      if (copied instanceof ClipboardCopy.Empty) {
         lastOperationFailureKey = "fastformer.message.operation_copy_empty";
         return false;
      }
      if (copied instanceof ClipboardCopy.TooLarge tooLarge) {
         lastOperationFailureKey = tooLarge.limit() == Composition.Limit.WORK
            ? "fastformer.message.workspace_work_too_large"
            : "fastformer.message.workspace_result_too_large";
         return false;
      }
      OperationClipboard value = ((ClipboardCopy.Copied)copied).clipboard();
      try {
         clipboard.saveAndPublish(value, clipboard ->
            OperationClipboardStore.save(clipboardFile(), OperationClipboardCodec.encode(clipboard))
         );
         return true;
      } catch (IOException | RuntimeException exception) {
         lastOperationFailureKey = "fastformer.message.operation_copy_storage_failed";
         return false;
      }
   }

   public boolean duplicateSelected(Minecraft minecraft) {
      lastOperationFailureKey = null;
      if (workspaceSubmissionPending() || workspaceEditInProgress()) {
         lastOperationFailureKey = "fastformer.message.operation_paste_pending";
         return false;
      }
      var selected = workspace().selectedParts();
      if (selected.isEmpty()) {
         lastOperationFailureKey = "fastformer.message.operation_copy_empty";
         return false;
      }
      if (selected.size() > ClientOperationWorkspace.MAX_PARTS - workspace().size()) {
         lastOperationFailureKey = "fastformer.message.operation_paste_limit";
         return false;
      }
      // Validate resolved content with the same aggregate budget as copy and paste.
      var copied = OperationClipboard.fromWorkspace(workspace());
      if (copied instanceof ClipboardCopy.TooLarge tooLarge) {
         lastOperationFailureKey = tooLarge.limit() == Composition.Limit.WORK
            ? "fastformer.message.workspace_work_too_large" : "fastformer.message.workspace_result_too_large";
         return false;
      }
      if (copied instanceof ClipboardCopy.Empty) {
         lastOperationFailureKey = "fastformer.message.operation_copy_empty";
         return false;
      }
      if (!workspace().addParts(selected.stream().map(ClientSelectionPart::duplicate).toList())) {
         lastOperationFailureKey = "fastformer.message.operation_paste_unavailable";
         return false;
      }
      synchronizeDerivedWorkspaceState();
      return true;
   }

   public boolean paste(Minecraft minecraft) {
      lastOperationFailureKey = null;
      if (workspaceSubmissionPending()) {
         lastOperationFailureKey = "fastformer.message.operation_paste_pending";
         return false;
      }
      OperationClipboard value = loadClipboard(minecraft).orElse(null);
      if (value == null || value.parts().size() > ClientOperationWorkspace.MAX_PARTS - workspace().size()) {
         lastOperationFailureKey = value == null
            ? "fastformer.message.operation_paste_clipboard_invalid"
            : "fastformer.message.operation_paste_limit";
         return false;
      }
      Vec3 offset;
      if (active()) {
         offset = PastePlacement.inWorkspace(value.bounds());
      } else {
         HitResult hitResult = minecraft.hitResult;
         if (!(hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
            lastOperationFailureKey = "fastformer.message.operation_paste_no_anchor";
            return false;
         }
         offset = PastePlacement.atSurface(value.bounds(), hit.getLocation(), hit.getDirection());
      }
      List<ClientSelectionPart> parts = value.instantiate().stream()
         .map(part -> part.withTranslation(offset))
         .toList();
      boolean added = workspace().addParts(parts);
      if (added) {
         synchronizeDerivedWorkspaceState();
      } else {
         lastOperationFailureKey = "fastformer.message.operation_paste_unavailable";
      }
      return added;
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
      var parts = new java.util.ArrayList<>(io.github.fastformer.client.operation.workspace.SelectionDeletionPlan.parts(workspace().parts(), selected));
      if (!draft) return submitDeletion(minecraft, parts, selected, false);
      var volume = io.github.fastformer.client.operation.selection.SelectionDraftPreview.volume(selectionDraft(), candidate);
      if (volume == null) return false;
      // The server selection may close while the client scans the preview box.
      // Retain the draft so the later workspace submission stays client-owned
      // and does not fail the input-state identity check.
      selectionSession().retain();
      var owner = workspace();
      var session = selectionSession();
      var before = draftSnapshot();
      long revision = owner.revision();
      var protectedSources = io.github.fastformer.client.operation.workspace.SelectionDeletionPlan.protectedSources(owner.parts(), selected);
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
      var remaining = io.github.fastformer.client.operation.workspace.SelectionDeletionPlan.remaining(workspace(), submittedPartIds);
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
         resizeCapture.cancel();
         pendingPointAdjustment = null;
         pendingResizeSelection = null;
         resizeToken = null;
         workspace().cancelEdit();
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
      var next = io.github.fastformer.client.operation.selection.SelectionToolPreference.get().next();
      io.github.fastformer.client.operation.selection.SelectionToolPreference.set(next);
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
      io.github.fastformer.client.operation.selection.SelectionToolPreference.set(next);
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
      var action = io.github.fastformer.client.operation.input.SelectionConfirmation.decide(
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
      var volume = io.github.fastformer.client.operation.selection.SelectionDraftPreview.volume(selectionDraft(), candidate);
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

   public boolean moveSelected(BlockPos offset) {
      return moveSelected(offset, 0, false);
   }

   public boolean scrollSelected(BlockPos offset, long timestampNanos) {
      return moveSelected(offset, timestampNanos, true);
   }

   public boolean scrollStackSelected(io.github.fastformer.client.operation.input.SelectionScrollAction.Stack stack, long timestampNanos) {
      if (smartCapture.pending() || draftCapture.pending() || workspaceSubmissionPending()
         || workspace().locked() || workspace().editing() || workspace().selectedIds().isEmpty()) return false;
      var baseline = List.copyOf(workspace().selectedParts());
      var result = io.github.fastformer.workspace.transform.SelectionTransformCalculator.scrollStack(
         baseline, stack.axis(), stack.side(), stack.steps());
      if (result.failureKey() != null) {
         captureFailed(result.failureKey());
         return false;
      }
      if (!workspace().beginEdit()) return false;
      result.parts().forEach(workspace()::updatePart);
      boolean changed = workspace().finishScrollEdit(stack, timestampNanos,
         io.github.fastformer.client.FastFormerClientConfig.scrollHistoryMergeNanos());
      synchronizeDerivedWorkspaceState();
      return changed;
   }

   private boolean moveSelected(BlockPos offset, long timestampNanos, boolean scroll) {
      if (smartCapture.pending() || draftCapture.pending() || workspaceSubmissionPending() || offset == null || offset.equals(BlockPos.ZERO)
         || workspace().selectedIds().isEmpty() || !workspace().beginEdit()) {
         return false;
      }
      Vec3 delta = Vec3.atLowerCornerOf(offset);
      for (ClientSelectionPart part : List.copyOf(workspace().selectedParts())) {
         workspace().updatePart(part.withTranslation(part.transform().translation().add(delta)));
      }
      boolean changed = scroll
         ? workspace().finishScrollEdit(offset, timestampNanos, io.github.fastformer.client.FastFormerClientConfig.scrollHistoryMergeNanos())
         : workspace().finishEdit();
      synchronizeDerivedWorkspaceState();
      return changed;
   }

   public boolean canAdjustAabbFace(ClientSelectionPart part) {
      return part != null && part.canAdjustGeometry();
   }

   /** Checks whether the captured selection can be adjusted in this environment. */
   public boolean sourceSnapshotMatches(ClientSelectionPart part) {
      return sourceState(part) == SourceState.MATCHES;
   }

   /**
    * Checks adjustment readiness without replacing the captured block states.
    * The server transaction checks conflicts when the workspace is written.
    */
   public SourceState sourceState(ClientSelectionPart part) {
      if (part == null || !canAdjustAabbFace(part)) {
         return SourceState.NOT_ADJUSTABLE;
      }
      Minecraft minecraft = Minecraft.getInstance();
      if (minecraft == null || minecraft.level == null) {
         return SourceState.LEVEL_UNAVAILABLE;
      }
      // The part already owns the immutable source snapshot captured when the
      // selection was created. Reading the live world here changes the meaning
      // of an adjustment and rejects valid states, such as unsupported fence
      // connections. The server transaction performs the authoritative
      // conflict check when it writes the operation.
      return SourceState.MATCHES;
   }

   /** Why a source check passed or failed. */
   public enum SourceState {
      PENDING,
      MATCHES,
      SOURCE_CHANGED,
      LEVEL_UNAVAILABLE,
      NOT_ADJUSTABLE
   }

   /**
    * Decides whether an adjustment gesture can start, and names the reason when it
    * cannot. READY means the source check passed. The caller starts the edit,
    * because a face drag selects and activates its part before the edit begins.
    */
   public AabbAdjustDecision aabbAdjustDecision(ClientSelectionPart part) {
      if (workspaceSubmissionPending()) {
         return AabbAdjustDecision.SUBMISSION_PENDING;
      }
      return switch (sourceState(part)) {
         case PENDING -> AabbAdjustDecision.CAPTURE_PENDING;
         case MATCHES -> AabbAdjustDecision.READY;
         case SOURCE_CHANGED -> AabbAdjustDecision.SOURCE_CHANGED;
         case LEVEL_UNAVAILABLE -> {
            reportMissingLevel();
            yield AabbAdjustDecision.ENVIRONMENT_UNAVAILABLE;
         }
         case NOT_ADJUSTABLE -> AabbAdjustDecision.NO_TARGET;
      };
   }

   /** Warns one time about a missing client level, then stays quiet on later inputs. */
   private void reportMissingLevel() {
      if (missingLevelLogged) {
         return;
      }
      missingLevelLogged = true;
      LOGGER.warn("FastFormer blocked a selection adjustment because the client level is not available");
   }

   /**
    * The result of an AABB-adjust start request. READY means the client start check
    * passed. The server write-time guard is a later boundary and does not recapture
    * the edit-start source.
    */
   public enum AabbAdjustDecision {
      CAPTURE_PENDING,
      READY,
      DRAG_STARTED,
      ADJUSTED,
      UNCHANGED,
      SUBMISSION_PENDING,
      SOURCE_CHANGED,
      ENVIRONMENT_UNAVAILABLE,
      NO_TARGET,
      EDIT_UNAVAILABLE;

      /** True when the source check passed, so the caller can begin the edit. */
      public boolean ready() {
         return this == READY;
      }

      /** True when the point adjustment changed the selection. */
      public boolean adjusted() {
         return this == ADJUSTED;
      }
   }

   /**
    * The actionbar key that explains a blocked adjustment. Returns null when the
    * adjustment ran. It also returns null for ENVIRONMENT_UNAVAILABLE and
    * EDIT_UNAVAILABLE: a missing level and a live edit already have another owner,
    * so a second message does not help the player.
    */
   public String aabbAdjustFailureKey(AabbAdjustDecision decision) {
      if (decision == null) {
         return null;
      }
      return switch (decision) {
         case CAPTURE_PENDING -> "fastformer.message.operation_capture_pending";
         case SUBMISSION_PENDING -> "fastformer.message.operation_submit_pending";
         case SOURCE_CHANGED -> "fastformer.message.operation_source_changed";
         case NO_TARGET -> "fastformer.message.operation_adjust_unavailable";
         case READY, DRAG_STARTED, ADJUSTED, UNCHANGED, EDIT_UNAVAILABLE,
              ENVIRONMENT_UNAVAILABLE -> null;
      };
   }

   /** Starts a point adjustment and reports why it cannot start. */
   public AabbAdjustDecision adjustActiveAabbPoint(int mouseButton, BlockPos worldPoint) {
      return adjustAabbPoint(workspace().activeId(), mouseButton, worldPoint);
   }

   /** Validates the current source of the explicitly addressed selection before editing. */
   public AabbAdjustDecision adjustAabbPoint(int partId, int mouseButton, BlockPos worldPoint) {
      if (worldPoint == null || mouseButton < 0 || mouseButton > 2) {
         return AabbAdjustDecision.NO_TARGET;
      }
      ClientSelectionPart part = workspace().part(partId).orElse(null);
      if (workspace().editing()) return AabbAdjustDecision.EDIT_UNAVAILABLE;
      AabbAdjustDecision decision = aabbAdjustDecision(part);
      if (decision == AabbAdjustDecision.CAPTURE_PENDING) {
         if (!workspace().beginEdit()) return AabbAdjustDecision.EDIT_UNAVAILABLE;
         pendingPointAdjustment = new PendingPointAdjustment(workspace(), workspace().activeEditToken(), part, mouseButton, worldPoint.immutable());
         return AabbAdjustDecision.ADJUSTED;
      }
      if (!decision.ready()) {
         return decision;
      }
      if (!workspace().beginEdit()) {
         return AabbAdjustDecision.EDIT_UNAVAILABLE;
      }
      return capturePointAdjustment(part, mouseButton, worldPoint);
   }

   private AabbAdjustDecision capturePointAdjustment(ClientSelectionPart part, int mouseButton, BlockPos worldPoint) {
      Vec3 translation = part.transform().translation();
      BlockPos localPoint = new BlockPos(
         Mth.floor(worldPoint.getX() - translation.x),
         Mth.floor(worldPoint.getY() - translation.y),
         Mth.floor(worldPoint.getZ() - translation.z)
      );
      OperationSelectionVolume selection = mouseButton == 2
         ? part.selection().expandCuboidTo(localPoint)
         : part.selection().withCuboidPoint(mouseButton, localPoint);
      if (selection == null) {
         workspace().cancelEdit();
         return AabbAdjustDecision.UNCHANGED;
      }
      WorkspaceTransform transform = part.transform().withRepeats(
         part.transform().repeats(), BlockPos.ZERO
      );
      var owner = workspace();
      var token = owner.activeEditToken();
      boolean started = resizeCapture.start(selection, part, () -> ownsCurrentInteraction() && workspace() == owner && owner.ownsEdit(token), blocks -> {
         owner.updatePart(part.withSelection(selection).withBlocks(blocks).withTransform(transform));
         owner.finishEdit(token);
         synchronizeDerivedWorkspaceState();
      }, key -> { owner.cancelEdit(token); captureFailed(key); });
      if (!started) owner.cancelEdit(token);
      return started ? AabbAdjustDecision.ADJUSTED : AabbAdjustDecision.UNCHANGED;
   }

   private void continuePointAdjustment() {
      var pending = pendingPointAdjustment;
      if (pending == null) return;
      if (workspace() != pending.owner() || !pending.owner().ownsEdit(pending.token())
         || pending.owner().part(pending.part().id()).orElse(null) != pending.part()) {
         pending.owner().cancelEdit(pending.token());
         pendingPointAdjustment = null;
         return;
      }
      var decision = aabbAdjustDecision(pending.part());
      if (decision == AabbAdjustDecision.CAPTURE_PENDING) return;
      pendingPointAdjustment = null;
      if (decision.ready()) capturePointAdjustment(pending.part(), pending.button(), pending.point());
      else {
         pending.owner().cancelEdit(pending.token());
         String key = aabbAdjustFailureKey(decision);
         if (key != null) captureFailed(key);
      }
   }

   public void updateAabbFaceGesture(
      ClientOperationWorkspace.EditToken editToken,
      ClientSelectionPart baseline, int axis, boolean positiveFace, int outwardSteps
   ) {
      if (workspaceSubmissionPending() || !workspace().ownsEdit(editToken)
         || !canAdjustAabbFace(baseline) || axis < 0 || axis > 2) {
         return;
      }
      AABB bounds = baseline.selection().bounds();
      double minX = bounds.minX;
      double minY = bounds.minY;
      double minZ = bounds.minZ;
      double maxX = bounds.maxX;
      double maxY = bounds.maxY;
      double maxZ = bounds.maxZ;
      double signed = (positiveFace ? 1.0 : -1.0) * outwardSteps;
      switch (axis) {
         case 0 -> {
            if (positiveFace) maxX = Math.max(minX + 1.0, maxX + signed);
            else minX = Math.min(maxX - 1.0, minX + signed);
         }
         case 1 -> {
            if (positiveFace) maxY = Math.max(minY + 1.0, maxY + signed);
            else minY = Math.min(maxY - 1.0, minY + signed);
         }
         case 2 -> {
            if (positiveFace) maxZ = Math.max(minZ + 1.0, maxZ + signed);
            else minZ = Math.min(maxZ - 1.0, minZ + signed);
         }
         default -> {
            return;
         }
      }
      OperationSelectionVolume selection = new OperationSelectionVolume(
         OperationSelectionMode.CUBOID,
         new AABB(minX, minY, minZ, maxX, maxY, maxZ),
         null,
         List.of(),
         0,
         baseline.selection().point1(),
         baseline.selection().point2()
      );
      WorkspaceTransform transform = baseline.transform().withRepeats(
         baseline.transform().repeats(), BlockPos.ZERO
      );
      ClientSelectionPart current = workspace().part(baseline.id()).orElse(baseline);
      if (selection.equals(pendingResizeSelection) && java.util.Objects.equals(resizeToken, editToken)) return;
      pendingResizeSelection = selection;
      resizeToken = editToken;
      resizeFinishRequested = false;
      var owner = workspace();
      resizeCapture.start(selection, current, () -> ownsCurrentInteraction() && workspace() == owner && owner.ownsEdit(editToken), blocks -> {
         owner.updatePart(baseline.withSelection(selection).withBlocks(blocks).withTransform(transform));
         if (resizeFinishRequested) owner.finishEdit(editToken);
         pendingResizeSelection = null;
         resizeToken = null;
         synchronizeDerivedWorkspaceState();
      }, key -> { owner.cancelEdit(editToken); pendingResizeSelection = null; resizeToken = null; captureFailed(key); });
   }

   /** Returns a failure message once per edit, or null when no new message is needed. */
   public String updateTransformGesture(
      ClientOperationWorkspace.EditToken editToken,
      List<ClientSelectionPart> baseline,
      boolean common,
      AxisGizmo.Operation operation,
      Axis axis,
      int direction,
      int totalSteps,
      double rotationRadians
   ) {
      return updateTransformGesture(editToken, baseline, common, operation, axis, direction, totalSteps,
         rotationRadians, io.github.fastformer.workspace.transform.WorkspaceRotation.unitAxis(axis), null);
   }

   public String updateTransformGesture(
      ClientOperationWorkspace.EditToken editToken, List<ClientSelectionPart> baseline, boolean common,
      AxisGizmo.Operation operation, Axis axis, int direction, int totalSteps, double rotationRadians,
      Vec3 rotationAxis, Vec3 rotationPivot
   ) {
      if (workspaceSubmissionPending() || !workspace().ownsEdit(editToken)) {
         return null;
      }
      if (totalSteps == 0) {
         baseline.forEach(part -> {
            boolean alreadyFixed = workspace().part(part.id())
               .map(current -> current.editability() == ClientSelectionPart.Editability.LOCKED).orElse(false);
            workspace().updatePart(alreadyFixed ? part.fixed() : part);
         });
         synchronizeDerivedWorkspaceState();
         return null;
      }
      var result = io.github.fastformer.workspace.transform.SelectionTransformCalculator.calculate(
         baseline, common, operation, axis, direction, totalSteps, rotationRadians, rotationAxis, rotationPivot);
      if (result.failureKey() != null) {
         if (editToken.equals(lastTooLargeGesture)) return null;
         lastTooLargeGesture = editToken;
         return result.failureKey();
      }
      result.parts().forEach(workspace()::updatePart);
      synchronizeDerivedWorkspaceState();
      return null;
   }

   public boolean finishTransformGesture(ClientOperationWorkspace.EditToken editToken) {
      if (!workspace().ownsEdit(editToken)) return false;
      if (resizeCapture.pending() && java.util.Objects.equals(resizeToken, editToken)) {
         resizeFinishRequested = true;
         return true;
      }
      boolean changed = workspace().finishEdit(editToken);
      if (java.util.Objects.equals(lastTooLargeGesture, editToken)) {
         lastTooLargeGesture = null;
      }
      synchronizeDerivedWorkspaceState();
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
         if (!io.github.fastformer.client.input.FastPlaceClientInput.beginWorkspaceRequest(transferId)) {
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
            io.github.fastformer.client.input.FastPlaceClientInput.abortWorkspaceRequest(transferId);
            synchronizeDerivedWorkspaceState();
            return false;
         }
         submission.begin(transferId, identity, selectionSession().interactionOwnerId());
         var playerSession = currentScope();
         if (playerSession != null) {
            var remainder = submittedPartIds == null ? null
               : io.github.fastformer.client.operation.workspace.SelectionDeletionPlan.remaining(workspace(), submittedPartIds);
            playerSession.setSubmissionRemainder(remainder == null || remainder.parts().isEmpty() && (submittedDraft || !selectionDraftActive()) ? null
               : new io.github.fastformer.client.session.draft.ClientOperationDraft.Remainder(
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
                  io.github.fastformer.client.input.FastPlaceClientInput.abortWorkspaceRequest(transferId);
                  synchronizeDerivedWorkspaceState();
               } else {
                  try { submission.send(); }
                  catch (RuntimeException exception) {
                     abortSubmission(); owner.setLocked(false);
                     io.github.fastformer.client.input.FastPlaceClientInput.abortWorkspaceRequest(transferId);
                     captureFailed("fastformer.message.operation_submit_unavailable");
                  }
               }
            });
         if (!queued) {
            abortSubmission(); owner.setLocked(false);
            io.github.fastformer.client.input.FastPlaceClientInput.abortWorkspaceRequest(transferId);
            lastOperationFailureKey = "fastformer.message.operation_submit_storage_failed";
         }
         return queued;
      } catch (RuntimeException exception) {
         lastOperationFailureKey = "fastformer.message.operation_submit_storage_failed";
         UUID failedTransferId = submission.transferId();
         abortSubmission();
         workspace().setLocked(false);
         io.github.fastformer.client.input.FastPlaceClientInput.abortWorkspaceRequest(failedTransferId);
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
         int limit = io.github.fastformer.protocol.c2s.workspace.OperationWorkspaceReceiptQueryPayload
            .MAX_TRANSFER_IDS;
         // A large set needs more than one packet. Each query stays inside the limit.
         for (int start = 0; start < transferIds.size(); start += limit) {
            List<UUID> batch = List.copyOf(
               transferIds.subList(start, Math.min(start + limit, transferIds.size()))
            );
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(
               new io.github.fastformer.protocol.c2s.workspace.OperationWorkspaceReceiptQueryPayload(
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
         io.github.fastformer.client.input.FastPlaceClientInput.acknowledgeWorkspaceRequest(payload.transferId());
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
         io.github.fastformer.client.input.FastPlaceClientInput.acknowledgeWorkspaceRequest(payload.transferId());
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
      io.github.fastformer.client.input.FastPlaceClientInput.acknowledgeWorkspaceRequest(payload.transferId());
   }

   public void cancelTransformGesture(ClientOperationWorkspace.EditToken editToken) {
      if (java.util.Objects.equals(resizeToken, editToken)) { resizeCapture.cancel(); resizeToken = null; pendingResizeSelection = null; }
      if (!workspace().cancelEdit(editToken)) return;
      if (java.util.Objects.equals(lastTooLargeGesture, editToken)) {
         lastTooLargeGesture = null;
      }
      synchronizeDerivedWorkspaceState();
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
      draftCapture.cancel(); resizeCapture.cancel(); deleteCapture.cancel(); smartCapture.cancel();
      submittedPartIds = null;
      submittedDraft = false;
      pendingPointAdjustment = null;
      resizeToken = null; pendingResizeSelection = null;
      resizeFinishRequested = false;
      lastOperationFailureKey = null;
      lastTooLargeGesture = null;
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
      long captureDeadline = System.nanoTime() + io.github.fastformer.client.operation.selection.SelectionBlockCapture.NANOS_PER_TICK;
      draftCapture.tick(captureDeadline); resizeCapture.tick(captureDeadline); deleteCapture.tick(captureDeadline);
      smartCapture.tick(captureDeadline);
      continuePointAdjustment();
      selectionSession().publishInteractionScene();
      WorkspacePreviewComposer.prunePartGeometry();
      if (workspaceSubmissionPending() && submission.tick()) {
         UUID expiredTransferId = submission.transferId();
         abortSubmission();
         workspace().setLocked(false);
         // Keep the draft available for retry. A late result cannot affect a
         // later submission because its transfer id is no longer current.
         io.github.fastformer.client.input.FastPlaceClientInput.expireWorkspaceRequest(expiredTransferId);
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

   private Optional<OperationClipboard> loadClipboard(Minecraft minecraft) {
      if (minecraft.level == null) {
         return Optional.empty();
      }
      try {
         return clipboard.load(() -> {
            CompoundTag root = OperationClipboardStore.loadStrict(clipboardFile()).orElse(null);
            if (root == null) {
               return Optional.empty();
            }
            return Optional.of(OperationClipboardCodec.decode(
               root, minecraft.level.registryAccess().lookupOrThrow(Registries.BLOCK)
            ));
         });
      } catch (IOException | RuntimeException exception) {
         return Optional.empty();
      }
   }

   private Path clipboardFile() {
      return FMLPaths.CONFIGDIR.get().resolve(CLIPBOARD_FILE_NAME);
   }

   private final io.github.fastformer.client.operation.selection.SelectionBlockCapture draftCapture = new io.github.fastformer.client.operation.selection.SelectionBlockCapture();
   private final io.github.fastformer.client.operation.selection.SmartSelectionCapture smartCapture = new io.github.fastformer.client.operation.selection.SmartSelectionCapture();

   public boolean smartTool() { return draftSelectionMode() == OperationSelectionMode.SMART; }
   public boolean smartEditing() {
      return smartCapture.pending() || io.github.fastformer.client.operation.selection.SmartSelectionEdits.editing(workspace());
   }
   public boolean smartSampling() { return smartCapture.pending(); }

   private boolean handleSmartClick(int button, BlockPos position, boolean alternative) {
      if (position == null || workspaceSubmissionPending() || workspaceEditInProgress() || workspace().locked()) return false;
      selectionSession().retain();
      if (button == 0) {
         io.github.fastformer.client.operation.selection.SmartSelectionEdits.remove(workspace(), position, alternative);
         settleSelectionRemoval();
         return true;
      }
      if (button != 1) return true;
      if (alternative) {
         var snapshot = io.github.fastformer.client.operation.selection.SmartSelectionCapture.captureCell(Minecraft.getInstance().level, position);
         io.github.fastformer.client.operation.selection.SmartSelectionEdits.addCell(workspace(), position, snapshot);
         synchronizeDerivedWorkspaceState();
         return true;
      }
      var owner = workspace();
      var session = selectionSession();
      long revision = owner.revision();
      return smartCapture.start(position, alternative,
         () -> ownsCurrentInteraction() && owner == workspace() && session == selectionSession() && revision == owner.revision() && smartTool(), blocks -> {
            io.github.fastformer.client.operation.selection.SmartSelectionEdits.add(owner, blocks);
            synchronizeDerivedWorkspaceState();
         }, this::captureFailed);
   }
   private final io.github.fastformer.client.operation.selection.SelectionBlockCapture deleteCapture = new io.github.fastformer.client.operation.selection.SelectionBlockCapture();
   private java.util.Set<Integer> submittedPartIds;
   private boolean submittedDraft;
   private final io.github.fastformer.client.operation.selection.SelectionBlockCapture resizeCapture = new io.github.fastformer.client.operation.selection.SelectionBlockCapture();
   private OperationSelectionVolume pendingResizeSelection;
   private ClientOperationWorkspace.EditToken resizeToken;
   private boolean resizeFinishRequested;
   private PendingPointAdjustment pendingPointAdjustment;
   private record PendingPointAdjustment(ClientOperationWorkspace owner, ClientOperationWorkspace.EditToken token,
      ClientSelectionPart part, int button, BlockPos point) { }

   private void captureFailed(String key) {
      lastOperationFailureKey = key;
      var player = Minecraft.getInstance().player;
      if (player != null) player.displayClientMessage(net.minecraft.network.chat.Component.translatable(key), true);
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
