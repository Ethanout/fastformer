package io.github.fastformer.client.operation;

import io.github.fastformer.fastplace.OperationSelectionMode;
import io.github.fastformer.fastplace.OperationSelectionVolume;
import io.github.fastformer.fastplace.OperationWorkspacePlan;
import io.github.fastformer.fastplace.OperationWorkspacePlanCodec;
import io.github.fastformer.fastplace.geometry.AxisGizmo;
import io.github.fastformer.network.OperationPreviewPayload;
import io.github.fastformer.network.OperationWorkspaceApplyPayload;
import io.github.fastformer.network.OperationWorkspaceResultPayload;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Arrays;
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
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.network.PacketDistributor;

/** Bridges the pure client workspace to Minecraft input, world capture and persistent clipboard storage. */
public final class ClientOperationController {
   private static final Path CLIPBOARD_FILE = FMLPaths.CONFIGDIR.get()
      .resolve("fastformer-operation-clipboard.nbt.gz");
   private static final ClientOperationWorkspace WORKSPACE = new ClientOperationWorkspace();
   private static final SourceBlockRenderMask SOURCE_MASK = new SourceBlockRenderMask();
   private static OperationClipboard clipboard;
   private static boolean clipboardLoaded;
   private static boolean serverOperationSeen;
   private static boolean workspaceSubmissionPending;
   private static OperationSelectionMode currentSelectionMode = OperationSelectionMode.CUBOID;
   private static final java.util.ArrayList<BlockPos> draftPoints = new java.util.ArrayList<>();
   private static int draftPrismBaseCount;

   private ClientOperationController() {
   }

   public static ClientOperationWorkspace workspace() {
      return WORKSPACE;
   }

   public static SourceBlockRenderMask sourceMask() {
      return SOURCE_MASK;
   }

   public static boolean active() {
      return !WORKSPACE.isEmpty();
   }

   public static void synchronize(OperationPreviewPayload payload) {
      if (payload == null) {
         return;
      }
      if (!payload.active()) {
         if (serverOperationSeen) {
            serverOperationSeen = false;
            if (!workspaceSubmissionPending) {
               clearWorkspace();
            }
         }
         return;
      }
      serverOperationSeen = true;
      currentSelectionMode = payload.operationSelectionMode();
      if (!WORKSPACE.isEmpty() || !selectionReady(payload)) {
         return;
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
         return;
      }
      Map<BlockPos, ClientBlockSnapshot> blocks = capture(selection);
      WorkspaceTransform transform = new WorkspaceTransform(
         Vec3.atLowerCornerOf(payload.operationTranslation()),
         payload.operationRotation(),
         new io.github.fastformer.fastplace.OperationStackRegion(
            payload.operationStackMin(), payload.operationStackMax()
         )
      );
      WORKSPACE.addParts(List.of(new ClientSelectionPart(
         0, ClientSelectionPart.Source.WORLD, selection, blocks, transform, false
      )));
      WORKSPACE.clearHistory();
      refreshSourceMask();
   }

   public static boolean copySelected() {
      Optional<OperationClipboard> copied = OperationClipboard.fromWorkspace(WORKSPACE);
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
      if (value == null || value.parts().size() > ClientOperationWorkspace.MAX_PARTS - WORKSPACE.size()) {
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
      boolean added = WORKSPACE.addParts(parts);
      if (added) {
         refreshSourceMask();
      }
      return added;
   }

   public static boolean markSelectedForDeletion() {
      if (workspaceSubmissionPending || WORKSPACE.selectedIds().isEmpty() || !WORKSPACE.beginEdit()) {
         return false;
      }
      for (ClientSelectionPart part : List.copyOf(WORKSPACE.selectedParts())) {
         if (part.source() == ClientSelectionPart.Source.CLIPBOARD) {
            WORKSPACE.removePartDuringEdit(part.id());
         } else {
            WORKSPACE.updatePart(part.withPendingDelete(true));
         }
      }
      boolean changed = WORKSPACE.finishEdit();
      refreshSourceMask();
      return changed;
   }

   public static boolean removeSelectedParts() {
      if (workspaceSubmissionPending) {
         return false;
      }
      boolean changed = WORKSPACE.removeSelectedParts();
      refreshSourceMask();
      return changed;
   }

   public static boolean handleCreateClick(int mouseButton, BlockPos point) {
      if (workspaceSubmissionPending || point == null || WORKSPACE.size() >= ClientOperationWorkspace.MAX_PARTS) {
         return false;
      }
      if (currentSelectionMode == OperationSelectionMode.CUBOID) {
         if (mouseButton == 0) {
            draftPoints.clear();
            draftPoints.add(point.immutable());
            draftPrismBaseCount = 0;
            return true;
         }
         if (mouseButton == 1 && draftPoints.size() == 1) {
            draftPoints.add(point.immutable());
            return finishDraft(0);
         }
         if (mouseButton == 2) {
            if (draftPoints.isEmpty()) {
               draftPoints.add(point.immutable());
               return true;
            }
            if (draftPoints.size() == 1) {
               draftPoints.add(point.immutable());
               return finishDraft(0);
            }
         }
         return false;
      }
      if (currentSelectionMode == OperationSelectionMode.PRISM) {
         if (mouseButton == 0) {
            if (!draftPoints.isEmpty()) {
               draftPoints.removeLast();
               if (draftPoints.size() < draftPrismBaseCount) {
                  draftPrismBaseCount = 0;
               }
               return true;
            }
            return false;
         }
         if (mouseButton != 1) {
            if (mouseButton == 2) {
               draftPoints.add(point.immutable());
               return draftPrismBaseCount > 0 && draftPoints.size() > draftPrismBaseCount
                  ? finishDraft(draftPrismBaseCount) : true;
            }
            return false;
         }
         if (draftPrismBaseCount == 0
            && draftPoints.size() >= 3
            && point.equals(draftPoints.getFirst())) {
            draftPrismBaseCount = draftPoints.size();
            return true;
         }
         draftPoints.add(point.immutable());
         return draftPrismBaseCount > 0 && draftPoints.size() > draftPrismBaseCount
            ? finishDraft(draftPrismBaseCount)
            : true;
      }
      return false;
   }

   public static boolean activeSelectionTransformed() {
      return active() && WORKSPACE.part(WORKSPACE.activeId()).map(ClientSelectionPart::transformed).orElse(false);
   }

   public static boolean undo() {
      if (workspaceSubmissionPending) {
         return false;
      }
      boolean changed = WORKSPACE.undo();
      refreshSourceMask();
      return changed;
   }

   public static boolean moveSelected(BlockPos offset) {
      if (workspaceSubmissionPending || offset == null || offset.equals(BlockPos.ZERO)
         || WORKSPACE.selectedIds().isEmpty() || !WORKSPACE.beginEdit()) {
         return false;
      }
      Vec3 delta = Vec3.atLowerCornerOf(offset);
      for (ClientSelectionPart part : List.copyOf(WORKSPACE.selectedParts())) {
         WORKSPACE.updatePart(part.withTranslation(part.transform().translation().add(delta)));
      }
      boolean changed = WORKSPACE.finishEdit();
      refreshSourceMask();
      return changed;
   }

   public static boolean canAdjustAabbFace(ClientSelectionPart part) {
      return part != null
         && part.axisAlignedCuboid()
         && part.transform().rotation().equals(Vec3.ZERO)
         && !part.transform().hasEffect()
         && part.matchesInitialBounds()
         && part.source() == ClientSelectionPart.Source.WORLD;
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
      ClientSelectionPart part = WORKSPACE.part(WORKSPACE.activeId()).orElse(null);
      if (!sourceSnapshotMatches(part) || !WORKSPACE.beginEdit()) {
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
         WORKSPACE.cancelEdit();
         return false;
      }
      WorkspaceTransform transform = part.transform().withRepeats(
         part.transform().repeats(), BlockPos.ZERO
      );
      WORKSPACE.updatePart(
         part.withSelection(selection).withBlocks(snapshotForSelection(part, selection)).withTransform(transform)
      );
      boolean changed = WORKSPACE.finishEdit();
      refreshSourceMask();
      return changed;
   }

   public static void updateAabbFaceGesture(
      ClientSelectionPart baseline, int axis, boolean positiveFace, int outwardSteps
   ) {
      if (workspaceSubmissionPending || !canAdjustAabbFace(baseline) || axis < 0 || axis > 2) {
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
         0
      );
      WorkspaceTransform transform = baseline.transform().withRepeats(
         baseline.transform().repeats(), BlockPos.ZERO
      );
      ClientSelectionPart current = WORKSPACE.part(baseline.id()).orElse(baseline);
      WORKSPACE.updatePart(
         baseline.withSelection(selection).withBlocks(snapshotForSelection(current, selection)).withTransform(transform)
      );
      refreshSourceMask();
   }

   public static void updateTransformGesture(
      List<ClientSelectionPart> baseline,
      boolean common,
      AxisGizmo.Operation operation,
      AxisGizmo.Axis axis,
      int direction,
      int totalSteps,
      double rotationRadians
   ) {
      if (workspaceSubmissionPending) {
         return;
      }
      baseline.forEach(WORKSPACE::updatePart);
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
         WORKSPACE.updatePart(part.withTransform(updated));
      }
      refreshSourceMask();
   }

   public static boolean finishTransformGesture() {
      boolean changed = WORKSPACE.finishEdit();
      refreshSourceMask();
      return changed;
   }

   public static boolean submitWorkspace(Minecraft minecraft) {
      if (workspaceSubmissionPending || minecraft == null || minecraft.getConnection() == null || WORKSPACE.isEmpty()) {
         return false;
      }
      try {
         OperationWorkspacePlan plan = new OperationWorkspacePlan(WORKSPACE.parts().stream().map(part ->
            new OperationWorkspacePlan.Part(
               part.id(),
               part.source(),
               part.pendingDelete() && !part.initialBlocks().isEmpty() ? part.initialBlocks() : part.blocks(),
               part.transform(),
               part.pendingDelete()
            )
         ).toList());
         byte[] compressed = OperationWorkspacePlanCodec.encodeCompressed(plan);
         int chunkSize = OperationWorkspaceApplyPayload.MAX_CHUNK_BYTES;
         int chunkCount = (compressed.length + chunkSize - 1) / chunkSize;
         UUID transferId = UUID.randomUUID();
         workspaceSubmissionPending = true;
         for (int index = 0; index < chunkCount; index++) {
            int from = index * chunkSize;
            int to = Math.min(compressed.length, from + chunkSize);
            PacketDistributor.sendToServer(
               new OperationWorkspaceApplyPayload(
                  transferId, index, chunkCount, Arrays.copyOfRange(compressed, from, to)
               ),
               new CustomPacketPayload[0]
            );
         }
         return true;
      } catch (IOException | RuntimeException exception) {
         workspaceSubmissionPending = false;
         return false;
      }
   }

   public static void applyWorkspaceResult(OperationWorkspaceResultPayload payload) {
      if (payload == null || !workspaceSubmissionPending) {
         return;
      }
      workspaceSubmissionPending = false;
      if (payload.accepted()) {
         clearWorkspace();
         serverOperationSeen = false;
         return;
      }
      if (!payload.failedPartIds().isEmpty()) {
         WORKSPACE.selectOnly(payload.failedPartIds().getFirst());
         for (int index = 1; index < payload.failedPartIds().size(); index++) {
            WORKSPACE.toggleSelected(payload.failedPartIds().get(index));
         }
      }
   }

   public static void cancelTransformGesture() {
      WORKSPACE.cancelEdit();
      refreshSourceMask();
   }

   public static void clearWorkspace() {
      WORKSPACE.clear();
      SOURCE_MASK.clear();
      draftPoints.clear();
      draftPrismBaseCount = 0;
      workspaceSubmissionPending = false;
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
         currentSelectionMode,
         List.copyOf(draftPoints),
         prismBaseCount,
         BlockPos.ZERO,
         BlockPos.ZERO,
         0
      );
      if (selection == null) {
         return false;
      }
      boolean added = WORKSPACE.addParts(List.of(new ClientSelectionPart(
         0,
         ClientSelectionPart.Source.WORLD,
         selection,
         capture(selection),
         WorkspaceTransform.IDENTITY,
         false
      )));
      if (added) {
         draftPoints.clear();
         draftPrismBaseCount = 0;
         refreshSourceMask();
      }
      return added;
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
      for (ClientSelectionPart part : WORKSPACE.parts()) {
         if (part.source() != ClientSelectionPart.Source.WORLD) {
            continue;
         }
         masked.addAll(part.initialBlocks().keySet());
      }
      SOURCE_MASK.replace(masked);
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
