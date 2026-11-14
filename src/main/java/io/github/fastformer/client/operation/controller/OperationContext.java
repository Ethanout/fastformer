package io.github.fastformer.client.operation.controller;

import io.github.fastformer.client.network.outbound.ServerGateway;

import java.util.function.BiConsumer;
import java.util.LinkedHashMap;
import io.github.fastformer.core.math.Axis;
import io.github.fastformer.common.geometry.gizmo.AxisGizmo;
import io.github.fastformer.client.interaction.selectiongesture.SelectionGestureEdits;
import io.github.fastformer.client.interaction.selectiongesture.AabbAdjustDecision;
import com.mojang.logging.LogUtils;
import io.github.fastformer.client.interaction.selectiongesture.SelectionTransformGestures;
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
import io.github.fastformer.common.selection.OperationSelectionMode;
import io.github.fastformer.common.selection.OperationSelectionVolume;
import io.github.fastformer.common.selection.OperationStackRegion;
import io.github.fastformer.protocol.callback.OperationCallbackScope;
import io.github.fastformer.protocol.s2c.operation.OperationPreviewPayload;
import io.github.fastformer.protocol.s2c.world.WorldHistoryEventPayload;
import io.github.fastformer.common.workspace.model.ClientSelectionPart;
import io.github.fastformer.common.workspace.model.WorkspaceTransform;
import io.github.fastformer.common.workspace.preview.WorkspacePreviewComposer;
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

/** Owns editable operations, captures, and submission state for one client scope. */
public final class OperationContext {
   private static final Logger LOGGER = LogUtils.getLogger();
   private final LinkedHashSet<BlockPos> failedTargets = new LinkedHashSet<>();
   private List<ClientSelectionPart> failedWorkspaceDraft = List.of();
   private final SourceBlockRenderMask sourceMask = new SourceBlockRenderMask();
   private final OperationClipboardActions clipboard;
   private final OperationTransforms transforms;
   private final SelectionTransformGestures selectionGestures;
   private final SelectionEdits selectionEdits;
   private final SelectionDeletions deletions;
   private final OperationSubmissions submissions;
   private final io.github.fastformer.client.operation.pointer.WorkspacePointerResolver pointer;
   public io.github.fastformer.client.operation.pointer.WorkspacePointerResolver pointer() { return pointer; }
   private final io.github.fastformer.client.operation.pointer.RemoteSelectionPointer remotePointer;
   public io.github.fastformer.client.operation.pointer.RemoteSelectionPointer remotePointer() { return remotePointer; }
   private final OperationPreviewState preview = new OperationPreviewState();
   private final OperationReceipts receipts = new OperationReceipts(() -> ClientSessionSystem.instance().submissions(),
      () -> {
         var minecraft = Minecraft.getInstance();
         return !ownsCurrentInteraction() || minecraft == null || minecraft.getConnection() == null || minecraft.level == null
            ? null : minecraft.level.dimension().location();
      }, ServerGateway::send,
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
      this.selectionGestures = new SelectionTransformGestures(
         new WorkspaceGestureEdits(selection, transforms, this::workspaceSubmissionPending, this::selectWorkspacePart), input.selectionGesturePointer(),
         key -> { if (key != null) captureFailed(key); });
      input.bindSelectionGestures(selectionGestures);
      var rays = Objects.requireNonNull(input, "input").pointer();
      this.remotePointer = new io.github.fastformer.client.operation.pointer.RemoteSelectionPointer(preview::live, this::active,
         context -> {
            var previews = ClientSessionSystem.instance().previews();
            var hand = context.player().getMainHandItem();
            var policy = io.github.fastformer.client.interaction.pointer.PointerRayPolicy.select(
               previews.geometry().active(), previews.building().active(), active(),
               selectionSessionActive() || selectionEdits.selectionDraftActive(), hand.isEmpty(),
               io.github.fastformer.common.placement.context.PlaceableItems.isPlaceable(hand));
            return rays.sample(context.player(), context.alternative(), policy);
         },
         () -> io.github.fastformer.client.interaction.intent.InteractionContext.capture(Minecraft.getInstance()),
         () -> io.github.fastformer.client.input.PointerDragSnapshotView.operationKey(input));
      this.pointer = new io.github.fastformer.client.operation.pointer.WorkspacePointerResolver(selection,
         selectionEdits::smartEditing, this::failedWorkspaceTargets, rays,
         new io.github.fastformer.client.operation.pointer.SelectionCreationPointer(selection, selectionEdits,
            preview::live, rays, input::canCreateSelection), selectionGestures::snapshot);
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

   public SelectionTransformGestures selectionGestures() {
      return selectionGestures;
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

   private void cancelTransforms() { selectionGestures.cancel(); transforms.cancel(); }

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
      selectionGestures.cancel();
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
      return io.github.fastformer.client.operation.pointer.RemoteSelectionPointer.ready(payload);
   }

   private void synchronizeDerivedWorkspaceState() {
      selectionSession().publishInteractionScene();
      sourceMask.replace(SourceBlockRenderMask.maskedSourcePositions(workspace().parts()));
   }


   /** Adapts real workspace ownership to the independent gesture edit ports. */
   private static final class WorkspaceGestureEdits implements SelectionGestureEdits {
      private final ClientSelectionSession selection;
      private final OperationTransforms transforms;
      private final BooleanSupplier submission;
      private final BiConsumer<Integer, Boolean> select;
      WorkspaceGestureEdits(ClientSelectionSession selection, OperationTransforms transforms, BooleanSupplier submission,
         BiConsumer<Integer, Boolean> select) {
         this.selection = selection; this.transforms = transforms; this.submission = submission; this.select = select;
      }
      private ClientOperationWorkspace workspace() { return selection.workspace(); }
      public boolean submissionPending() { return submission.getAsBoolean(); }
      public AabbAdjustDecision faceReadiness(int partId) { return transforms.aabbAdjustDecision(workspace().part(partId).orElse(null)); }
      public String failureKey(AabbAdjustDecision decision) { return transforms.aabbAdjustFailureKey(decision); }
      public void select(int partId, boolean toggle) { select.accept(partId, toggle); }
      public void focus(int partId, boolean control) {
         if (!workspace().selectedIds().contains(partId)) select.accept(partId, control);
         workspace().activate(partId);
      }
      public FaceEdit beginFace(int partId) {
         var baseline = workspace().part(partId).orElse(null);
         if (baseline == null) return null;
         focus(partId, false);
         var targets = targets(List.of(baseline));
         if (!workspace().beginEdit()) return null;
         return new FaceLease(workspace().activeEditToken(), targets, baseline);
      }
      public GizmoEdit beginGizmo(int partId, boolean common, boolean control, AxisGizmo.Operation operation, Axis axis) {
         if (!common) focus(partId, control);
         var baseline = common ? List.copyOf(workspace().selectedParts()) : workspace().part(partId).stream().toList();
         if (baseline.isEmpty()) return null;
         var targets = targets(baseline);
         var profile = transforms.repeatProfile(operation, common, partId, baseline, axis);
         boolean repeats = profile.wholeGroup();
         int stride = profile.stride();
         if (!workspace().beginEdit()) return null;
         return new GizmoLease(workspace().activeEditToken(), targets, baseline, common, operation, axis, repeats, stride);
      }
      private Targets targets(List<ClientSelectionPart> parts) {
         var identities = new LinkedHashMap<Integer, Long>();
         for (var part : parts) identities.put(part.id(), workspace().interactionId(part.id()));
         return new Targets(selection.interactionOwnerId(), identities);
      }
      private abstract class Lease implements Edit {
         final ClientOperationWorkspace.EditToken token;
         private final Targets targets;
         Lease(ClientOperationWorkspace.EditToken token, Targets targets) { this.token = token; this.targets = targets; }
         public Targets targets() { return targets; }
         public boolean valid() {
            if (!targets.owner().equals(selection.interactionOwnerId()) || !workspace().ownsEdit(token)) return false;
            for (var target : targets.identities().entrySet()) {
               if (workspace().part(target.getKey()).isEmpty() || workspace().interactionId(target.getKey()) != target.getValue()) return false;
            }
            return true;
         }
         public boolean finish() { return transforms.finishTransformGesture(token); }
         public void cancel() { transforms.cancelTransformGesture(token); }
      }
      private final class FaceLease extends Lease implements FaceEdit {
         private final ClientSelectionPart baseline;
         FaceLease(ClientOperationWorkspace.EditToken token, Targets targets, ClientSelectionPart baseline) {
            super(token, targets); this.baseline = baseline;
         }
         public AabbAdjustDecision readiness() { return transforms.aabbAdjustDecision(baseline); }
         public void update(int axis, boolean positive, int steps) { transforms.updateAabbFaceGesture(token, baseline, axis, positive, steps); }
      }
      private final class GizmoLease extends Lease implements GizmoEdit {
         private final List<ClientSelectionPart> baseline;
         private final boolean common, repeats;
         private final AxisGizmo.Operation operation;
         private final Axis axis;
         private final int stride;
         GizmoLease(ClientOperationWorkspace.EditToken token, Targets targets, List<ClientSelectionPart> baseline, boolean common,
            AxisGizmo.Operation operation, Axis axis, boolean repeats, int stride) {
            super(token, targets); this.baseline = baseline; this.common = common; this.operation = operation;
            this.axis = axis; this.repeats = repeats; this.stride = stride;
         }
         public boolean repeatsWholeGroup() { return repeats; }
         public int repeatStride() { return stride; }
         public String update(int direction, int steps, double radians, Vec3 axisVector, Vec3 pivot) {
            return transforms.updateTransformGesture(token, baseline, common, operation, axis, direction, steps, radians, axisVector, pivot);
         }
      }
   }
}
