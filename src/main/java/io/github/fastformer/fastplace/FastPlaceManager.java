package io.github.fastformer.fastplace;

import io.github.fastformer.fastplace.world.*;

import io.github.fastformer.fastplace.session.*;
import com.mojang.logging.LogUtils;
import io.github.fastformer.fastplace.geometry.GeometryNumbers;
import io.github.fastformer.fastplace.task.PlacementTask;
import io.github.fastformer.fastplace.task.TaskCancellationResult;
import io.github.fastformer.network.FastPlaceNetwork;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import io.github.fastformer.fastplace.geometry.generation.LineTieBias;
import io.github.fastformer.fastplace.geometry.generation.ProgressiveBlockGeneration;
import io.github.fastformer.fastplace.geometry.generation.GenerationFailed;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.slf4j.Logger;

public final class FastPlaceManager {
   private static final Logger LOGGER = LogUtils.getLogger();
   /** Generate ordinary placements on the server thread up to this size. */
   private static final long SYNCHRONOUS_PLACEMENT_LIMIT = 262_144L;
   private static final int BLOCKS_PER_TICK = 4096;
   private static final Map<UUID, FastPlaceSession> SESSIONS = new HashMap<>();
   private static final Map<UUID, PlacementTask> TASKS = new HashMap<>();
   private static final Map<UUID, Boolean> MODIFIER_HELD = new HashMap<>();

   private FastPlaceManager() {
   }

   public static Optional<FastPlaceSession> session(ServerPlayer player) {
      return Optional.ofNullable(SESSIONS.get(player.getUUID()));
   }

   public static boolean active(ServerPlayer player) {
      return SESSIONS.containsKey(player.getUUID());
   }

   public static void setModifierHeld(ServerPlayer player, boolean modifierHeld) {
      MODIFIER_HELD.put(player.getUUID(), modifierHeld);
      FastPlaceSession session = SESSIONS.get(player.getUUID());
      if (session != null) {
         session.setModifierHeld(modifierHeld);
         FastPlaceNetwork.syncPreview(player, session);
      }
      OperationManager.session(player).ifPresent(operation -> FastPlaceNetwork.syncOperation(player, operation));
      GeometryManager.session(player).ifPresent(geometry -> FastPlaceNetwork.syncGeometry(player, geometry));
   }

   public static boolean modifierHeld(ServerPlayer player) {
      return MODIFIER_HELD.getOrDefault(player.getUUID(), false);
   }

   public static InteractionState classify(ServerPlayer player) {
      if (OperationManager.active(player)) {
         return InteractionState.OPERATION;
      } else if (GeometryManager.active(player)) {
         return InteractionState.GEOMETRY;
      } else if (active(player)) {
         return InteractionState.BUILDING;
      } else if (SpecialItemHandlers.isSpecial(player.getMainHandItem())) {
         return InteractionState.SPECIAL_ITEM;
      } else if (!PlaceableItems.isPlaceable(player.getMainHandItem())) {
         return InteractionState.OPERATION;
      } else {
         return InteractionState.BUILDING;
      }
   }

   public static boolean operationActive(ServerPlayer player) {
      return OperationManager.active(player);
   }

   public static FastPlaceGeometry.Modes effectiveModes(FastPlaceSettings settings, FastPlaceSession session) {
      FastPlaceGeometry.Modes modes = settings.modes().withModifierHeld(session != null && session.modifierHeld());
      if (session != null && session.polygonClosed()) {
         return modes.withVolumeMode(VolumeMode.PERPENDICULAR_TO_FACE);
      }
      boolean selectingRaycastPoint = settings.pointMode() == PointMode.RAYCAST
         && (session == null || session.points().isEmpty());
      if (selectingRaycastPoint) {
         return modes.withRaycastPlacement(
            session != null && session.modifierHeld() ? RaycastPlacement.EMBEDDED : modes.raycastPlacement()
         );
      }
      if (session != null && session.stage() == FastPlaceStage.LINE && settings.lineMode() == LineMode.RAYCAST) {
         return modes.withRaycastPlacement(
            session.modifierHeld() ? RaycastPlacement.EMBEDDED : modes.raycastPlacement()
         );
      }
      return modes;
   }

   public static void addPoint(ServerPlayer player, BlockHitResult hit) {
      addPoint(player, hit.getBlockPos(), hit.getBlockPos().relative(hit.getDirection()), hit);
   }

   public static void addPoint(ServerPlayer player, BlockPos hitBlock, BlockPos surfaceBlock) {
      addPoint(player, hitBlock, surfaceBlock, null);
   }

   private static void addPoint(
      ServerPlayer player, BlockPos hitBlock, BlockPos surfaceBlock, BlockHitResult hit
   ) {
      FastPlaceSession session = SESSIONS.computeIfAbsent(player.getUUID(), ignored -> new FastPlaceSession());
      boolean modifierHeld = modifierHeld(player);
      session.setModifierHeld(modifierHeld);
      FastPlaceSettings settings = FastPlaceSettings.load(player);
      int previousPointCount = session.points().size();
      FastPlaceStage previousStage = effectiveStage(session, settings);
      FastPlaceGeometry.Modes modes = effectiveModes(settings, session);
      boolean collectingPolygon = settings.faceMode() == FaceMode.POLYGON && session.points().size() >= 2 && !session.polygonClosed();
      BlockPos point = FastPlaceGeometry.resolveCandidate(
         session.points(),
         session.polygonClosed(),
         hitBlock,
         surfaceBlock,
         session.faceBaseOffset(),
         session.volumeBaseOffset(),
         session.perpendicularAnchor(),
         player.getEyePosition(),
         player.getViewVector(1.0F),
         session.freeScrollOffset(),
         modes
      );
      if (previousPointCount == 0 && hit != null) {
         boolean embedded = settings.pointMode() == PointMode.RAYCAST
            && modes.raycastPlacement() == RaycastPlacement.EMBEDDED;
         session.capturePlacementContext(PlacementContextSnapshot.capture(
            player.level(), player, player.getMainHandItem(), hit, embedded
         ));
      }
      if (session.polygonClosed()) {
         int pointCount = session.points().size();
         session.addPoint(point, player.getEyePosition(), player.getViewVector(1.0F));
         if (session.points().size() > pointCount) {
            session.confirmPolygonHeight();
            fill(player);
         } else {
            FastPlaceNetwork.syncPreview(player, session);
         }
         return;
      }
      boolean closed = session.addOrClose(point, player.getEyePosition(), player.getViewVector(1.0F), collectingPolygon ? 3 : defaultClosingPoints(session));
      if (!collectingPolygon && previousPointCount == 2 && session.points().size() == 3) {
         session.confirmFaceTieBias(modifierHeld ? LineTieBias.OPPOSITE : LineTieBias.DEFAULT);
      }
      FastPlaceStage currentStage = effectiveStage(session, settings);
      if (!collectingPolygon && currentStage != previousStage) {
         session.onStageChanged();
      }
      if (closed) {
         if (collectingPolygon) {
            session.closePolygon();
            session.onStageChanged();
            FastPlaceNetwork.syncPreview(player, session);
         } else {
            FastPlaceMessages.actionBar(player, "fastformer.message.closed_points", session.points().size());
            cancel(player);
         }
      } else if (!collectingPolygon && session.points().size() == 4) {
         fill(player);
      } else {
         FastPlaceNetwork.syncPreview(player, session);
      }
   }

   private static int defaultClosingPoints(FastPlaceSession session) {
      return session.stage() == FastPlaceStage.VOLUME ? 4 : 3;
   }

   public static void undo(ServerPlayer player) {
      FastPlaceSession session = SESSIONS.get(player.getUUID());
      if (session != null) {
         if (!session.undoStep()) {
            cancel(player);
            FastPlaceMessages.actionBar(player, FastPlaceMessages.text("fastformer.message.cancelled"));
         } else {
            FastPlaceNetwork.syncPreview(player, session);
         }
      }
   }

   public static void sync(ServerPlayer player) {
      FastPlaceSession session = SESSIONS.get(player.getUUID());
      if (session != null) {
         FastPlaceNetwork.syncPreview(player, session);
      } else {
         FastPlaceNetwork.syncSettings(player);
      }
   }

   public static void cancel(ServerPlayer player) {
      FastPlaceSession session = SESSIONS.remove(player.getUUID());
      if (session != null) {
         session.onDestroyed();
         FastPlaceNetwork.clearPreview(player);
      }
      OperationManager.cancel(player);
      GeometryManager.cancel(player);
   }

   public static void quit(ServerPlayer player) {
      boolean restoringPlacement = restoreActive(player);
      boolean restoringOperation = OperationManager.restoreActive(player);
      cancel(player);
      if (restoringPlacement || restoringOperation) {
         cancelRestore(player);
         OperationManager.cancelRestore(player);
      } else {
         cancelTask(player);
         OperationManager.cancelTask(player);
      }
      FastPlaceMessages.actionBar(player, "fastformer.message.quit");
   }

   public static boolean cancelRestore(ServerPlayer player) {
      return WorldHistoryManager.cancel(player);
   }

   public static TaskCancellationResult cancelTask(ServerPlayer player) {
      PlacementTask task = TASKS.remove(player.getUUID());
      if (task == null) {
         return TaskCancellationResult.NOT_ACTIVE;
      }
      // Detach and cancel first. The server tick can no longer write another
      // block from this task while its completed write journal is recovered.
      task.cancel();
      ArrayDeque<ReversibleBlockSnapshot> undoChanges = task.undoChanges();
      ServerLevel taskLevel = player.getServer().getLevel(task.dimension());
      if (undoChanges.isEmpty()) {
         task.releaseAfterCancelledJournal(new WorldTaskContext(player.getServer(), player.getUUID()));
         return TaskCancellationResult.CANCELLED_BEFORE_WRITE;
      }
      boolean rollbackStarted;
      if (taskLevel != null) {
         rollbackStarted = WorldHistoryManager.startRollback(
            player, taskLevel, undoChanges, task.afterChanges(), task.journal()
         );
      } else {
         rollbackStarted = WorldHistoryManager.startRollback(
            player, task.dimension(), undoChanges, task.afterChanges(), task.journal()
         );
      }
      return rollbackStarted
         ? TaskCancellationResult.ROLLBACK_STARTED
         : TaskCancellationResult.RECOVERY_BLOCKED;
   }

   public static void cycleStageMode(ServerPlayer player) {
      cycleStageMode(player, null);
   }

   public static void setStageMode(ServerPlayer player, FastPlaceMode mode) {
      FastPlaceSettings settings = FastPlaceSettings.load(player);
      FastPlaceSession session = SESSIONS.get(player.getUUID());
      if (session != null && session.polygonClosed() && mode.stage() == FastPlaceStage.VOLUME) {
         return;
      }
      if (!FastPlaceStateMachine.allowedModes(mode.stage(), settings.storedLineMode(), settings.storedFaceMode()).contains(mode)) {
         FastPlaceMessages.chat(player, FastPlaceMessages.text("fastformer.message.mode_rejected", FastPlaceMessages.text(mode)));
         return;
      }
      settings.setMode(player, mode);
      if (session != null) {
         session.onModeChanged();
         FastPlaceNetwork.syncPreview(player, session);
      } else {
         FastPlaceNetwork.syncSettings(player);
      }
      FastPlaceMessages.chat(player, FastPlaceMessages.text("fastformer.message.mode_changed", FastPlaceMessages.text(mode.stage()), FastPlaceMessages.text(mode)));
   }

   public static void cycleStageMode(ServerPlayer player, BlockPos lineCandidate) {
      FastPlaceSession session = SESSIONS.get(player.getUUID());
      FastPlaceSettings settings = FastPlaceSettings.load(player);
      if (session != null && session.polygonClosed() && !session.polygonHeightConfirmed()) {
         session.cyclePolygonVolumeShape();
         FastPlaceNetwork.syncPreview(player, session);
         return;
      }
      FastPlaceStage stage = session == null ? FastPlaceStage.POINT : effectiveStage(session, settings);
      if (session != null && stage == FastPlaceStage.LINE && lineCandidate != null) {
         BlockPos offset = lineCandidate.subtract(session.points().getFirst());
         session.setFreeScrollOffset(offset);
      }
      settings.cycleMode(player, stage);
      if (session != null) {
         session.onModeChanged();
         FastPlaceNetwork.syncPreview(player, session);
      } else {
         FastPlaceNetwork.syncSettings(player);
      }
   }

   public static void cycleFillMode(ServerPlayer player) {
      FastPlaceSettings settings = FastPlaceSettings.load(player);
      settings.cycleFillMode(player);
      syncCurrentPreview(player);
   }

   public static void cycleRaycastPlacement(ServerPlayer player) {
      FastPlaceSettings settings = FastPlaceSettings.load(player);
      settings.cycleRaycastPlacement(player);
      session(player).ifPresentOrElse(session -> FastPlaceNetwork.syncPreview(player, session), () -> FastPlaceNetwork.syncSettings(player));
   }

   public static void adjustFreeScrollOffset(ServerPlayer player, int steps) {
      FastPlaceSession session = SESSIONS.get(player.getUUID());
      if (session != null && steps != 0) {
         session.adjustFreeScrollOffset(player.getViewVector(1.0F), steps);
         FastPlaceNetwork.syncPreview(player, session);
      }
   }

   public static boolean closePolygon(ServerPlayer player) {
      FastPlaceSession session = SESSIONS.get(player.getUUID());
      FastPlaceSettings settings = FastPlaceSettings.load(player);
      if (session == null
         || settings.faceMode() != FaceMode.POLYGON
         || session.polygonClosed()
         || session.points().size() < 3) {
         return false;
      }
      session.closePolygon();
      session.onStageChanged();
      FastPlaceNetwork.syncPreview(player, session);
      return true;
   }

   public static void scrollContext(ServerPlayer player, int steps) {
      FastPlaceSession session = SESSIONS.get(player.getUUID());
      if (session != null && steps != 0) {
         if (session.polygonClosed()) {
            return;
         }
         FastPlaceSettings settings = FastPlaceSettings.load(player);
         switch (effectiveStage(session, settings)) {
            case LINE -> {
               if (settings.lineMode() != LineMode.FREE_SCROLL) {
                  return;
               }
               adjustFreeScrollOffset(player, steps);
            }
            case FACE -> {
               if (settings.faceMode() != FaceMode.PARALLELOGRAM_BASE_PLANE) {
                  return;
               }
               session.adjustFaceBaseOffset(FastPlaceGeometry.faceBaseAxis(session.points(), player.getViewVector(1.0F)), steps);
               FastPlaceNetwork.syncPreview(player, session);
            }
            case VOLUME -> {
               if (!FastPlaceGeometry.usesVolumeOffset(settings.modes())) {
                  return;
               }
               session.adjustVolumeBaseOffset(FastPlaceGeometry.volumeBaseAxis(session.points(), settings.modes(), player.getViewVector(1.0F)), steps);
               FastPlaceNetwork.syncPreview(player, session);
            }
            default -> {
            }
         }
      }
   }
   public static boolean setContextValue(ServerPlayer player, double value) {
      if (!GeometryNumbers.finite(value)) {
         return false;
      }
      FastPlaceSession session = SESSIONS.get(player.getUUID());
      if (session == null) {
         return false;
      }
      FastPlaceSettings settings = FastPlaceSettings.load(player);
      if (session.polygonClosed()) {
         return false;
      }
      return switch (effectiveStage(session, settings)) {
         case LINE -> {
            if (settings.lineMode() != LineMode.FREE_SCROLL) {
               yield false;
            }
            session.setFreeScrollOffset(new BlockPos((int)Math.round(value), 0, 0));
            FastPlaceNetwork.syncPreview(player, session);
            yield true;
         }
         case FACE -> {
            if (settings.faceMode() != FaceMode.PARALLELOGRAM_BASE_PLANE) {
               yield false;
            }
            session.setFaceBaseOffset(FastPlaceGeometry.faceBaseAxis(session.points(), player.getViewVector(1.0F)), value);
            FastPlaceNetwork.syncPreview(player, session);
            yield true;
         }
         case VOLUME -> {
            if (!FastPlaceGeometry.usesVolumeOffset(settings.modes())) {
               yield false;
            }
            session.setVolumeBaseOffset(FastPlaceGeometry.volumeBaseAxis(session.points(), settings.modes(), player.getViewVector(1.0F)).scale(value));
            FastPlaceNetwork.syncPreview(player, session);
            yield true;
         }
         default -> false;
      };
   }
   public static boolean setFaceAngle(ServerPlayer player, double value) {
      if (!GeometryNumbers.finite(value)) {
         return false;
      }
      FastPlaceSession session = SESSIONS.get(player.getUUID());
      if (session != null && effectiveStage(session, FastPlaceSettings.load(player)) == FastPlaceStage.FACE) {
         FastPlaceSettings settings = FastPlaceSettings.load(player);
         if (settings.faceMode() != FaceMode.POLYGON) {
            return false;
         } else {
            settings.setAngle(player, value);
            FastPlaceNetwork.syncPreview(player, session);
            return true;
         }
      } else {
         return false;
      }
   }

   public static boolean setLineAngleDistance(ServerPlayer player, int value) {
      FastPlaceSession session = SESSIONS.get(player.getUUID());
      if (session != null && session.stage() == FastPlaceStage.LINE) {
         FastPlaceSettings settings = FastPlaceSettings.load(player);
         if (settings.lineMode() != LineMode.FREE_SCROLL) {
            return false;
         } else {
            session.setFreeScrollOffset(new BlockPos(value, 0, 0));
            FastPlaceNetwork.syncPreview(player, session);
            return true;
         }
      } else {
         return false;
      }
   }

   public static boolean setVolumeDistance(ServerPlayer player, int value) {
      FastPlaceSession session = SESSIONS.get(player.getUUID());
      if (session != null
         && !session.polygonClosed()
         && effectiveStage(session, FastPlaceSettings.load(player)) == FastPlaceStage.VOLUME
         && FastPlaceGeometry.usesVolumeOffset(FastPlaceSettings.load(player).modes())) {
         session.setVolumeBaseOffset(
            FastPlaceGeometry.volumeBaseAxis(session.points(), FastPlaceSettings.load(player).modes(), player.getViewVector(1.0F)).scale((double)value)
         );
         FastPlaceNetwork.syncPreview(player, session);
         return true;
      } else {
         return false;
      }
   }

   public static void fill(ServerPlayer player) {
      FastPlaceSession session = SESSIONS.get(player.getUUID());
      if (session != null) {
         if (placementBusy(player)) {
            FastPlaceMessages.actionBar(player, FastPlaceMessages.text("fastformer.message.placement_task_running"));
            return;
         }
         Optional<BlockState> placeState = PlaceableItems.placementState(
            player.getMainHandItem(), player, session.placementContext()
         );
         if (placeState.isPresent()) {
            FastPlaceSettings settings = FastPlaceSettings.load(player);
            int maxPlacement = settings.maxPlacement();
            List<BlockPos> points = List.copyOf(session.points());
            boolean polygonHeightConfirmed = session.polygonHeightConfirmed();
            PolygonVolumeShape polygonVolumeShape = session.polygonVolumeShape();
            FastPlaceGeometry.Modes modes = settings.modes().withFaceTieBias(effectiveFaceTieBias(session, settings));
            BlockState state = placeState.get();
            SmartWoodFrame.Config smartFrame = settings.smartWoodFrame()
               ? new SmartWoodFrame.Config(
                  session.placementContext() == null
                     ? Direction.Axis.Y
                     : session.placementContext().clickedFace().getAxis(),
                  points,
                  SmartWoodFrame.edgeGuides(
                     points,
                     modes.faceMode(),
                     polygonHeightConfirmed,
                     polygonVolumeShape
                  )
            )
               : null;
            FastPlaceGeometry.Modes outlineModes = modes.withFillMode(FillMode.OUTLINE);
            long estimatedPlacement = estimatedBlocks(points);
            if (estimatedPlacement > SYNCHRONOUS_PLACEMENT_LIMIT) {
               ProgressiveBlockGeneration progress = new ProgressiveBlockGeneration(estimatedPlacement);
               CompletableFuture<Set<BlockPos>> future = CompletableFuture.supplyAsync(() -> {
                  Set<BlockPos> generated = FastPlaceGeometry.blocks(
                     points, modes, polygonHeightConfirmed, polygonVolumeShape, maxPlacement + 1, progress
                  );
                  progress.complete();
                  return generated;
               });
               CompletableFuture<Set<BlockPos>> outlineFuture = smartFrame == null
                  ? null
                  : modes.fillMode() == FillMode.OUTLINE
                     ? future
                     : CompletableFuture.supplyAsync(() -> FastPlaceGeometry.blocks(
                        points, outlineModes, polygonHeightConfirmed, polygonVolumeShape, maxPlacement + 1
                     ));
               TASKS.put(player.getUUID(), PlacementTask.generating(
                  future,
                  outlineFuture,
                  progress,
                  state,
                  smartFrame == null ? null : positions -> SmartWoodFrame.resolve(positions, state, smartFrame),
                  settings.placementConflictMode(),
                  settings.placementUpdateMode(),
                  maxPlacement,
                  player.serverLevel().dimension()
               ));
               cancel(player);
               FastPlaceMessages.actionBar(player, "fastformer.message.placement_generating");
            } else {
               Set<BlockPos> blocks = FastPlaceGeometry.blocks(points, modes, polygonHeightConfirmed, polygonVolumeShape, maxPlacement + 1);
               Set<BlockPos> outline = smartFrame == null
                  ? Set.of()
                  : modes.fillMode() == FillMode.OUTLINE
                     ? blocks
                     : FastPlaceGeometry.blocks(points, outlineModes, polygonHeightConfirmed, polygonVolumeShape, maxPlacement + 1);
               if (GenerationFailed.is(outline)) {
                  outline = Set.of();
               }
               if (GenerationFailed.is(blocks)) {
                  cancel(player);
                  FastPlaceMessages.chat(player, "fastformer.message.face_generation_constraints_failed");
               } else if (blocks.size() > maxPlacement) {
                  cancel(player);
                  FastPlaceMessages.actionBar(player, "fastformer.message.placement_too_large", maxPlacement);
                } else {
                   TASKS.put(player.getUUID(), PlacementTask.ready(
                      blocks,
                      outline,
                      state,
                      smartFrame == null ? null : positions -> SmartWoodFrame.resolve(positions, state, smartFrame),
                      settings.placementConflictMode(),
                      settings.placementUpdateMode(),
                      maxPlacement,
                      player.serverLevel().dimension()
                   ));
                   cancel(player);
                   FastPlaceMessages.actionBar(player, "fastformer.message.placement_queued", blocks.size());
                }
            }
         } else {
            FastPlaceMessages.actionBar(player, "fastformer.message.placement_hold_block");
         }
      }
   }

   public static boolean fillBoundedPlane(ServerPlayer player, BlockPos start) {
      return fillBoundedPlane(player, start, null);
   }

   public static boolean fillBoundedPlane(ServerPlayer player, BlockPos start, Direction.Axis preferredAxis) {
      Optional<BlockState> placeState = PlaceableItems.defaultBlockState(player.getMainHandItem());
      if (placeState.isEmpty() || !player.level().getBlockState(start).canBeReplaced()) {
         return false;
      }
      if (placementBusy(player)) {
         FastPlaceMessages.actionBar(player, FastPlaceMessages.text("fastformer.message.placement_task_running"));
         return true;
      }

      Set<BlockPos> best = null;
      Direction.Axis bestAxis = null;
      for (Direction.Axis normalAxis : Direction.Axis.values()) {
         Set<BlockPos> candidate = boundedPlane(player, start, normalAxis, 256);
         if (candidate != null && betterBoundedPlane(candidate, normalAxis, best, bestAxis, preferredAxis)) {
            best = candidate;
            bestAxis = normalAxis;
         }
      }
      if (best == null || best.isEmpty()) {
         FastPlaceMessages.actionBar(player, FastPlaceMessages.text("fastformer.message.point_plane_not_found"));
         return false;
      }

      int maxPlacement = FastPlaceSettings.load(player).maxPlacement();
      if (best.size() > maxPlacement) {
         FastPlaceMessages.actionBar(player, FastPlaceMessages.text("fastformer.message.point_plane_too_large", maxPlacement));
         return false;
      }
      FastPlaceSettings settings = FastPlaceSettings.load(player);
      TASKS.put(
         player.getUUID(),
         PlacementTask.ready(best, placeState.get(), settings.placementConflictMode(), settings.placementUpdateMode(), maxPlacement, player.serverLevel().dimension())
      );
      FastPlaceMessages.actionBar(player, FastPlaceMessages.text("fastformer.message.point_plane_queued", best.size()));
      return true;
   }

   private static boolean betterBoundedPlane(
      Set<BlockPos> candidate, Direction.Axis candidateAxis, Set<BlockPos> best, Direction.Axis bestAxis, Direction.Axis preferredAxis
   ) {
      if (best == null || candidate.size() < best.size()) {
         return true;
      }
      if (candidate.size() > best.size()) {
         return false;
      }
      if (preferredAxis != null && candidateAxis == preferredAxis && bestAxis != preferredAxis) {
         return true;
      }
      return bestAxis == null || candidateAxis.ordinal() < bestAxis.ordinal();
   }

   public static boolean queuePlacement(ServerPlayer player, Set<BlockPos> blocks, BlockState state) {
      FastPlaceSettings settings = FastPlaceSettings.load(player);
      int maxPlacement = settings.maxPlacement();
      if (GenerationFailed.is(blocks)) {
         FastPlaceMessages.chat(player, "fastformer.message.face_generation_constraints_failed");
         return false;
      }
      if (blocks.size() > maxPlacement) {
         FastPlaceMessages.actionBar(player, FastPlaceMessages.text("fastformer.message.placement_too_large", maxPlacement));
         return false;
      }
      if (placementBusy(player)) {
         FastPlaceMessages.actionBar(player, FastPlaceMessages.text("fastformer.message.placement_task_running"));
         return false;
      }
      TASKS.put(player.getUUID(), PlacementTask.ready(blocks, state, settings.placementConflictMode(), settings.placementUpdateMode(), maxPlacement, player.serverLevel().dimension()));
      FastPlaceMessages.actionBar(player, FastPlaceMessages.text("fastformer.message.placement_queued", blocks.size()));
      return true;
   }

   public static boolean queueGeneratedPlacement(
      ServerPlayer player,
      Supplier<Set<BlockPos>> generator,
      BlockState state,
      long estimatedWork
   ) {
      FastPlaceSettings settings = FastPlaceSettings.load(player);
      int maxPlacement = settings.maxPlacement();
      if (placementBusy(player)) {
         FastPlaceMessages.actionBar(player, FastPlaceMessages.text("fastformer.message.placement_task_running"));
         return false;
      }
      if (estimatedWork <= SYNCHRONOUS_PLACEMENT_LIMIT) {
         Set<BlockPos> blocks;
         try {
            blocks = generator.get();
         } catch (RuntimeException | OutOfMemoryError exception) {
            FastPlaceMessages.actionBar(player, FastPlaceMessages.text("fastformer.message.placement_generation_failed"));
            LOGGER.error("FastFormer synchronous placement generation failed for {}", player.getUUID(), exception);
            return false;
         }
         return queuePlacement(player, blocks, state);
      }
      CompletableFuture<Set<BlockPos>> future = CompletableFuture.supplyAsync(generator);
      TASKS.put(player.getUUID(), PlacementTask.generating(
         future,
         null,
         state,
         settings.placementConflictMode(),
         settings.placementUpdateMode(),
         maxPlacement,
         player.serverLevel().dimension()
      ));
      FastPlaceMessages.actionBar(player, FastPlaceMessages.text("fastformer.message.placement_generating"));
      return true;
   }

   private static Set<BlockPos> boundedPlane(ServerPlayer player, BlockPos start, Direction.Axis normalAxis, int limit) {
      java.util.LinkedHashSet<BlockPos> visited = new java.util.LinkedHashSet<>();
      ArrayDeque<BlockPos> open = new ArrayDeque<>();
      open.add(start);
      while (!open.isEmpty()) {
         BlockPos pos = open.removeFirst();
         if (visited.contains(pos) || !player.level().getBlockState(pos).canBeReplaced()) {
            continue;
         }
         visited.add(pos.immutable());
         if (visited.size() > limit) {
            return null;
         }
         for (Direction direction : Direction.values()) {
            if (direction.getAxis() != normalAxis) {
               open.addLast(pos.relative(direction));
            }
         }
      }
      return Set.copyOf(visited);
   }

   public static void setMaxPlacement(ServerPlayer player, int value) {
      FastPlaceSettings settings = FastPlaceSettings.load(player);
      settings.setMaxPlacement(player, value);
      FastPlaceMessages.chat(player, "fastformer.message.max_placement", settings.maxPlacement());
   }

   public static boolean remove(ServerPlayer player) {
      SESSIONS.remove(player.getUUID());
      MODIFIER_HELD.remove(player.getUUID());
      OperationManager.remove(player);
      GeometryManager.remove(player);
      WorldHistoryManager.remove(player);
      FastPlaceNetwork.forgetActivity(player);
      return true;
   }

   /**
    * Detaches a player instance while retaining UUID-owned sessions and tasks.
    * Reconnect and dimension transitions create a new player instance; their
    * state is reconciled when the replacement logs in.
    */
   public static void detachPlayer(ServerPlayer player) {
      if (player == null) {
         return;
      }
      UUID owner = player.getUUID();
      MODIFIER_HELD.remove(owner);
      // Modifier state belongs to the old connection, unlike the workflow
      // points and shape data that remain owned by the player UUID.
      FastPlaceSession session = SESSIONS.get(owner);
      if (session != null) {
         session.setModifierHeld(false);
      }
      FastPlaceNetwork.forgetActivity(player);
   }

   /** Drops server-bound sessions/tasks before a world instance is replaced. */
   public static void clearServer() {
      for (PlacementTask task : TASKS.values()) {
         task.cancel();
      }
      TASKS.clear();
      SESSIONS.clear();
      MODIFIER_HELD.clear();
      OperationManager.clearServer();
      GeometryManager.clearServer();
   }

   public static void tickWorld(net.minecraft.server.MinecraftServer server) {
      for (UUID owner : List.copyOf(TASKS.keySet())) {
         if (!PersistentRecoveryJournal.writesAllowed()) {
            break;
         }
         if (WorldHistoryManager.busy(owner)) {
            continue;
         }
         tickTask(new WorldTaskContext(server, owner));
      }
   }

   private static void tickTask(WorldTaskContext context) {
      UUID owner = context.owner();
      PlacementTask task = TASKS.get(owner);
      if (task == null) {
         return;
      }
      try {
         if (task.prepare()) {
            if (task.generationConstraintsFailed()) {
               TASKS.remove(owner);
               task.releaseLease(context);
               context.chat(FastPlaceMessages.text("fastformer.message.face_generation_constraints_failed"));
            } else if (task.failed()) {
               TASKS.remove(owner);
               task.releaseLease(context);
               context.actionBar(FastPlaceMessages.text("fastformer.message.placement_generation_failed"));
            } else if (task.exceededLimit()) {
               TASKS.remove(owner);
               task.releaseLease(context);
               context.actionBar(FastPlaceMessages.text("fastformer.message.placement_exceeds_max", task.maxPlacement()));
            } else if (task.memoryUnsafe()) {
               TASKS.remove(owner);
               task.releaseLease(context);
               context.actionBar(FastPlaceMessages.text("fastformer.message.operation_memory_unsafe"));
            } else if (context.level(task.dimension()) == null) {
               context.actionBar(FastPlaceMessages.text("fastformer.message.history_dimension_failed"));
            } else if (!task.acquireLease(context)) {
               context.actionBar(FastPlaceMessages.text("fastformer.message.world_write_waiting"));
            } else {
               WorldTaskBudget budget = WorldTaskBudget.forServerTick();
               if (!task.validateSnapshots(context.level(task.dimension()), budget)) {
                context.actionBar(FastPlaceMessages.text("fastformer.message.placement_validating", task.validationRemaining()));
               } else if (task.memoryUnsafe()) {
                TASKS.remove(owner);
                task.releaseLease(context);
                context.actionBar(FastPlaceMessages.text("fastformer.message.operation_memory_unsafe"));
               } else if (task.failed()) {
                TASKS.remove(owner);
                task.releaseLease(context);
                context.actionBar(FastPlaceMessages.text("fastformer.message.placement_snapshot_validation_failed"));
               } else {
                ServerLevel level = context.level(task.dimension());
               JournalPreparation journalPreparation = task.prepareJournal(context);
               if (journalPreparation == JournalPreparation.PENDING) {
                  context.actionBar(FastPlaceMessages.text("fastformer.message.recovery_journal_preparing"));
                  return;
               }
               if (journalPreparation == JournalPreparation.FAILED) {
                  TASKS.remove(owner);
                  settleFailedTask(context, task);
                  context.actionBar(FastPlaceMessages.text("fastformer.message.recovery_journal_failed"));
                  return;
               }
                while (PersistentRecoveryJournal.writesAllowed()
                   && budget.tryConsume()
                   && task.blocks().hasNext()) {
                   BlockPos pos = task.blocks().next();
                   task.consumed();
                   task.place(context, level, pos);
                  if (task.failed()) {
                     break;
                  }
               }

               if (!PersistentRecoveryJournal.writesAllowed()) {
                  TASKS.remove(owner, task);
                  settleFailedTask(context, task);
                  context.actionBar(FastPlaceMessages.text("fastformer.message.recovery_journal_blocked"));
                  return;
               }

               if (task.failed()) {
                  TASKS.remove(owner);
                  settleFailedTask(context, task);
                  context.actionBar(FastPlaceMessages.text("fastformer.message.placement_failed_rollback"));
                } else if (!task.blocks().hasNext()) {
                   if (!task.finalizeSnapshots(level, budget)) {
                      context.actionBar(FastPlaceMessages.text("fastformer.message.placement_finalizing"));
                      return;
                   }
                   if (task.failed()) {
                      TASKS.remove(owner);
                      settleFailedTask(context, task);
                      context.actionBar(FastPlaceMessages.text("fastformer.message.placement_failed_rollback"));
                      return;
                   }
                   JournalPreparation finalCommit = task.prepareCommit();
                   if (finalCommit == JournalPreparation.PENDING) {
                      context.actionBar(FastPlaceMessages.text("fastformer.message.recovery_journal_finalizing"));
                      return;
                   }
                   if (finalCommit == JournalPreparation.FAILED) {
                      TASKS.remove(owner, task);
                      settleFailedTask(context, task);
                      context.actionBar(FastPlaceMessages.text("fastformer.message.placement_failed_rollback"));
                   } else if (!WorldHistoryManager.commitPreparedOperation(context, task.preparedBatch(), task.journal())) {
                      TASKS.remove(owner, task);
                      settleFailedTask(context, task);
                      context.actionBar(FastPlaceMessages.text("fastformer.message.placement_failed_rollback"));
                   } else {
                      TASKS.remove(owner, task);
                      task.releaseLease(context);
                      context.chat(FastPlaceMessages.text("fastformer.message.placement_placed", task.placed()));
                  }
               } else {
                  context.actionBar(FastPlaceMessages.text(
                     "fastformer.message.placement_progress", task.processed(), task.total(), task.placed()
                  ));
                }
               }
            }
         } else {
            ProgressiveBlockGeneration.Snapshot progress = task.generationProgress();
            if (progress == null) {
               context.actionBar(FastPlaceMessages.text("fastformer.message.placement_generating"));
            } else {
               context.actionBar(FastPlaceMessages.text(
                  "fastformer.message.placement_generation_progress",
                  progress.scanned(),
                  progress.estimatedScan(),
                  progress.generated()
               ));
            }
         }
      } catch (RuntimeException | OutOfMemoryError exception) {
         if (TASKS.remove(owner, task)) {
            settleFailedTask(context, task);
         }
         LOGGER.error("FastFormer placement task failed for {} and was transferred to recovery", owner, exception);
         context.actionBar(FastPlaceMessages.text("fastformer.message.placement_failed_rollback"));
      }
   }

   public static boolean undoLast(ServerPlayer player) {
      return WorldHistoryManager.requestUndo(player, 1);
   }

   private static boolean placementBusy(ServerPlayer player) {
      return localTaskBusy(player)
         || OperationManager.taskBusy(player)
         || WorldHistoryManager.busy(player)
         || WorldWriteCoordinator.busy(player.getServer(), player.serverLevel().dimension());
   }

   private static FastPlaceStage effectiveStage(FastPlaceSession session, FastPlaceSettings settings) {
      return FastPlaceGeometry.effectiveStage(session.points(), settings.faceMode(), session.polygonClosed());
   }

   private static LineTieBias effectiveFaceTieBias(FastPlaceSession session, FastPlaceSettings settings) {
      if (settings.faceMode() == FaceMode.POLYGON || session.points().size() < 3) {
         return LineTieBias.DEFAULT;
      }
      return session.effectiveFaceTieBias(session.modifierHeld());
   }

   public static boolean taskBusy(ServerPlayer player) {
      return localTaskBusy(player);
   }

   public static boolean taskActive(ServerPlayer player) {
      return taskActive(player.getUUID());
   }

   static boolean taskActive(UUID owner) {
      return TASKS.containsKey(owner);
   }

   public static boolean restoreActive(ServerPlayer player) {
      return WorldHistoryManager.restoreActive(player);
   }

   private static boolean localTaskBusy(ServerPlayer player) {
      UUID id = player.getUUID();
      return TASKS.containsKey(id);
   }

   public static void syncCurrentPreview(ServerPlayer player) {
      if (OperationManager.active(player)) {
         OperationManager.session(player).ifPresent(session -> FastPlaceNetwork.syncOperation(player, session));
      } else if (GeometryManager.active(player)) {
         GeometryManager.session(player).ifPresent(session -> FastPlaceNetwork.syncGeometry(player, session));
      } else {
         session(player).ifPresentOrElse(session -> FastPlaceNetwork.syncPreview(player, session), () -> FastPlaceNetwork.syncSettings(player));
      }
   }

   private static void startRestore(
      ServerPlayer player,
      ArrayDeque<ReversibleBlockSnapshot> changes,
      Map<BlockPos, ReversibleBlockSnapshot> after,
      PersistentRecoveryJournal journal
   ) {
      WorldHistoryManager.startRollback(player, player.serverLevel(), changes, after, journal);
   }

   private static void startRestore(
      WorldTaskContext context,
      ResourceKey<Level> dimension,
      ArrayDeque<ReversibleBlockSnapshot> changes,
      Map<BlockPos, ReversibleBlockSnapshot> after,
      PersistentRecoveryJournal journal
   ) {
      WorldHistoryManager.startRollback(context, dimension, changes, after, journal);
   }

   private static void settleFailedTask(WorldTaskContext context, PlacementTask task) {
      task.cancel();
      switch (WorldTaskFeature.failureDisposition(task.hasWrites())) {
         case RECOVER_WRITES -> startRestore(
            context, task.dimension(), task.undoChanges(), task.afterChanges(), task.journal()
         );
         case DISCARD_UNUSED_JOURNAL -> task.releaseAfterCancelledJournal(context);
      }
   }

   public static void restoreTossedItem(ServerPlayer player, ItemStack tossed) {
      if (!tossed.isEmpty()) {
         Inventory inventory = player.getInventory();
         ItemStack selected = inventory.getSelected();
         ItemStack returning = tossed.copy();
         if (selected.isEmpty()) {
            inventory.setItem(inventory.selected, returning);
         } else if (ItemStack.isSameItemSameComponents(selected, returning) && selected.getCount() + returning.getCount() <= selected.getMaxStackSize()) {
            selected.grow(returning.getCount());
            inventory.setItem(inventory.selected, selected);
         } else if (!inventory.add(returning)) {
            player.drop(returning, false);
         }

         inventory.setChanged();
         player.containerMenu.broadcastChanges();
      }
   }

   private static long estimatedBlocks(List<BlockPos> points) {
      if (points.isEmpty()) {
         return 0L;
      } else {
         int minX = points.stream().mapToInt(Vec3i::getX).min().orElse(0);
         int minY = points.stream().mapToInt(Vec3i::getY).min().orElse(0);
         int minZ = points.stream().mapToInt(Vec3i::getZ).min().orElse(0);
         int maxX = points.stream().mapToInt(Vec3i::getX).max().orElse(0);
         int maxY = points.stream().mapToInt(Vec3i::getY).max().orElse(0);
         int maxZ = points.stream().mapToInt(Vec3i::getZ).max().orElse(0);
         return (long)(maxX - minX + 1) * (long)(maxY - minY + 1) * (long)(maxZ - minZ + 1);
      }
   }

}
