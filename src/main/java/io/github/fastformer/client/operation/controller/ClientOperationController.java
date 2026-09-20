package io.github.fastformer.client.operation.controller;

import io.github.fastformer.fastplace.selection.OperationSelectionMode;
import io.github.fastformer.fastplace.selection.OperationSelectionVolume;
import io.github.fastformer.fastplace.OperationWorkspacePlan;
import io.github.fastformer.fastplace.FastPlaceActivity;
import io.github.fastformer.fastplace.geometry.AxisGizmo;
import io.github.fastformer.client.placement.ClientPlacementRouter;
import io.github.fastformer.client.operation.clipboard.ClipboardCopy;
import io.github.fastformer.client.operation.clipboard.OperationClipboard;
import io.github.fastformer.client.operation.clipboard.OperationClipboardCodec;
import io.github.fastformer.client.operation.clipboard.OperationClipboardState;
import io.github.fastformer.client.operation.clipboard.OperationClipboardStore;
import io.github.fastformer.client.operation.clipboard.PastePlacement;
import io.github.fastformer.client.operation.model.ClientBlockSnapshot;
import io.github.fastformer.client.operation.model.ClientSelectionPart;
import io.github.fastformer.client.operation.model.WorkspaceTransform;
import io.github.fastformer.client.operation.selection.ClientSelectionSession;
import io.github.fastformer.client.interaction.SelectionInteractionScene;
import io.github.fastformer.client.operation.selection.ClientSelectionState;
import io.github.fastformer.client.operation.preview.SourceBlockRenderMask;
import io.github.fastformer.client.operation.preview.Composition;
import io.github.fastformer.client.operation.preview.WorkspacePreviewComposer;
import io.github.fastformer.client.operation.workspace.ClientOperationEventStack;
import io.github.fastformer.client.operation.workspace.ClientOperationWorkspace;
import io.github.fastformer.client.operation.workspace.WorkspaceContentPreparer;
import io.github.fastformer.client.session.ClientPlayerSession;
import io.github.fastformer.client.session.ClientSessionManager;
import io.github.fastformer.client.session.OperationDraftIdentity;
import io.github.fastformer.client.operation.selection.SelectionDraftEvent;
import io.github.fastformer.client.operation.selection.SelectionDraftResult;
import io.github.fastformer.client.session.OperationDraftSettlement;
import io.github.fastformer.client.session.OperationSubmissionOrigin;
import io.github.fastformer.client.session.OperationSubmissionReceipt;
import io.github.fastformer.network.payload.operation.OperationPreviewPayload;
import io.github.fastformer.network.payload.operation.OperationSubmissionOutcome;
import io.github.fastformer.network.payload.operation.OperationWorkspaceReceiptPayload;
import io.github.fastformer.network.payload.operation.OperationWorkspaceResultPayload;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

/** Bridges the pure client workspace to Minecraft input, world capture and persistent clipboard storage. */
public final class ClientOperationController {
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final java.util.LinkedHashSet<BlockPos> FAILED_WORKSPACE_TARGETS = new java.util.LinkedHashSet<>();
   private static List<ClientSelectionPart> failedWorkspaceDraft = List.of();
   private static final String CLIPBOARD_FILE_NAME = "fastformer-operation-clipboard.nbt.gz";
   private static final SourceBlockRenderMask SOURCE_MASK = new SourceBlockRenderMask();
   private static final ClientSelectionSession FALLBACK_SELECTION_SESSION = new ClientSelectionSession();
   private static final OperationClipboardState CLIPBOARD_STATE = new OperationClipboardState();
   private static String lastOperationFailureKey;
   private static ClientOperationWorkspace.EditToken lastTooLargeGesture;
   /** Stops the client log from repeating one warning on every mouse press. */
   private static boolean missingLevelLogged;
   private static final WorkspaceSubmissionTracker WORKSPACE_SUBMISSION =
      new WorkspaceSubmissionTracker(20 * 30, 20 * 2);
   /** Ticks an unanswered connection boundary waits before it is dropped. */
   private static final int RECONNECT_BOUNDARY_IDLE_TICKS = 40;
   /** A reconnect snapshot may describe a server task that continues running,
    * but it must not recreate client-owned draft/workspace state. */
   private static boolean reconnectBoundaryArmed;
   private static boolean reconnectBoundarySawSnapshot;
   private static int reconnectBoundaryIdleTicks;
   private static OperationPreviewPayload pendingReconnectPreview;
   private static OperationPreviewPayload serverPreview = OperationPreviewPayload.inactive();
   private static long lastServerPreviewRevision = -1L;

   private ClientOperationController() {
   }

   public static ClientOperationWorkspace workspace() {
      return selectionSession().workspace();
   }

   public static SelectionInteractionScene interactionScene() {
      return selectionSession().interactionScene();
   }

   public static void selectWorkspacePart(int partId, boolean toggle) {
      if (toggle) workspace().toggleSelected(partId);
      else workspace().selectOnly(partId);
      selectionSession().publishInteractionScene();
   }

   public static void selectAllWorkspaceParts() {
      workspace().selectAll();
      selectionSession().publishInteractionScene();
   }

   public static io.github.fastformer.client.input.drag.SelectionGestureState selectionGestures() {
      return selectionSession().gestures();
   }

   public static void updateVisualHover(io.github.fastformer.client.input.OperationInteractionIntent intent) {
      selectionSession().updateHover(intent);
   }

   public static io.github.fastformer.client.input.OperationInteractionIntent visualHoverIntent() {
      return selectionSession().hoveredIntent();
   }

   public static boolean hoveredLabel(io.github.fastformer.client.interaction.InteractionObject label) {
      var session = selectionSession();
      var part = session.interactionScene().parts().get(session.hoveredPartId());
      return label != null && part != null && part.label() != null && label.id().equals(part.label().id());
   }

   private static ClientSelectionSession selectionSession() {
      Minecraft minecraft = Minecraft.getInstance();
      if (minecraft == null || minecraft.player == null) {
         ClientPlayerSession current = ClientSessionManager.instance().currentSession();
         return current == null ? FALLBACK_SELECTION_SESSION : current.selectionSession();
      }
      return ClientSessionManager.instance().forCurrent(minecraft).selectionSession();
   }

   public static SourceBlockRenderMask sourceMask() {
      return SOURCE_MASK;
   }

   public static boolean active() {
      return !workspace().isEmpty();
   }

   /**
    * Local undo steps that the workspace can still revert.
    *
    * <p>This value is independent of {@link #active()}. The chain keeps the step
    * that removed the last part, so an empty workspace can still own undo duty.</p>
    */
   public static int workspaceUndoDepth() {
      return workspace().undoSize();
   }

   /** True while a workspace transform gesture holds an open edit. */
   public static boolean workspaceEditInProgress() {
      return workspace().editing();
   }

   /**
    * True while the server has an operation-selection preview, including the
    * AABB one-point phase before the client workspace contains any blocks.
    */
   public static boolean selectionSessionActive() {
      return serverPreview.active();
   }

   public static OperationDraftIdentity remoteSelectionIdentity() {
      return OperationDraftIdentity.from(serverPreview);
   }

   public static boolean operationSelectionReady() {
      return serverPreview.active() && selectionReady(serverPreview);
   }

   public static boolean operationSelectionConfirmed() {
      return serverPreview.active() && serverPreview.operationSelectionConfirmed();
   }

   public static boolean operationAdjustmentStarted() {
      return serverPreview.active() && serverPreview.operationAdjustmentStarted();
   }

   public static boolean operationPrism() {
      return serverPreview.active() && serverPreview.operationSelectionMode() == OperationSelectionMode.PRISM;
   }

   public static boolean operationCuboid() {
      return serverPreview.active() && serverPreview.operationSelectionMode() == OperationSelectionMode.CUBOID;
   }

   /** Single authoritative state projection used by input and preview code. */
   public static ClientSelectionState interactionState() {
      return selectionSession().state(serverPreview.active() && !selectionReady(serverPreview));
   }

   public static void setAltMode(boolean enabled) {
      selectionSession().setAltHeld(enabled);
   }

   public static boolean selectionDraftActive() {
      return selectionSession().hasDraft();
   }

   public static boolean synchronize(OperationPreviewPayload payload) {
      if (payload == null) {
         return false;
      }
      if (payload.operationRevision() <= lastServerPreviewRevision) {
         return false;
      }
      if (pendingReconnectPreview != null
         && payload.operationRevision() == pendingReconnectPreview.operationRevision()) {
         return false;
      }
      if (shouldHoldStoredDraft(
         reconnectBoundaryArmed,
         pendingReconnectPreview != null,
         ClientSessionManager.instance().currentDraftPending()
      )) {
         if (payload.active()) {
            pendingReconnectPreview = payload;
            serverPreview = OperationPreviewPayload.inactive();
            return false;
         }
         ClientSessionManager.instance().discardCurrentDraft();
      }
      if (reconnectBoundaryArmed) {
         reconnectBoundarySawSnapshot = true;
         if (payload.active()) {
            reconnectBoundaryArmed = false;
            reconnectBoundarySawSnapshot = false;
            reconnectBoundaryIdleTicks = 0;
            pendingReconnectPreview = payload;
            serverPreview = OperationPreviewPayload.inactive();
            return false;
         }
         ClientSessionManager.instance().discardCurrentDraft();
      } else if (pendingReconnectPreview != null
         && payload.operationRevision() != pendingReconnectPreview.operationRevision()) {
         // A later revision proves the player is already working with the live
         // operation, so the snapshot waiting for confirmation is stale.
         pendingReconnectPreview = null;
      }
      boolean previousServerOperationActive = serverPreview.active();
      lastServerPreviewRevision = payload.operationRevision();
      serverPreview = payload;
      if (!payload.active()) {
         if (shouldClearWorkspaceAfterSnapshot(
            previousServerOperationActive, workspaceSubmissionPending()
         )) {
            clearWorkspace();
         }
         return true;
      }
      selectionSession().setSelectionMode(payload.operationSelectionMode());
      if (!workspace().isEmpty() || !selectionReady(payload)) {
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
      Map<BlockPos, ClientBlockSnapshot> blocks = capture(selection);
      WorkspaceTransform transform = new WorkspaceTransform(
         Vec3.atLowerCornerOf(payload.operationTranslation()),
         payload.operationRotation(),
         new io.github.fastformer.fastplace.selection.OperationStackRegion(
            payload.operationStackMin(), payload.operationStackMax()
         )
      );
      workspace().addParts(List.of(new ClientSelectionPart(
         0, ClientSelectionPart.Source.WORLD, selection, blocks, transform, false
      )));
      workspace().clearHistory();
      synchronizeDerivedWorkspaceState();
      return true;
   }

   /** Target positions rejected for the unchanged workspace draft. */
   public static java.util.Set<BlockPos> failedWorkspaceTargets() {
      return failedTargetsForDraft(
         failedWorkspaceDraft, FAILED_WORKSPACE_TARGETS, workspace().parts()
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

   private static void rememberFailedWorkspaceTargets(List<BlockPos> failedTargets) {
      FAILED_WORKSPACE_TARGETS.clear();
      FAILED_WORKSPACE_TARGETS.addAll(failedTargets);
      failedWorkspaceDraft = workspace().parts();
   }

   private static void clearFailedWorkspaceTargets() {
      FAILED_WORKSPACE_TARGETS.clear();
      failedWorkspaceDraft = List.of();
   }

   public static boolean reconnectRestorePending() {
      return pendingReconnectPreview != null;
   }

   /** Applies only the server's immutable selection snapshot after explicit user confirmation. */
   public static boolean confirmReconnectRestore() {
      OperationPreviewPayload pending = pendingReconnectPreview;
      if (pending == null) return false;
      pendingReconnectPreview = null;
      ClientSessionManager.instance().restoreCurrentDraft(OperationDraftIdentity.from(pending));
      // The world-session boundary cleared the source mask. A restored draft keeps world
      // source references, and the snapshot below is skipped while the workspace is not
      // empty, so the mask must be rebuilt here or a restored move shows its source again.
      synchronizeDerivedWorkspaceState();
      return synchronize(pending);
   }

   static boolean shouldHoldStoredDraft(
      boolean reconnectArmed, boolean promptPending, boolean draftPending
   ) {
      return !reconnectArmed && !promptPending && draftPending;
   }

   public static void dismissReconnectRestore() {
      pendingReconnectPreview = null;
      ClientSessionManager.instance().discardCurrentDraft();
   }

   public static boolean copySelected() {
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
         CLIPBOARD_STATE.saveAndPublish(value, clipboard ->
            OperationClipboardStore.save(clipboardFile(), OperationClipboardCodec.encode(clipboard))
         );
         return true;
      } catch (IOException | RuntimeException exception) {
         lastOperationFailureKey = "fastformer.message.operation_copy_storage_failed";
         return false;
      }
   }

   public static boolean paste(Minecraft minecraft) {
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

   public static String lastOperationFailureKey(String fallback) {
      String key = lastOperationFailureKey;
      lastOperationFailureKey = null;
      return key == null ? fallback : key;
   }

   public static boolean markSelectedForDeletion() {
      if (workspaceSubmissionPending() || workspace().selectedIds().isEmpty() || !workspace().beginEdit()) {
         return false;
      }
      for (ClientSelectionPart part : List.copyOf(workspace().selectedParts())) {
         if (part.source() == ClientSelectionPart.Source.CLIPBOARD) {
            workspace().removePartDuringEdit(part.id());
         } else {
            workspace().updatePart(part.withPendingDelete(true));
         }
      }
      boolean changed = workspace().finishEdit();
      synchronizeDerivedWorkspaceState();
      return changed;
   }

   public static boolean removeSelectedParts() {
      if (workspaceSubmissionPending()) {
         return false;
      }
      boolean changed = workspace().removeSelectedParts();
      synchronizeDerivedWorkspaceState();
      return changed;
   }

   public static boolean handleCreateClick(int mouseButton, BlockPos point) {
      DraftSnapshot before = draftSnapshot();
      boolean handled = handleCreateClickInternal(mouseButton, point);
      recordDraftEvent(before);
      return handled;
   }

   private static boolean handleCreateClickInternal(int mouseButton, BlockPos point) {
      return handleDraftClick(mouseButton, point, false);
   }

   /** Alt-prefixed creation deliberately bypasses all existing-part hit testing. */
   public static boolean handleAltCreateClick(int mouseButton, BlockPos point) {
      DraftSnapshot before = draftSnapshot();
      boolean handled = handleAltCreateClickInternal(mouseButton, point);
      recordDraftEvent(before);
      return handled;
   }

   private static boolean handleAltCreateClickInternal(int mouseButton, BlockPos point) {
      return handleDraftClick(mouseButton, point, true);
   }

   public static OperationSelectionMode draftSelectionMode() {
      return selectionSession().selectionMode();
   }

   private static boolean handleDraftClick(int mouseButton, BlockPos point, boolean alt) {
      if (workspaceSubmissionPending() || workspace().size() >= ClientOperationWorkspace.MAX_PARTS) {
         return false;
      }
      var event = SelectionDraftEvent.fromMouse(mouseButton, point, alt);
      var result = selectionSession().onDraftEvent(event);
      if (!result.handled()) {
         return false;
      }
      boolean handled = result != SelectionDraftResult.READY
         || finishDraft(selectionSession().prismBaseCount());
      return handled;
   }

   public static boolean activeSelectionTransformed() {
      return active() && workspace().part(workspace().activeId()).map(ClientSelectionPart::transformed).orElse(false);
   }

   public static boolean undo() {
      if (workspaceSubmissionPending()) {
         return false;
      }
      boolean changed = workspace().undo();
      synchronizeDerivedWorkspaceState();
      return changed;
   }

   public static boolean moveSelected(BlockPos offset) {
      if (workspaceSubmissionPending() || offset == null || offset.equals(BlockPos.ZERO)
         || workspace().selectedIds().isEmpty() || !workspace().beginEdit()) {
         return false;
      }
      Vec3 delta = Vec3.atLowerCornerOf(offset);
      for (ClientSelectionPart part : List.copyOf(workspace().selectedParts())) {
         workspace().updatePart(part.withTranslation(part.transform().translation().add(delta)));
      }
      boolean changed = workspace().finishEdit();
      synchronizeDerivedWorkspaceState();
      return changed;
   }

   public static boolean canAdjustAabbFace(ClientSelectionPart part) {
      return part != null && part.canAdjustGeometry();
   }

   /** Checks the source only when an adjustment gesture is actually starting. */
   public static boolean sourceSnapshotMatches(ClientSelectionPart part) {
      return sourceState(part) == SourceState.MATCHES;
   }

   /**
    * Reports why a source check passes or fails. This is the client AABB-adjust start
    * check against the live client world. The server write-time guard compares the
    * recorded before-image at write time. It does not recapture the edit-start source
    * through LiveBlockLookup.
    */
   public static SourceState sourceState(ClientSelectionPart part) {
      if (part == null || !canAdjustAabbFace(part)) {
         return SourceState.NOT_ADJUSTABLE;
      }
      Minecraft minecraft = Minecraft.getInstance();
      if (minecraft == null || minecraft.level == null) {
         return SourceState.LEVEL_UNAVAILABLE;
      }
      return capture(part.selection()).equals(part.blocks())
         ? SourceState.MATCHES
         : SourceState.SOURCE_CHANGED;
   }

   /** Why a source check passed or failed. */
   public enum SourceState {
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
   public static AabbAdjustDecision aabbAdjustDecision(ClientSelectionPart part) {
      if (workspaceSubmissionPending()) {
         return AabbAdjustDecision.SUBMISSION_PENDING;
      }
      return switch (sourceState(part)) {
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
   private static void reportMissingLevel() {
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
   public static String aabbAdjustFailureKey(AabbAdjustDecision decision) {
      if (decision == null) {
         return null;
      }
      return switch (decision) {
         case SUBMISSION_PENDING -> "fastformer.message.operation_submit_pending";
         case SOURCE_CHANGED -> "fastformer.message.operation_source_changed";
         case NO_TARGET -> "fastformer.message.operation_adjust_unavailable";
         case READY, DRAG_STARTED, ADJUSTED, UNCHANGED, EDIT_UNAVAILABLE,
              ENVIRONMENT_UNAVAILABLE -> null;
      };
   }

   /** Starts a point adjustment and reports why it cannot start. */
   public static AabbAdjustDecision adjustActiveAabbPoint(int mouseButton, BlockPos worldPoint) {
      return adjustAabbPoint(workspace().activeId(), mouseButton, worldPoint);
   }

   /** Validates the current source of the explicitly addressed selection before editing. */
   public static AabbAdjustDecision adjustAabbPoint(int partId, int mouseButton, BlockPos worldPoint) {
      if (worldPoint == null || mouseButton < 0 || mouseButton > 2) {
         return AabbAdjustDecision.NO_TARGET;
      }
      ClientSelectionPart part = workspace().part(partId).orElse(null);
      AabbAdjustDecision decision = aabbAdjustDecision(part);
      if (!decision.ready()) {
         return decision;
      }
      if (!workspace().beginEdit()) {
         return AabbAdjustDecision.EDIT_UNAVAILABLE;
      }
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
      workspace().updatePart(
         part.withSelection(selection).withBlocks(snapshotForSelection(part, selection)).withTransform(transform)
      );
      boolean changed = workspace().finishEdit();
      synchronizeDerivedWorkspaceState();
      return changed ? AabbAdjustDecision.ADJUSTED : AabbAdjustDecision.UNCHANGED;
   }

   public static void updateAabbFaceGesture(
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
      workspace().updatePart(
         baseline.withSelection(selection).withBlocks(snapshotForSelection(current, selection)).withTransform(transform)
      );
      synchronizeDerivedWorkspaceState();
   }

   /** Returns a failure message once per edit, or null when no new message is needed. */
   public static String updateTransformGesture(
      ClientOperationWorkspace.EditToken editToken,
      List<ClientSelectionPart> baseline,
      boolean common,
      AxisGizmo.Operation operation,
      AxisGizmo.Axis axis,
      int direction,
      int totalSteps,
      double rotationRadians
   ) {
      if (workspaceSubmissionPending() || !workspace().ownsEdit(editToken)) {
         return null;
      }
      if (totalSteps == 0) {
         baseline.forEach(workspace()::updatePart);
         synchronizeDerivedWorkspaceState();
         return null;
      }
      var result = io.github.fastformer.client.operation.transform.SelectionTransformCalculator.calculate(
         baseline, common, operation, axis, direction, totalSteps, rotationRadians);
      if (result.failureKey() != null) {
         if (editToken.equals(lastTooLargeGesture)) return null;
         lastTooLargeGesture = editToken;
         return result.failureKey();
      }
      result.parts().forEach(workspace()::updatePart);
      synchronizeDerivedWorkspaceState();
      return null;
   }

   public static boolean finishTransformGesture(ClientOperationWorkspace.EditToken editToken) {
      if (!workspace().ownsEdit(editToken)) return false;
      boolean changed = workspace().finishEdit(editToken);
      if (java.util.Objects.equals(lastTooLargeGesture, editToken)) {
         lastTooLargeGesture = null;
      }
      synchronizeDerivedWorkspaceState();
      return changed;
   }

   public static boolean submitWorkspace(Minecraft minecraft) {
      lastOperationFailureKey = null;
      if (workspaceSubmissionPending() || workspace().editing()
         || minecraft == null || minecraft.getConnection() == null || workspace().isEmpty()) {
         lastOperationFailureKey = workspaceSubmissionPending()
            ? "fastformer.message.operation_submit_pending"
            : "fastformer.message.operation_submit_invalid";
         return false;
      }
      try {
         List<OperationWorkspacePlan.Part> parts = WorkspaceContentPreparer.submissionParts(workspace().parts());
         if (parts.isEmpty()) {
            lastOperationFailureKey = "fastformer.message.operation_submit_no_content";
            return false;
         }
         OperationWorkspacePlan plan = new OperationWorkspacePlan(parts);
         ClientPlacementRouter.WorkspaceSubmission submission = ClientPlacementRouter
            .prepareWorkspace(minecraft, plan).orElse(null);
         if (submission == null) {
            lastOperationFailureKey = "fastformer.message.operation_submit_unavailable";
            return false;
         }
         UUID transferId = submission.transferId();
         if (!io.github.fastformer.client.input.FastPlaceClientInput.beginWorkspaceRequest(transferId)) {
            lastOperationFailureKey = "fastformer.message.operation_submit_state_changed";
            return false;
         }
         // The server preview is still active here. It turns inactive when the server
         // queues the task, so this is the last moment that can read the identity.
         OperationDraftIdentity identity = OperationDraftIdentity.from(serverPreview);
         boolean localOnly = parts.stream()
            .allMatch(part -> part.source() == ClientSelectionPart.Source.CLIPBOARD);
         OperationSubmissionOrigin origin = localOnly
            ? OperationSubmissionOrigin.LOCAL_ONLY : OperationSubmissionOrigin.SERVER_SELECTION;
         if (localOnly) {
            identity = null;
         }
         if (origin == OperationSubmissionOrigin.SERVER_SELECTION && identity == null) {
            // A server selection that vanished cannot be confirmed after a reconnect, and
            // a draft without it could never return. Do not send work that cannot be
            // recovered.
            lastOperationFailureKey = "fastformer.message.operation_source_changed";
            WORKSPACE_SUBMISSION.clear();
            workspace().setLocked(false);
            io.github.fastformer.client.input.FastPlaceClientInput.abortWorkspaceRequest(transferId);
            synchronizeDerivedWorkspaceState();
            return false;
         }
         // Both files reach the disk before the submission leaves. The order is receipt
         // first, then draft, then send. A failed second write removes the first, so a
         // receipt never claims a recovery that has no draft behind it.
         if (!recordSubmissionReceipt(transferId, identity, origin)
            || !ClientSessionManager.instance().persistSubmittedDraft(identity, origin, transferId)) {
            // A silent send would promise a recovery that the client cannot make. The
            // stores report the failed write to the player.
            ClientSessionManager.instance().forgetSubmissionReceipt(transferId);
            WORKSPACE_SUBMISSION.clear();
            workspace().setLocked(false);
            io.github.fastformer.client.input.FastPlaceClientInput.abortWorkspaceRequest(transferId);
            synchronizeDerivedWorkspaceState();
            return false;
         }
         WORKSPACE_SUBMISSION.begin(transferId, identity, selectionSession().interactionOwnerId());
         workspace().setLocked(true);
         submission.send();
         return true;
      } catch (IOException | RuntimeException exception) {
         lastOperationFailureKey = "fastformer.message.operation_submit_storage_failed";
         UUID failedTransferId = WORKSPACE_SUBMISSION.transferId();
         WORKSPACE_SUBMISSION.clear();
         workspace().setLocked(false);
         io.github.fastformer.client.input.FastPlaceClientInput.abortWorkspaceRequest(failedTransferId);
         synchronizeDerivedWorkspaceState();
         return false;
      }
   }

   /**
    * Writes the durable receipt of one submission before the submission leaves the client.
    *
    * @return true when the receipt reached the disk
    */
   private static boolean recordSubmissionReceipt(
      UUID transferId, OperationDraftIdentity identity, OperationSubmissionOrigin origin
   ) {
      Minecraft minecraft = Minecraft.getInstance();
      ResourceLocation dimension = minecraft != null && minecraft.level != null
         ? minecraft.level.dimension().location() : null;
      if (dimension == null) {
         return false;
      }
      return ClientSessionManager.instance().recordSubmissionReceipt(new OperationSubmissionReceipt(
         transferId, origin, origin == OperationSubmissionOrigin.LOCAL_ONLY ? null : identity, dimension,
         OperationSubmissionOutcome.IN_FLIGHT, System.currentTimeMillis()
      ));
   }

   /**
    * Sends the receipt query for every submission that still waits for a result.
    *
    * <p>The client calls this after a login settles. A submission that the server
    * completes while the player is away has no receiver, so the answer must come from
    * this query.</p>
    */
   public static void queryOutstandingSubmissionReceipts() {
      Minecraft minecraft = Minecraft.getInstance();
      if (minecraft == null || minecraft.getConnection() == null || minecraft.level == null) {
         return;
      }
      ResourceLocation dimension = minecraft.level.dimension().location();
      List<OperationSubmissionReceipt> open =
         ClientSessionManager.instance().openSubmissionReceipts();
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

   private static void sendReceiptQuery(ResourceLocation dimension, List<UUID> transferIds) {
      try {
         int limit = io.github.fastformer.network.payload.operation.OperationWorkspaceReceiptQueryPayload
            .MAX_TRANSFER_IDS;
         // A large set needs more than one packet. Each query stays inside the limit.
         for (int start = 0; start < transferIds.size(); start += limit) {
            List<UUID> batch = List.copyOf(
               transferIds.subList(start, Math.min(start + limit, transferIds.size()))
            );
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(
               new io.github.fastformer.network.payload.operation.OperationWorkspaceReceiptQueryPayload(
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
   public static void applyWorkspaceReceipt(OperationWorkspaceReceiptPayload payload) {
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
      ClientSessionManager sessions = ClientSessionManager.instance();
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
   private static void settleReceipt(UUID transferId, OperationSubmissionOutcome outcome) {
      if (transferId == null) {
         return;
      }
      ClientSessionManager.instance().updateSubmissionReceipt(
         transferId, outcome, System.currentTimeMillis()
      );
   }

   private static void showSubmissionMessage(String translationKey) {
      Minecraft minecraft = Minecraft.getInstance();
      if (minecraft == null || minecraft.player == null) {
         return;
      }
      minecraft.player.displayClientMessage(Component.translatable(translationKey), false);
   }

   public static void applyWorkspaceResult(OperationWorkspaceResultPayload payload) {
      if (payload == null || !WORKSPACE_SUBMISSION.accepts(
         payload.transferId(), selectionSession().interactionOwnerId()
      )) {
         return;
      }
      WORKSPACE_SUBMISSION.clear();
      workspace().setLocked(false);
      if (payload.accepted()) {
         // The completed move leaves the source mask empty. The mask needs no per-part
         // resolve here, because the server already wrote that new state.
         SOURCE_MASK.complete(java.util.Map.of());
         // The applied result is recorded, then the two draft copies are cleared. The live
         // workspace goes below, because this path holds the lock on the submitted work and
         // the server already wrote it.
         OperationDraftSettlement.Applied applied =
            ClientSessionManager.instance().settleAppliedSubmission(payload.transferId());
         Minecraft minecraft = Minecraft.getInstance();
         if (minecraft != null && minecraft.player != null) {
            minecraft.player.displayClientMessage(
               Component.translatable(applied.confirmed()
                  ? "fastformer.message.operation_submit_success"
                  : "fastformer.message.operation_submit_cleanup_pending"),
               true
            );
         }
         clearWorkspace();
         io.github.fastformer.client.input.FastPlaceClientInput.acknowledgeWorkspaceRequest(payload.transferId());
         return;
      }
      if (!shouldRetainWorkspaceAfterFailure(payload.retryable())) {
         // The player cannot send this work again, and the live workspace goes. The durable
         // copy goes with it, so a later reconnect cannot restore work that the server
         // already refused for good.
         settleReceipt(payload.transferId(), OperationSubmissionOutcome.FAILED_NONRETRYABLE);
         boolean clearedDraft = ClientSessionManager.instance()
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
            Component.translatable("fastformer.message.operation_submit_rejected"), true
         );
      }
      io.github.fastformer.client.input.FastPlaceClientInput.acknowledgeWorkspaceRequest(payload.transferId());
   }

   public static void cancelTransformGesture(ClientOperationWorkspace.EditToken editToken) {
      if (!workspace().cancelEdit(editToken)) return;
      if (java.util.Objects.equals(lastTooLargeGesture, editToken)) {
         lastTooLargeGesture = null;
      }
      synchronizeDerivedWorkspaceState();
   }

   public static void clearWorkspace() {
      lastOperationFailureKey = null;
      lastTooLargeGesture = null;
      selectionSession().clearLiveInteraction();
      WorkspacePreviewComposer.invalidatePartGeometry();
      clearFailedWorkspaceTargets();
      SOURCE_MASK.clear();
      WORKSPACE_SUBMISSION.clear();
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
   public static void onDisconnected() {
      clearFailedWorkspaceTargets();
      ClientPlayerSession session = ClientSessionManager.instance().currentSession();
      if (session != null) {
         // The server preview is already inactive when the task was queued, and the
         // server may have cancelled the selection. Use the identity that the send
         // captured, so a submitted draft still reaches the disk.
         ClientSessionManager.instance().suspendCurrentDraft(
            WORKSPACE_SUBMISSION.identity(), outstandingSubmissionOrigin(), WORKSPACE_SUBMISSION.transferId()
         );
      }
      FALLBACK_SELECTION_SESSION.clearLiveInteraction();
      WorkspacePreviewComposer.invalidatePartGeometry();
      WORKSPACE_SUBMISSION.clear();
      reconnectBoundaryArmed = true;
      reconnectBoundarySawSnapshot = false;
      reconnectBoundaryIdleTicks = 0;
      pendingReconnectPreview = null;
      serverPreview = OperationPreviewPayload.inactive();
      lastServerPreviewRevision = -1L;
      SOURCE_MASK.clear();
   }

   /**
    * Origin to record when the disconnect has no server identity for the live draft.
    *
    * <p>Only a workspace that holds no world part at all is proven client-only. Every
    * other workspace needs a server identity, so the draft is kept as already staged
    * instead of being overwritten without one.</p>
    */
   private static OperationSubmissionOrigin outstandingSubmissionOrigin() {
      boolean localOnly = !workspace().parts().isEmpty() && workspace().parts().stream()
         .allMatch(part -> part.source() == ClientSelectionPart.Source.CLIPBOARD);
      return localOnly ? OperationSubmissionOrigin.LOCAL_ONLY : null;
   }

   /** Ends a reconnect boundary once the snapshot that followed it has settled. */
   public static void onClientTick() {
      selectionSession().publishInteractionScene();
      WorkspacePreviewComposer.prunePartGeometry();
      if (workspaceSubmissionPending() && WORKSPACE_SUBMISSION.tick()) {
         UUID expiredTransferId = WORKSPACE_SUBMISSION.transferId();
         WORKSPACE_SUBMISSION.clear();
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
      if (!reconnectBoundaryArmed) {
         return;
      }
      if (reconnectBoundarySawSnapshot) {
         reconnectBoundaryArmed = false;
         reconnectBoundarySawSnapshot = false;
         reconnectBoundaryIdleTicks = 0;
         // The environment returned a snapshot, so the connection is live and the scope
         // is bound. Any submission that was still open may have finished while the
         // player was away, so ask once for its result.
         queryOutstandingSubmissionReceipts();
         return;
      }
      if (++reconnectBoundaryIdleTicks >= RECONNECT_BOUNDARY_IDLE_TICKS) {
         reconnectBoundaryArmed = false;
         reconnectBoundaryIdleTicks = 0;
         // Silence is not a server rejection. Keep the draft so a delayed
         // snapshot can still offer restoration through shouldHoldStoredDraft.
      }
   }

   public static void observeServerActivity(FastPlaceActivity activity) {
      if (workspaceSubmissionPending()) {
         WORKSPACE_SUBMISSION.observeActivity(activity);
      }
   }

   public static boolean workspaceSubmissionPending() {
      return WORKSPACE_SUBMISSION.pending(selectionSession().interactionOwnerId());
   }

   private static Optional<OperationClipboard> loadClipboard(Minecraft minecraft) {
      if (minecraft.level == null) {
         return Optional.empty();
      }
      try {
         return CLIPBOARD_STATE.load(() -> {
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

   private static Path clipboardFile() {
      return FMLPaths.CONFIGDIR.get().resolve(CLIPBOARD_FILE_NAME);
   }

   private static Map<BlockPos, ClientBlockSnapshot> capture(OperationSelectionVolume selection) {
      return io.github.fastformer.client.operation.selection.SelectionBlockCapture.capture(selection);
   }

   private static Map<BlockPos, ClientBlockSnapshot> snapshotForSelection(
      ClientSelectionPart baseline, OperationSelectionVolume selection
   ) {
      return io.github.fastformer.client.operation.selection.SelectionBlockCapture.resize(baseline, selection);
   }

   private static boolean finishDraft(int prismBaseCount) {
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
      boolean added = workspace().addPartsWithoutHistory(List.of(new ClientSelectionPart(
         0,
         ClientSelectionPart.Source.WORLD,
         selection,
         capture(selection),
         WorkspaceTransform.IDENTITY,
         false
      )));
      if (added) {
         selectionSession().clearDraft();
         synchronizeDerivedWorkspaceState();
      }
      return added;
   }

   private static DraftSnapshot draftSnapshot() {
      return new DraftSnapshot(
         selectionSession().draftPoints(),
         selectionSession().prismBaseCount(),
         selectionSession().draftMinPoint(),
         selectionSession().draftMaxPoint(),
         workspace().partIds(),
         workspace().selectionState()
      );
   }

   /** Records one inverse for the complete click, including selection clearing and draft changes. */
   private static void recordDraftEvent(DraftSnapshot before) {
      DraftSnapshot after = draftSnapshot();
      if (before.equals(after)) {
         return;
      }
      workspace().pushEvent(() -> {
         workspace().restoreParts(before.partIds());
         selectionSession().restoreDraftEdit(new ClientSelectionSession.DraftState(
            selectionSession().selectionMode(), before.points(), before.prismBaseCount(), before.minPoint(), before.maxPoint()
         ));
         workspace().restoreSelectionStateWithoutHistory(before.selection());
      }, ClientOperationEventStack.Retention.of(
         // The inverse keeps the draft points, the part id set and the
         // selection alive. The block payloads belong to the workspace parts,
         // which this event only re-filters.
         1 + before.points().size() + before.partIds().size() + before.selection().ids().size()
      ));
   }

   private static boolean selectionReady(OperationPreviewPayload payload) {
      OperationSelectionMode mode = payload.operationSelectionMode();
      return mode == OperationSelectionMode.CUBOID
         ? payload.hasFirst() && payload.hasSecond()
         : mode == OperationSelectionMode.PRISM
            ? payload.operationPrismBasePointCount() >= 3
               && payload.points().size() > payload.operationPrismBasePointCount()
            : payload.points().size() >= mode.requiredPoints();
   }

   private static void synchronizeDerivedWorkspaceState() {
      selectionSession().publishInteractionScene();
      SOURCE_MASK.replace(SourceBlockRenderMask.maskedSourcePositions(workspace().parts()));
   }

   private record DraftSnapshot(
      List<BlockPos> points,
      int prismBaseCount,
      BlockPos minPoint,
      BlockPos maxPoint,
      java.util.Set<Integer> partIds,
      ClientOperationWorkspace.SelectionState selection
   ) {
   }

}
