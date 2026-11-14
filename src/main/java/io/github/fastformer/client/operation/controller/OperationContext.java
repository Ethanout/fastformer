package io.github.fastformer.client.operation.controller;

import com.mojang.logging.LogUtils;
import io.github.fastformer.client.input.drag.SelectionGestureState;
import io.github.fastformer.client.interaction.InteractionObject;
import io.github.fastformer.client.interaction.SelectionInteractionScene;
import io.github.fastformer.client.interaction.intent.OperationInteractionIntent;
import io.github.fastformer.client.operation.clipboard.OperationClipboardActions;
import io.github.fastformer.client.operation.clipboard.OperationClipboardRepository;
import io.github.fastformer.client.operation.input.SelectionConfirmation;
import io.github.fastformer.client.operation.preview.OperationPreviewState;
import io.github.fastformer.client.operation.edit.SelectionEdits;
import io.github.fastformer.client.operation.edit.SelectionDeletions;
import io.github.fastformer.client.operation.submission.OperationReceipts;
import io.github.fastformer.client.operation.submission.OperationSubmissions;
import io.github.fastformer.client.operation.submission.SubmissionActionResult;
import io.github.fastformer.client.operation.submission.SubmissionEnvironment;
import io.github.fastformer.client.operation.submission.SubmissionScope;
import io.github.fastformer.client.operation.submission.SubmissionStorage;
import io.github.fastformer.client.operation.render.SourceBlockRenderMask;
import io.github.fastformer.client.operation.selection.ClientSelectionSession;
import io.github.fastformer.client.operation.selection.ClientSelectionState;
import io.github.fastformer.client.operation.selection.SelectionBlockCapture;
import io.github.fastformer.client.operation.transform.OperationTransforms;
import io.github.fastformer.client.operation.workspace.ClientOperationWorkspace;
import io.github.fastformer.client.operation.workspace.WorkspaceContentPreparer;
import io.github.fastformer.client.session.ClientSessionSystem;
import io.github.fastformer.client.session.draft.ClientOperationDraft;
import io.github.fastformer.client.session.draft.OperationDraftIdentity;
import io.github.fastformer.client.session.scope.ClientScope;
import io.github.fastformer.client.session.submission.OperationSubmissionOrigin;
import io.github.fastformer.fastplace.selection.OperationSelectionMode;
import io.github.fastformer.fastplace.selection.OperationSelectionVolume;
import io.github.fastformer.fastplace.selection.OperationStackRegion;
import io.github.fastformer.protocol.callback.OperationCallbackScope;
import io.github.fastformer.protocol.s2c.operation.OperationPreviewPayload;
import io.github.fastformer.protocol.s2c.world.WorldHistoryEventPayload;
import io.github.fastformer.workspace.model.ClientSelectionPart;
import io.github.fastformer.workspace.model.WorkspaceTransform;
import io.github.fastformer.workspace.preview.WorkspacePreviewComposer;
import io.github.fastformer.workspace.submission.OperationWorkspacePlan;
import java.util.List;
import java.util.UUID;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import net.neoforged.neoforge.network.PacketDistributor;

/** Owns editable operations, captures, and submission state for one client scope. */
public final class OperationContext {
   private static final Logger LOGGER = LogUtils.getLogger();
   private final LinkedHashSet<BlockPos> failedTargets = new LinkedHashSet<>();
   private List<ClientSelectionPart> failedWorkspaceDraft = List.of();
   private final SourceBlockRenderMask sourceMask = new SourceBlockRenderMask();
   private final OperationClipboardActions clipboard;
   private final OperationTransforms transforms;
   private final SelectionEdits selectionEdits;
   private final SelectionDeletions deletions;
   private final OperationSubmissions submissions;
   private final io.github.fastformer.client.operation.pointer.WorkspacePointerResolver pointer;
   public io.github.fastformer.client.operation.pointer.WorkspacePointerResolver pointer() { return pointer; }
   private final OperationPreviewState preview = new OperationPreviewState();
   private final OperationReceipts receipts = new OperationReceipts(() -> ClientSessionSystem.instance().submissions(),
      () -> {
         var minecraft = Minecraft.getInstance();
         return !ownsCurrentInteraction() || minecraft == null || minecraft.getConnection() == null || minecraft.level == null
            ? null : minecraft.level.dimension().location();
      }, PacketDistributor::sendToServer,
      this::showSubmissionMessage, System::currentTimeMillis,
      failure -> LOGGER.warn("Unable to query the FastFormer workspace submission receipts", failure));

   private final ClientSelectionSession selection;

   public OperationContext(ClientSelectionSession selection, OperationClipboardRepository clipboard,
      io.github.fastformer.client.input.ClientInputSession input) {
      this(selection, clipboard, () -> ClientSessionSystem.instance().submissions(), ClientSubmissionEnvironment::new, input);
   }

   OperationContext(ClientSelectionSession selection, OperationClipboardRepository clipboard,
      Supplier<? extends SubmissionStorage> storage, Function<BooleanSupplier, SubmissionEnvironment> environment,
      io.github.fastformer.client.input.ClientInputSession input) {
      this.selection = Objects.requireNonNull(selection, "selection");
      this.clipboard = new OperationClipboardActions(selection.workspace(), clipboard,
         this::workspaceSubmissionPending, this::workspaceEditInProgress, this::synchronizeDerivedWorkspaceState);
      this.deletions = new SelectionDeletions(selection, this::selectionEdits, this::submissions,
         () -> {
            var minecraft = Minecraft.getInstance();
            return ownsCurrentInteraction() && minecraft != null && minecraft.level != null;
         }, this::captureFailed, new SelectionBlockCapture());
      this.submissions = new OperationSubmissions(selection, this::remoteSelectionIdentity, deletions::pending,
         storage, environment.apply(this::ownsCurrentInteraction),
         new OperationSubmissions.Effects(this::clearWorkspace, this::synchronizeDerivedWorkspaceState,
            () -> sourceMask.complete(Map.of()), this::setSubmissionRemainder,
            this::captureFailed, this::rememberFailedWorkspaceTargets,
            () -> preview.clearSelection(selection.selectionMode())));
      this.selectionEdits = new SelectionEdits(selection, preview::live,
         () -> selectionReady(preview.live()), this::workspaceSubmissionPending, this::ownsCurrentInteraction,
         this::cancelTransforms, this::synchronizeDerivedWorkspaceState,
         () -> { sourceMask.clear(); preview.clearSelection(selection.selectionMode()); }, this::captureFailed);
      this.transforms = new OperationTransforms(selection.workspace(), this::workspaceSubmissionPending,
         selectionEdits::capturePending, this::ownsCurrentInteraction,
         this::captureFailed, this::synchronizeDerivedWorkspaceState);
      var rays = Objects.requireNonNull(input, "input").pointer();
      this.pointer = new io.github.fastformer.client.operation.pointer.WorkspacePointerResolver(selection,
         selectionEdits::smartEditing, this::failedWorkspaceTargets, rays,
         new io.github.fastformer.client.operation.pointer.SelectionCreationPointer(selection, selectionEdits,
            preview::live, rays, input::canCreateSelection));
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
      return workspace().editing() || deletions.pending() || selectionEdits.smartSampling();
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
   public Set<BlockPos> failedWorkspaceTargets() {
      return failedTargetsForDraft(
         failedWorkspaceDraft, failedTargets, workspace().parts()
      );
   }

   static Set<BlockPos> failedTargetsForDraft(
      List<ClientSelectionPart> failedDraft,
      Set<BlockPos> failedTargets,
      List<ClientSelectionPart> currentDraft
   ) {
      if (failedDraft == null || failedTargets == null || currentDraft == null
         || !failedDraft.equals(currentDraft)) {
         return Set.of();
      }
      return Set.copyOf(failedTargets);
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

   public SelectionDeletions deletions() { return deletions; }

   private SubmissionActionResult submitPartsOrFinishLocal(List<OperationWorkspacePlan.Part> parts, SubmissionScope scope) {
      if (parts.isEmpty()) {
         submissions.finish(scope);
         return SubmissionActionResult.ACCEPTED;
      }
      return submissions.submit(parts, scope);
   }

   public SubmissionActionResult confirmSelection(BlockPos candidate) {
      selectionEdits.adoptRemoteDraft();
      var action = SelectionConfirmation.decide(
         workspace(), workspaceSubmissionPending() || selectionEdits.capturePending());
      return switch (action) {
         case BLOCKED -> SubmissionActionResult.ACCEPTED;
         case SUBMIT_ALL -> selectionEdits.selectionDraftActive()
            ? (selectionEdits.completePreview(candidate, () -> {
                  var result = submitSelectionParts();
                  if (!result.accepted()) captureFailed(result.failureKey());
               })
               ? SubmissionActionResult.ACCEPTED : SubmissionActionResult.INVALID)
            : submitSelectionParts();
      };
   }

   private SubmissionActionResult submitSelectionParts() {
      var chosen = workspace().parts();
      var ids = chosen.stream().map(ClientSelectionPart::id).collect(Collectors.toSet());
      var parts = WorkspaceContentPreparer.confirmedParts(chosen);
      return submitPartsOrFinishLocal(parts, new SubmissionScope.SelectionParts(ids, SubmissionScope.DraftDisposition.REMOVE));
   }

   public OperationSubmissions submissions() { return submissions; }

   public OperationReceipts receipts() {
      return receipts;
   }

   private void showSubmissionMessage(String translationKey) {
      var minecraft = Minecraft.getInstance();
      if (minecraft != null && minecraft.player != null) {
         minecraft.player.displayClientMessage(Component.translatable(translationKey), false);
      }
   }

   private void setSubmissionRemainder(ClientOperationDraft.Remainder remainder) {
      var scope = currentScope();
      if (scope != null) scope.setSubmissionRemainder(remainder);
   }

   public void clearWorkspace() {
      submissions.cancel();
      clearTransientOperationState();
      selectionSession().clearLiveInteraction();
      WorkspacePreviewComposer.invalidatePartGeometry();
      clearFailedWorkspaceTargets();
      sourceMask.clear();
   }

   private void clearTransientOperationState() {
      pointer.clear();
      selectionEdits.cancelCaptures(); transforms.cancel(); deletions.cancel();
      submissions.leave();
   }

   static boolean shouldClearWorkspaceAfterSnapshot(
      boolean previousServerOperationActive,
      boolean submissionPending
   ) {
      return !submissionPending && previousServerOperationActive;
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
            submissions.identity(), outstandingSubmissionOrigin(), submissions.transferId()
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
      selectionEdits.tick(captureDeadline); transforms.tick(captureDeadline); deletions.tick(captureDeadline);
      selectionSession().publishInteractionScene();
      WorkspacePreviewComposer.prunePartGeometry();
      submissions.tick();
      if (preview.tick()) {
         // The environment returned a snapshot, so the connection is live and the scope
         // is bound. Any submission that was still open may have finished while the
         // player was away, so ask once for its result.
         receipts.query();
      }
      // Silence is not a server rejection. The wait owns no draft and discards none on timeout.
   }

   public boolean workspaceSubmissionPending() {
      return deletions.pending() || submissions.pending();
   }

   private void captureFailed(String key) {
      var minecraft = Minecraft.getInstance();
      if (minecraft != null && minecraft.player != null) minecraft.player.displayClientMessage(Component.translatable(key), true);
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
