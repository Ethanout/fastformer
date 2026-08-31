package io.github.fastformer.client.operation.controller;

import io.github.fastformer.fastplace.OperationSelectionMode;
import io.github.fastformer.fastplace.OperationSelectionVolume;
import io.github.fastformer.fastplace.OperationWorkspacePlan;
import io.github.fastformer.fastplace.geometry.AxisGizmo;
import io.github.fastformer.client.placement.ClientPlacementRouter;
import io.github.fastformer.client.operation.clipboard.OperationClipboard;
import io.github.fastformer.client.operation.clipboard.OperationClipboardCodec;
import io.github.fastformer.client.operation.clipboard.OperationClipboardStore;
import io.github.fastformer.client.operation.clipboard.PastePlacement;
import io.github.fastformer.client.operation.model.ClientBlockSnapshot;
import io.github.fastformer.client.operation.model.ClientSelectionPart;
import io.github.fastformer.client.operation.model.WorkspaceTransform;
import io.github.fastformer.client.operation.selection.ClientSelectionSession;
import io.github.fastformer.client.operation.selection.ClientSelectionState;
import io.github.fastformer.client.operation.selection.OccupiedBlockBounds;
import io.github.fastformer.client.operation.preview.SourceBlockRenderMask;
import io.github.fastformer.client.operation.preview.WorkspacePreviewComposer;
import io.github.fastformer.client.operation.workspace.ClientOperationWorkspace;
import io.github.fastformer.client.session.ClientPlayerSession;
import io.github.fastformer.client.session.ClientSessionManager;
import io.github.fastformer.network.payload.operation.OperationPreviewPayload;
import io.github.fastformer.network.payload.operation.OperationWorkspaceResultPayload;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.loading.FMLPaths;

/** Bridges the pure client workspace to Minecraft input, world capture and persistent clipboard storage. */
public final class ClientOperationController {
   private static final Path CLIPBOARD_FILE = FMLPaths.CONFIGDIR.get()
      .resolve("fastformer-operation-clipboard.nbt.gz");
   private static final ClientOperationWorkspace FALLBACK_WORKSPACE = new ClientOperationWorkspace();
   private static final SourceBlockRenderMask SOURCE_MASK = new SourceBlockRenderMask();
   private static final ClientSelectionSession FALLBACK_SELECTION_SESSION = new ClientSelectionSession();
   private static OperationClipboard clipboard;
   private static boolean clipboardLoaded;
   private static boolean workspaceSubmissionPending;
   private static UUID pendingWorkspaceTransferId;
   private static OperationPreviewPayload serverPreview = OperationPreviewPayload.inactive();
   private static long lastServerPreviewRevision = -1L;

   private ClientOperationController() {
   }

   static {
      FALLBACK_WORKSPACE.setChangeListener(ClientOperationController::refreshInteractionState);
   }

   public static ClientOperationWorkspace workspace() {
      Minecraft minecraft = Minecraft.getInstance();
      if (minecraft.player == null) {
         return FALLBACK_WORKSPACE;
      }
      ClientOperationWorkspace workspace = ClientSessionManager.instance()
         .forPlayer(minecraft.player.getUUID())
         .operationWorkspace();
      workspace.setChangeListener(ClientOperationController::refreshInteractionState);
      return workspace;
   }

   private static ClientSelectionSession selectionSession() {
      Minecraft minecraft = Minecraft.getInstance();
      if (minecraft.player == null) {
         ClientPlayerSession current = ClientSessionManager.instance().currentSession();
         return current == null ? FALLBACK_SELECTION_SESSION : current.selectionSession();
      }
      return ClientSessionManager.instance()
         .forPlayer(minecraft.player.getUUID())
         .selectionSession();
   }

   public static SourceBlockRenderMask sourceMask() {
      return SOURCE_MASK;
   }

   public static boolean active() {
      return !workspace().isEmpty();
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
      refreshInteractionState();
      return selectionSession().state();
   }

   public static void setAltMode(boolean enabled) {
      if (enabled && !workspace().selectedIds().isEmpty()) {
         workspace().clearSelectionForNewDraftWithoutHistory();
      }
      selectionSession().setAltHeld(enabled);
      refreshInteractionState();
   }

   public static boolean selectionDraftActive() {
      return selectionSession().hasDraft();
   }

   private static void refreshInteractionState() {
      refreshInteractionState(workspace());
   }

   private static void refreshInteractionState(ClientOperationWorkspace workspace) {
      selectionSession().refresh(
         !workspace.selectedIds().isEmpty(),
         workspace.isEmpty(),
         serverPreview.active() && !selectionReady(serverPreview)
      );
   }

   public static boolean synchronize(OperationPreviewPayload payload) {
      if (payload == null) {
         return false;
      }
      ClientSessionManager sessionManager = ClientSessionManager.instance();
      // A dimension change can deliver the server's inactive snapshot before
      // the regular client tick observes the new world instance. Gate this
      // packet before it can erase the player's retained workspace.
      if (!sessionManager.acceptAuthoritativeSnapshot(Minecraft.getInstance(), payload.active())) {
         return false;
      }
      if (payload.operationRevision() < lastServerPreviewRevision) {
         return false;
      }
      boolean previousServerOperationActive = serverPreview.active();
      lastServerPreviewRevision = payload.operationRevision();
      serverPreview = payload;
      if (!payload.active()) {
         if (previousServerOperationActive
            && !workspaceSubmissionPending
            && !sessionManager.isAwaitingAuthoritativeSnapshot()) {
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
      if (selection == null) {
         return true;
      }
      Map<BlockPos, ClientBlockSnapshot> blocks = capture(selection);
      WorkspaceTransform transform = new WorkspaceTransform(
         Vec3.atLowerCornerOf(payload.operationTranslation()),
         payload.operationRotation(),
         new io.github.fastformer.fastplace.OperationStackRegion(
            payload.operationStackMin(), payload.operationStackMax()
         )
      );
      workspace().addParts(List.of(new ClientSelectionPart(
         0, ClientSelectionPart.Source.WORLD, selection, blocks, transform, false
      )));
      workspace().clearHistory();
      refreshInteractionState();
      refreshSourceMask();
      return true;
   }

   public static boolean copySelected() {
      Optional<OperationClipboard> copied = OperationClipboard.fromWorkspace(workspace());
      if (copied.isEmpty()) {
         return false;
      }
      clipboard = copied.orElseThrow();
      clipboardLoaded = true;
      try {
         OperationClipboardStore.save(CLIPBOARD_FILE, OperationClipboardCodec.encode(clipboard));
         return true;
      } catch (IOException | RuntimeException exception) {
         return false;
      }
   }

   public static boolean paste(Minecraft minecraft) {
      if (workspaceSubmissionPending) {
         return false;
      }
      OperationClipboard value = loadClipboard(minecraft).orElse(null);
      if (value == null || value.parts().size() > ClientOperationWorkspace.MAX_PARTS - workspace().size()) {
         return false;
      }
      Vec3 offset;
      if (active()) {
         offset = PastePlacement.inWorkspace(value.bounds());
      } else {
         HitResult hitResult = minecraft.hitResult;
         if (!(hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
            return false;
         }
         offset = PastePlacement.atSurface(value.bounds(), hit.getLocation(), hit.getDirection());
      }
      List<ClientSelectionPart> parts = value.instantiate().stream()
         .map(part -> part.withTranslation(offset))
         .toList();
      boolean added = workspace().addParts(parts);
      if (added) {
         refreshSourceMask();
      }
      return added;
   }

   public static boolean markSelectedForDeletion() {
      if (workspaceSubmissionPending || workspace().selectedIds().isEmpty() || !workspace().beginEdit()) {
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
      refreshSourceMask();
      return changed;
   }

   public static boolean removeSelectedParts() {
      if (workspaceSubmissionPending) {
         return false;
      }
      boolean changed = workspace().removeSelectedParts();
      refreshSourceMask();
      return changed;
   }

   public static boolean handleCreateClick(int mouseButton, BlockPos point) {
      DraftSnapshot before = draftSnapshot();
      boolean handled = handleCreateClickInternal(mouseButton, point);
      recordDraftEvent(before);
      return handled;
   }

   private static boolean handleCreateClickInternal(int mouseButton, BlockPos point) {
      if (workspaceSubmissionPending || point == null || workspace().size() >= ClientOperationWorkspace.MAX_PARTS) {
         return false;
      }
      if (selectionSession().selectionMode() == OperationSelectionMode.CUBOID) {
         if (mouseButton == 0) {
            selectionSession().clearDraft();
            selectionSession().addDraftPoint(point);
            refreshInteractionState();
            return true;
         }
         if (mouseButton == 1 && selectionSession().draftSize() == 1) {
            selectionSession().addDraftPoint(point);
            boolean result = finishDraft(0); refreshInteractionState(); return result;
         }
         if (mouseButton == 2) {
            if (!selectionSession().hasDraft()) {
               selectionSession().addDraftPoint(point);
               refreshInteractionState(); return true;
            }
            if (selectionSession().draftSize() == 1) {
               selectionSession().addDraftPoint(point);
               boolean result = finishDraft(0); refreshInteractionState(); return result;
            }
         }
         return false;
      }
      if (selectionSession().selectionMode() == OperationSelectionMode.PRISM) {
         if (mouseButton == 0) {
            if (selectionSession().hasDraft()) {
               selectionSession().removeLastDraftPoint();
               if (selectionSession().draftSize() < selectionSession().prismBaseCount()) {
                  selectionSession().setPrismBaseCount(0);
               }
               return true;
            }
            return false;
         }
         if (mouseButton != 1) {
            if (mouseButton == 2) {
               selectionSession().addDraftPoint(point);
               boolean result = selectionSession().prismBaseCount() > 0
                  && selectionSession().draftSize() > selectionSession().prismBaseCount()
                  ? finishDraft(selectionSession().prismBaseCount()) : true; refreshInteractionState(); return result;
            }
            return false;
         }
         if (selectionSession().prismBaseCount() == 0
            && selectionSession().draftSize() >= 3
            && point.equals(selectionSession().draftFirst())) {
            selectionSession().setPrismBaseCount(selectionSession().draftSize());
            refreshInteractionState(); return true;
         }
         selectionSession().addDraftPoint(point);
         boolean result = selectionSession().prismBaseCount() > 0
            && selectionSession().draftSize() > selectionSession().prismBaseCount()
            ? finishDraft(selectionSession().prismBaseCount()) : true; refreshInteractionState(); return result;
      }
      return false;
   }

   /** Alt-prefixed creation deliberately bypasses all existing-part hit testing. */
   public static boolean handleAltCreateClick(int mouseButton, BlockPos point) {
      DraftSnapshot before = draftSnapshot();
      boolean handled = handleAltCreateClickInternal(mouseButton, point);
      recordDraftEvent(before);
      return handled;
   }

   private static boolean handleAltCreateClickInternal(int mouseButton, BlockPos point) {
      if (workspaceSubmissionPending || point == null) {
         return false;
      }
      if (selectionSession().selectionMode() == OperationSelectionMode.PRISM && mouseButton == 2) {
         return false;
      }
      if (!workspace().selectedIds().isEmpty()) {
         workspace().clearSelectionForNewDraftWithoutHistory();
      }
      boolean handled;
      if (selectionSession().selectionMode() == OperationSelectionMode.PRISM) {
         if (!selectionSession().hasDraft()) {
            selectionSession().addDraftPoint(point);
            selectionSession().setPrismBaseCount(0);
            handled = true;
         } else if (selectionSession().prismBaseCount() == 0
            && point.equals(selectionSession().draftFirst())
            && selectionSession().draftSize() >= 3) {
            selectionSession().setPrismBaseCount(selectionSession().draftSize());
            handled = true;
         } else {
            selectionSession().addDraftPoint(point);
            handled = selectionSession().prismBaseCount() > 0
               && selectionSession().draftSize() > selectionSession().prismBaseCount()
               ? finishDraft(selectionSession().prismBaseCount()) : true;
         }
      } else if (mouseButton == 0) {
         selectionSession().clearDraft();
         selectionSession().addDraftPoint(point);
         handled = true;
      } else if (mouseButton == 1 || mouseButton == 2) {
         if (mouseButton == 2) {
            if (selectionSession().draftSize() < 2) {
               selectionSession().addDraftPoint(point);
               handled = true;
            } else {
               handled = selectionSession().expandDraftTo(point);
            }
         } else {
            selectionSession().addDraftPoint(point);
            handled = selectionSession().draftSize() > 1 ? finishDraft(0) : true;
         }
      } else {
         handled = false;
      }
      return handled;
   }

   public static boolean activeSelectionTransformed() {
      return active() && workspace().part(workspace().activeId()).map(ClientSelectionPart::transformed).orElse(false);
   }

   public static boolean undo() {
      if (workspaceSubmissionPending) {
         return false;
      }
      boolean changed = workspace().undo();
      refreshSourceMask();
      return changed;
   }

   public static boolean moveSelected(BlockPos offset) {
      if (workspaceSubmissionPending || offset == null || offset.equals(BlockPos.ZERO)
         || workspace().selectedIds().isEmpty() || !workspace().beginEdit()) {
         return false;
      }
      Vec3 delta = Vec3.atLowerCornerOf(offset);
      for (ClientSelectionPart part : List.copyOf(workspace().selectedParts())) {
         workspace().updatePart(part.withTranslation(part.transform().translation().add(delta)));
      }
      boolean changed = workspace().finishEdit();
      refreshSourceMask();
      return changed;
   }

   public static boolean canAdjustAabbFace(ClientSelectionPart part) {
      return part != null && part.canAdjustGeometry();
   }

   /** Checks the source only when an adjustment gesture is actually starting. */
   public static boolean sourceSnapshotMatches(ClientSelectionPart part) {
      return part != null && canAdjustAabbFace(part)
         && Minecraft.getInstance().level != null
         && capture(part.selection()).equals(part.blocks());
   }

   public static boolean adjustActiveAabbPoint(int mouseButton, BlockPos worldPoint) {
      if (workspaceSubmissionPending || worldPoint == null || mouseButton < 0 || mouseButton > 2) {
         return false;
      }
      ClientSelectionPart part = workspace().part(workspace().activeId()).orElse(null);
      if (!sourceSnapshotMatches(part) || !workspace().beginEdit()) {
         return false;
      }
      Vec3 translation = part.transform().translation();
      BlockPos localPoint = new BlockPos(
         Mth.floor(worldPoint.getX() - translation.x),
         Mth.floor(worldPoint.getY() - translation.y),
         Mth.floor(worldPoint.getZ() - translation.z)
      );
      AABB bounds = part.selection().bounds();
      BlockPos first = new BlockPos(Mth.floor(bounds.minX), Mth.floor(bounds.minY), Mth.floor(bounds.minZ));
      BlockPos second = new BlockPos(
         Mth.ceil(bounds.maxX) - 1, Mth.ceil(bounds.maxY) - 1, Mth.ceil(bounds.maxZ) - 1
      );
      if (mouseButton == 0) {
         first = localPoint;
      } else if (mouseButton == 1) {
         second = localPoint;
      } else {
         first = new BlockPos(
            Math.min(first.getX(), localPoint.getX()),
            Math.min(first.getY(), localPoint.getY()),
            Math.min(first.getZ(), localPoint.getZ())
         );
         second = new BlockPos(
            Math.max(second.getX(), localPoint.getX()),
            Math.max(second.getY(), localPoint.getY()),
            Math.max(second.getZ(), localPoint.getZ())
         );
      }
      OperationSelectionVolume selection = OperationSelectionVolume.create(
         OperationSelectionMode.CUBOID, List.of(first, second), 0,
         BlockPos.ZERO, BlockPos.ZERO, 0
      );
      if (selection == null) {
         workspace().cancelEdit();
         return false;
      }
      WorkspaceTransform transform = part.transform().withRepeats(
         part.transform().repeats(), BlockPos.ZERO
      );
      workspace().updatePart(
         part.withSelection(selection).withBlocks(snapshotForSelection(part, selection)).withTransform(transform)
      );
      boolean changed = workspace().finishEdit();
      refreshSourceMask();
      return changed;
   }

   public static void updateAabbFaceGesture(
      ClientOperationWorkspace.EditToken editToken,
      ClientSelectionPart baseline, int axis, boolean positiveFace, int outwardSteps
   ) {
      if (workspaceSubmissionPending || !workspace().ownsEdit(editToken)
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
      refreshSourceMask();
   }

   public static void updateTransformGesture(
      ClientOperationWorkspace.EditToken editToken,
      List<ClientSelectionPart> baseline,
      boolean common,
      AxisGizmo.Operation operation,
      AxisGizmo.Axis axis,
      int direction,
      int totalSteps,
      double rotationRadians
   ) {
      if (workspaceSubmissionPending || !workspace().ownsEdit(editToken)) {
         return;
      }
      baseline.forEach(workspace()::updatePart);
      if (totalSteps == 0) {
         refreshSourceMask();
         return;
      }
      int directedSteps = direction * totalSteps;
      OccupiedBlockBounds groupBounds = common ? baseline.stream()
         .map(WorkspacePreviewComposer::resolve)
         .filter(values -> !values.isEmpty())
         .map(values -> OccupiedBlockBounds.from(values.keySet()).orElseThrow())
         .reduce(OccupiedBlockBounds::union)
         .orElse(null) : null;
      for (ClientSelectionPart part : baseline) {
         WorkspaceTransform transform = part.transform();
         WorkspaceTransform updated = switch (operation) {
            case MOVE -> transform.withTranslation(transform.translation().add(axisVector(axis).scale(directedSteps)));
            case SCALE -> {
               if (part.selection() != null && part.selection().prism() != null) {
                  OccupiedBlockBounds sourceBounds = OccupiedBlockBounds.from(part.blocks().keySet()).orElseThrow();
                  int sourceWidth = sourceBounds.width(axis);
                  double currentScale = axisComponent(transform.scale(), axis);
                  int currentWidth = Math.max(1, (int)Math.round(sourceWidth * currentScale));
                  int targetWidth = Math.max(1, currentWidth + totalSteps);
                  double targetScale = targetWidth / (double)sourceWidth;
                  double centerShift = (targetWidth - currentWidth) * 0.5 * direction;
                  Vec3 localAxis = rotateVector(axisVector(axis), transform.rotation());
                  yield transform.withScale(axis, targetScale)
                     .withTranslation(transform.translation().add(localAxis.scale(centerShift)));
               }
               int stride = common && groupBounds != null ? groupBounds.width(axis) : 0;
               WorkspaceTransform axisStride = transform.withRepeatStride(axis, stride);
               yield axisStride.withRepeats(transform.repeats().withAxisEndpoint(axis, direction, totalSteps), axisStride.repeatStride());
            }
            case ROTATE -> {
               double radians = Double.isFinite(rotationRadians)
                  ? rotationRadians
                  : totalSteps * Math.PI * 2.0 / 1024.0;
               Vec3 rotation = transform.rotation().add(axisVector(axis).scale(radians));
               Vec3 translation = transform.translation();
               if (common && groupBounds != null) {
                  Map<BlockPos, ClientBlockSnapshot> current = WorkspacePreviewComposer.resolve(part);
                  if (!current.isEmpty()) {
                     Vec3 center = OccupiedBlockBounds.from(current.keySet()).orElseThrow().center();
                     Vec3 revolved = rotateAround(center, groupBounds.center(), axis, radians);
                     translation = translation.add(revolved.subtract(center));
                  }
               }
               yield new WorkspaceTransform(
                  translation, rotation, transform.repeats(), transform.repeatStride(), transform.scale()
               );
            }
         };
         if (!WorkspacePreviewComposer.canResolveForRendering(part.blocks(), updated)) {
            baseline.forEach(workspace()::updatePart);
            refreshSourceMask();
            return;
         }
         workspace().updatePart(part.withTransform(updated));
      }
      refreshSourceMask();
   }

   public static boolean finishTransformGesture(ClientOperationWorkspace.EditToken editToken) {
      boolean changed = workspace().finishEdit(editToken);
      refreshSourceMask();
      return changed;
   }

   public static boolean submitWorkspace(Minecraft minecraft) {
      if (workspaceSubmissionPending || workspace().editing()
         || minecraft == null || minecraft.getConnection() == null || workspace().isEmpty()) {
         return false;
      }
      try {
         OperationWorkspacePlan plan = new OperationWorkspacePlan(workspace().parts().stream().map(part ->
            new OperationWorkspacePlan.Part(
               part.id(),
               part.source(),
               part.pendingDelete() && !part.sourceSnapshot().isEmpty() ? part.sourceSnapshot() : part.blocks(),
               part.transform(),
               part.pendingDelete()
            )
         ).toList());
         ClientPlacementRouter.WorkspaceSubmission submission = ClientPlacementRouter
            .prepareWorkspace(minecraft, plan).orElse(null);
         if (submission == null) return false;
         UUID transferId = submission.transferId();
         workspaceSubmissionPending = true;
         pendingWorkspaceTransferId = transferId;
         workspace().setLocked(true);
         submission.send();
         return true;
      } catch (IOException | RuntimeException exception) {
         workspaceSubmissionPending = false;
         pendingWorkspaceTransferId = null;
         workspace().setLocked(false);
         return false;
      }
   }

   public static void applyWorkspaceResult(OperationWorkspaceResultPayload payload) {
      if (payload == null || !workspaceSubmissionPending
         || !payload.transferId().equals(pendingWorkspaceTransferId)) {
         return;
      }
      workspaceSubmissionPending = false;
      pendingWorkspaceTransferId = null;
      workspace().setLocked(false);
      if (payload.accepted()) {
         clearWorkspace();
         return;
      }
      if (!payload.failedPartIds().isEmpty()) {
         workspace().selectOnly(payload.failedPartIds().getFirst());
         for (int index = 1; index < payload.failedPartIds().size(); index++) {
            workspace().toggleSelected(payload.failedPartIds().get(index));
         }
      }
   }

   public static void cancelTransformGesture(ClientOperationWorkspace.EditToken editToken) {
      workspace().cancelEdit(editToken);
      refreshSourceMask();
   }

   public static void clearWorkspace() {
      workspace().clear();
      SOURCE_MASK.clear();
      selectionSession().clearDraft();
      selectionSession().setAltHeld(false);
      refreshInteractionState();
      workspaceSubmissionPending = false;
      pendingWorkspaceTransferId = null;
   }

   /**
    * Clears connection-local interaction state without deleting the player's session box.
    * The workspace and its history remain attached to the player's UUID for reconnects,
    * respawns, and dimension changes.
    */
   public static void onDisconnected() {
      ClientPlayerSession session = ClientSessionManager.instance().currentSession();
      ClientOperationWorkspace playerWorkspace = session == null ? FALLBACK_WORKSPACE : session.operationWorkspace();
      // Keep the player's edit baseline and draft across reconnects, respawns,
      // and dimension changes. Only the transport lock is connection-local.
      playerWorkspace.setLocked(false);
      workspaceSubmissionPending = false;
      pendingWorkspaceTransferId = null;
      serverPreview = OperationPreviewPayload.inactive();
      lastServerPreviewRevision = -1L;
      SOURCE_MASK.clear();
      refreshInteractionState(playerWorkspace);
   }

   public static boolean workspaceSubmissionPending() {
      return workspaceSubmissionPending;
   }

   private static Optional<OperationClipboard> loadClipboard(Minecraft minecraft) {
      if (clipboardLoaded) {
         return Optional.ofNullable(clipboard);
      }
      clipboardLoaded = true;
      if (minecraft.level == null) {
         return Optional.empty();
      }
      try {
         CompoundTag root = OperationClipboardStore.load(CLIPBOARD_FILE).orElse(null);
         if (root == null) {
            return Optional.empty();
         }
         clipboard = OperationClipboardCodec.decode(
            root, minecraft.level.registryAccess().lookupOrThrow(Registries.BLOCK)
         );
         return Optional.of(clipboard);
      } catch (IOException | RuntimeException exception) {
         return Optional.empty();
      }
   }

   private static Map<BlockPos, ClientBlockSnapshot> capture(OperationSelectionVolume selection) {
      Minecraft minecraft = Minecraft.getInstance();
      if (minecraft.level == null) {
         return Map.of();
      }
      AABB bounds = selection.bounds();
      LinkedHashMap<BlockPos, ClientBlockSnapshot> result = new LinkedHashMap<>();
      for (int x = Mth.floor(bounds.minX); x < Mth.ceil(bounds.maxX); x++) {
         for (int y = Mth.floor(bounds.minY); y < Mth.ceil(bounds.maxY); y++) {
            for (int z = Mth.floor(bounds.minZ); z < Mth.ceil(bounds.maxZ); z++) {
               BlockPos pos = new BlockPos(x, y, z);
               var state = minecraft.level.getBlockState(pos);
               if (state.isAir() || !selection.intersects(new AABB(pos))) {
                  continue;
               }
               CompoundTag blockEntityTag = null;
               try {
                  BlockEntity entity = minecraft.level.getBlockEntity(pos);
                  if (entity != null) {
                     blockEntityTag = entity.saveWithFullMetadata(minecraft.level.registryAccess());
                  }
               } catch (RuntimeException ignored) {
                  // A failed client-only NBT preview does not make the world unsafe;
                  // the server will capture and validate again before applying.
               }
               result.put(pos, new ClientBlockSnapshot(state, blockEntityTag));
            }
         }
      }
      return Map.copyOf(result);
   }

   private static Map<BlockPos, ClientBlockSnapshot> snapshotForSelection(
      ClientSelectionPart baseline, OperationSelectionVolume selection
   ) {
      if (baseline.source() == ClientSelectionPart.Source.WORLD) {
         LinkedHashMap<BlockPos, ClientBlockSnapshot> result = new LinkedHashMap<>();
         baseline.blocks().forEach((pos, snapshot) -> {
            if (selection.intersects(new AABB(pos))) {
               result.put(pos.immutable(), snapshot);
            }
         });
         result.putAll(captureMissing(selection, baseline.blocks().keySet()));
         return Map.copyOf(result);
      }
      LinkedHashMap<BlockPos, ClientBlockSnapshot> retained = new LinkedHashMap<>();
      baseline.blocks().forEach((pos, snapshot) -> {
         if (selection.intersects(new AABB(pos))) {
            retained.put(pos.immutable(), snapshot);
         }
      });
      return Map.copyOf(retained);
   }

   private static Map<BlockPos, ClientBlockSnapshot> captureMissing(
      OperationSelectionVolume selection, java.util.Set<BlockPos> alreadyCaptured
   ) {
      Minecraft minecraft = Minecraft.getInstance();
      if (minecraft.level == null) {
         return Map.of();
      }
      AABB bounds = selection.bounds();
      LinkedHashMap<BlockPos, ClientBlockSnapshot> result = new LinkedHashMap<>();
      for (int x = Mth.floor(bounds.minX); x < Mth.ceil(bounds.maxX); x++) {
         for (int y = Mth.floor(bounds.minY); y < Mth.ceil(bounds.maxY); y++) {
            for (int z = Mth.floor(bounds.minZ); z < Mth.ceil(bounds.maxZ); z++) {
               BlockPos pos = new BlockPos(x, y, z);
               if (alreadyCaptured.contains(pos) || !selection.intersects(new AABB(pos))) {
                  continue;
               }
               var state = minecraft.level.getBlockState(pos);
               if (state.isAir()) {
                  continue;
               }
               CompoundTag blockEntityTag = null;
               try {
                  BlockEntity entity = minecraft.level.getBlockEntity(pos);
                  if (entity != null) {
                     blockEntityTag = entity.saveWithFullMetadata(minecraft.level.registryAccess());
                  }
               } catch (RuntimeException ignored) {
                  // Keep the visual snapshot usable when block-entity capture is unavailable.
               }
               result.put(pos.immutable(), new ClientBlockSnapshot(state, blockEntityTag));
            }
         }
      }
      return result;
   }

   private static boolean finishDraft(int prismBaseCount) {
      OperationSelectionVolume selection = OperationSelectionVolume.create(
         selectionSession().selectionMode(),
         selectionSession().selectionMode() == OperationSelectionMode.CUBOID
            ? selectionSession().selectionDraftPoints() : selectionSession().draftPoints(),
         prismBaseCount,
         BlockPos.ZERO,
         BlockPos.ZERO,
         0
      );
      if (selection == null) {
         return false;
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
         refreshInteractionState();
         refreshSourceMask();
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
         selectionSession().restoreDraft(before.points(), before.prismBaseCount());
         selectionSession().restoreDraftBounds(before.minPoint(), before.maxPoint());
         workspace().restoreSelectionStateWithoutHistory(before.selection());
      });
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

   private static void refreshSourceMask() {
      java.util.LinkedHashSet<BlockPos> masked = new java.util.LinkedHashSet<>();
      for (ClientSelectionPart part : workspace().parts()) {
         if (part.source() != ClientSelectionPart.Source.WORLD) {
            continue;
         }
         if (part.masksSourceBlocks()) {
            masked.addAll(part.sourceSnapshot().keySet());
         }
      }
      SOURCE_MASK.replace(masked);
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

   private static Vec3 axisVector(AxisGizmo.Axis axis) {
      return switch (axis) {
         case X -> new Vec3(1.0, 0.0, 0.0);
         case Y -> new Vec3(0.0, 1.0, 0.0);
         case Z -> new Vec3(0.0, 0.0, 1.0);
      };
   }

   private static Vec3 rotateAround(Vec3 point, Vec3 pivot, AxisGizmo.Axis axis, double radians) {
      Vec3 value = point.subtract(pivot);
      double sin = Math.sin(radians);
      double cos = Math.cos(radians);
      Vec3 rotated = switch (axis) {
         case X -> new Vec3(value.x, value.y * cos - value.z * sin, value.y * sin + value.z * cos);
         case Y -> new Vec3(value.x * cos + value.z * sin, value.y, -value.x * sin + value.z * cos);
         case Z -> new Vec3(value.x * cos - value.y * sin, value.x * sin + value.y * cos, value.z);
      };
      return rotated.add(pivot);
   }

   private static double axisComponent(Vec3 value, AxisGizmo.Axis axis) {
      return switch (axis) {
         case X -> value.x;
         case Y -> value.y;
         case Z -> value.z;
      };
   }

   private static Vec3 rotateVector(Vec3 value, Vec3 rotation) {
      double xSin = Math.sin(rotation.x);
      double xCos = Math.cos(rotation.x);
      value = new Vec3(value.x, value.y * xCos - value.z * xSin, value.y * xSin + value.z * xCos);
      double ySin = Math.sin(rotation.y);
      double yCos = Math.cos(rotation.y);
      value = new Vec3(value.x * yCos + value.z * ySin, value.y, -value.x * ySin + value.z * yCos);
      double zSin = Math.sin(rotation.z);
      double zCos = Math.cos(rotation.z);
      return new Vec3(value.x * zCos - value.y * zSin, value.x * zSin + value.y * zCos, value.z);
   }
}
