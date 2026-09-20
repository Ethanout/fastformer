package io.github.fastformer.fastplace;

import io.github.fastformer.fastplace.quickshape.FastPlaceStage;
import io.github.fastformer.fastplace.quickshape.FastPlaceMode;
import io.github.fastformer.fastplace.quickshape.PointMode;
import io.github.fastformer.fastplace.quickshape.LineMode;
import io.github.fastformer.fastplace.quickshape.FaceMode;
import io.github.fastformer.fastplace.quickshape.VolumeMode;
import io.github.fastformer.fastplace.quickshape.RaycastPlacement;
import io.github.fastformer.fastplace.quickshape.PolygonVolumeShape;

import io.github.fastformer.fastplace.world.*;

import io.github.fastformer.fastplace.session.*;
import com.mojang.logging.LogUtils;
import io.github.fastformer.fastplace.geometry.GeometryNumbers;
import io.github.fastformer.fastplace.task.PlacementTask;
import io.github.fastformer.fastplace.task.PlacementTaskPlan;
import io.github.fastformer.fastplace.task.TaskCancellationResult;
import io.github.fastformer.fastplace.placement.effect.PlacementEffectResolver;
import io.github.fastformer.fastplace.placement.effect.ResolvedPlacementEffect;
import io.github.fastformer.fastplace.placement.plan.PlacementGenerationPlan;
import io.github.fastformer.fastplace.placement.plan.PlacementBlockEstimate;
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
import io.github.fastformer.fastplace.geometry.generation.BlockGenerationResult;
import java.util.function.Supplier;
import java.util.function.Function;
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
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

public final class FastPlaceManager {
   private static final Logger LOGGER = LogUtils.getLogger();
   /** Generate ordinary placements on the server thread up to this size. */
   private static final long SYNCHRONOUS_PLACEMENT_LIMIT = 262_144L;
   private static final Map<UUID, FastPlaceSession> SESSIONS = new HashMap<>();
   private static final Map<UUID, PlacementTask> TASKS = new HashMap<>();
   /**
    * Recovery snapshots already extracted from a task and not yet accepted.
    *
    * <p>The extraction moves the before/after record out of the task. The record therefore
    * lives here until the recovery path accepts it, so a failed acceptance can retry
    * without extracting a second, empty snapshot.
    */
   private static final Map<UUID, WorldRecoverySnapshot> HANDOVERS = new HashMap<>();
   private static final Map<UUID, Boolean> MODIFIER_HELD = new HashMap<>();
   /**
    * Sessions of dimensions that the player left. A dimension change keeps this
    * selection: only the binding to the old environment ends, so the points of
    * one dimension are never reused with the coordinates of another.
    */
   private static final DimensionSessionStore<FastPlaceSession> PARKED_SESSIONS = new DimensionSessionStore<>();

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
            session != null && session.modifierHeld() ? RaycastPlacement.EMBEDDED : RaycastPlacement.SURFACE
         );
      }
      if (session != null && session.stage() == FastPlaceStage.LINE && settings.lineMode() == LineMode.RAYCAST) {
         return modes.withRaycastPlacement(
            session.modifierHeld() ? RaycastPlacement.EMBEDDED : RaycastPlacement.SURFACE
         );
      }
      return modes;
   }

   public static void addPoint(ServerPlayer player, BlockHitResult hit) {
      addPoint(player, hit.getBlockPos(), hit.getBlockPos().relative(hit.getDirection()), hit, modifierHeld(player));
   }

   public static void addPoint(ServerPlayer player, BlockPos hitBlock, BlockPos surfaceBlock) {
      addPoint(player, hitBlock, surfaceBlock, null, modifierHeld(player));
   }

   public static void addInitialPoint(ServerPlayer player, BlockHitResult hit, boolean modifierHeld) {
      MODIFIER_HELD.put(player.getUUID(), modifierHeld);
      addPoint(player, hit.getBlockPos(), hit.getBlockPos().relative(hit.getDirection()), hit, modifierHeld);
   }

   private static void addPoint(
      ServerPlayer player, BlockPos hitBlock, BlockPos surfaceBlock, BlockHitResult hit, boolean modifierHeld
   ) {
      FastPlaceSession session = SESSIONS.computeIfAbsent(player.getUUID(), ignored -> new FastPlaceSession());
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
            FastPlaceMessages.actionBar(player, FastPlaceMessages.text("fastformer.message.closed_points", session.points().size()));
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
      boolean restoring = WorldHistoryManager.restoreActive(player);
      cancel(player);
      if (!restoring) {
         cancelTask(player);
         OperationManager.cancelTask(player);
      }
      FastPlaceMessages.actionBar(player, FastPlaceMessages.text("fastformer.message.quit"));
   }

   public static TaskCancellationResult cancelTask(ServerPlayer player) {
      return cancelTask(new WorldTaskContext(player.getServer(), player.getUUID()));
   }

   static TaskCancellationResult cancelTask(WorldTaskContext context) {
      PlacementTask task = TASKS.get(context.owner());
      if (task == null) {
         return TaskCancellationResult.NOT_ACTIVE;
      }
      // Accept first, then drop the queue slot. A refused or failed handover keeps both
      // the task and the extracted snapshot, so the only record of a partial write stays.
      TaskCancellationResult result = transferToRecovery(context, task);
      if (result == TaskCancellationResult.CANCELLED_BEFORE_WRITE
         || result == TaskCancellationResult.ROLLBACK_STARTED) {
         TASKS.remove(context.owner(), task);
      }
      return result;
   }

   static void addTaskForTest(UUID owner, PlacementTask task) {
      TASKS.put(owner, task);
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
      BlockPos freeScrollCandidateOffset = null;
      if (session != null && stage == FastPlaceStage.LINE && lineCandidate != null) {
         freeScrollCandidateOffset = lineCandidate.subtract(session.points().getFirst());
      }
      FastPlaceMode nextMode = settings.cycleMode(player, stage);
      if (session != null) {
         session.onModeChanged(nextMode == LineMode.FREE_SCROLL ? freeScrollCandidateOffset : null);
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
         FastPlaceSettings settings = FastPlaceSettings.load(player);
         List<BlockPos> points = session.submissionPoints(settings.lineMode());
         if (points.isEmpty()) {
            FastPlaceMessages.actionBar(player, FastPlaceMessages.text("fastformer.message.operation_empty"));
            FastPlaceNetwork.syncPreview(player, session);
            return;
         }
         Optional<BlockState> placeState = PlaceableItems.placementState(
            player.getMainHandItem(), player, session.placementContext()
         );
         if (placeState.isPresent()) {
            int maxPlacement = settings.maxPlacement();
            boolean polygonHeightConfirmed = session.polygonHeightConfirmed();
            PolygonVolumeShape polygonVolumeShape = session.polygonVolumeShape();
            FastPlaceGeometry.Modes modes = settings.modes().withFaceTieBias(effectiveFaceTieBias(session, settings));
            BlockState state = placeState.get();
            ResolvedPlacementEffect effect = PlacementEffectResolver.resolve(
               player, settings, session, state, modes, points
            ).orElse(null);
            long estimatedPlacement = PlacementBlockEstimate.upperBound(
               points, modes, polygonHeightConfirmed, polygonVolumeShape, maxPlacement
            );
            PlacementGenerationPlan generationPlan = new PlacementGenerationPlan(
               points,
               modes,
               polygonHeightConfirmed,
               polygonVolumeShape,
               effect,
               estimatedPlacement,
               taskPlan(player, settings, state, effect == null ? null : effect.stateOverrides())
            );
            var generationAdmission = WorldOperationMemory.generationAdmission(
               generationPlan.estimatedTargetBlocks(), generationPlan.additionalGeneratedBlockSets()
            );
            if (!generationAdmission.allowed()) {
               LOGGER.warn(
                  "FastFormer placement generation rejected: stage={}, fillMode={}, pointCount={}, baseEstimate={}, targetEstimate={}, additionalSets={}, requestedBytes={}, usableBytes={}",
                  FastPlaceGeometry.effectiveStage(points, modes.faceMode(), session.polygonClosed()),
                  modes.fillMode(),
                  points.size(),
                  generationPlan.estimatedBlocks(),
                  generationPlan.estimatedTargetBlocks(),
                  generationPlan.additionalGeneratedBlockSets(),
                  generationAdmission.requestedBytes(),
                  generationAdmission.usableBytes()
               );
               cancel(player);
               FastPlaceMessages.actionBar(player, FastPlaceMessages.text("fastformer.message.operation_memory_unsafe"));
               return;
            }
            if (generationPlan.estimatedTargetBlocks() > SYNCHRONOUS_PLACEMENT_LIMIT || generationAdmission.throttled()) {
               Optional<MemoryReservation> generationReservation = WorldOperationMemory.reserveGeneration(
                  generationPlan.estimatedTargetBlocks(), generationPlan.additionalGeneratedBlockSets()
               );
               if (generationReservation.isEmpty()) {
                  enqueueTask(player, generationPlan.waitForGenerationMemory());
                  cancel(player);
                  FastPlaceMessages.actionBar(player, FastPlaceMessages.text("fastformer.message.world_write_waiting"));
                  return;
               }
               enqueueTask(player, generationPlan.generateAsync(generationReservation.orElseThrow()));
               cancel(player);
               FastPlaceMessages.actionBar(player, FastPlaceMessages.text("fastformer.message.placement_generating"));
            } else {
               PlacementGenerationPlan.GeneratedPlacement generated;
               Optional<MemoryReservation> generationReservation = WorldOperationMemory.reserveGeneration(
                  generationPlan.estimatedTargetBlocks(), generationPlan.additionalGeneratedBlockSets()
               );
               if (generationReservation.isEmpty()) {
                  enqueueTask(player, generationPlan.waitForGenerationMemory());
                  cancel(player);
                  FastPlaceMessages.actionBar(player, FastPlaceMessages.text("fastformer.message.world_write_waiting"));
                  return;
               }
               try {
                  generated = generationPlan.generateNow();
               } catch (RuntimeException | OutOfMemoryError exception) {
                  cancel(player);
                  FastPlaceMessages.actionBar(player, FastPlaceMessages.text("fastformer.message.placement_generation_failed"));
                  LOGGER.error("FastFormer synchronous placement generation failed for {}", player.getUUID(), exception);
                  return;
               } finally {
                  generationReservation.orElseThrow().close();
               }
               BlockGenerationResult generationResult = generated.result();
               Set<BlockPos> blocks = generationResult.blocks();
               if (generationResult.status() == BlockGenerationResult.Status.CONSTRAINTS_FAILED) {
                  cancel(player);
                  FastPlaceMessages.chat(player, "fastformer.message.face_generation_constraints_failed");
               } else if (generationResult.status() == BlockGenerationResult.Status.LIMIT_EXCEEDED
                  || blocks.size() > maxPlacement) {
                  cancel(player);
                  FastPlaceMessages.actionBar(player, FastPlaceMessages.text("fastformer.message.placement_too_large", maxPlacement));
               } else if (blocks.isEmpty()) {
                  cancel(player);
                  FastPlaceMessages.actionBar(player, FastPlaceMessages.text("fastformer.message.operation_empty"));
                } else {
                   enqueueTask(player, PlacementTask.ready(blocks, generationPlan.taskPlan()));
                   cancel(player);
                   FastPlaceMessages.actionBar(player, FastPlaceMessages.text("fastformer.message.placement_queued", blocks.size()));
                }
            }
         } else {
            FastPlaceMessages.actionBar(player, FastPlaceMessages.text("fastformer.message.placement_hold_block"));
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
      enqueueTask(
         player,
         PlacementTask.ready(best, taskPlan(player, settings, placeState.get(), null))
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
      return queuePlacementResult(player, BlockGenerationResult.fromLegacy(blocks), state);
   }

   private static boolean queuePlacementResult(
      ServerPlayer player,
      BlockGenerationResult generationResult,
      BlockState state
   ) {
      FastPlaceSettings settings = FastPlaceSettings.load(player);
      int maxPlacement = settings.maxPlacement();
      if (generationResult.status() == BlockGenerationResult.Status.CONSTRAINTS_FAILED) {
         FastPlaceMessages.chat(player, "fastformer.message.face_generation_constraints_failed");
         return false;
      }
      if (generationResult.status() == BlockGenerationResult.Status.LIMIT_EXCEEDED
         || generationResult.blocks().size() > maxPlacement) {
         FastPlaceMessages.actionBar(player, FastPlaceMessages.text("fastformer.message.placement_too_large", maxPlacement));
         return false;
      }
      if (placementBusy(player)) {
         FastPlaceMessages.actionBar(player, FastPlaceMessages.text("fastformer.message.placement_task_running"));
         return false;
      }
      Set<BlockPos> targets = generationResult.blocks();
      if (targets.isEmpty()) {
         FastPlaceMessages.actionBar(player, FastPlaceMessages.text("fastformer.message.operation_empty"));
         return false;
      }
      enqueueTask(player, PlacementTask.ready(targets, taskPlan(player, settings, state, null)));
      FastPlaceMessages.actionBar(player, FastPlaceMessages.text("fastformer.message.placement_queued", targets.size()));
      return true;
   }

   public static boolean queueGeneratedPlacement(
      ServerPlayer player,
      Supplier<BlockGenerationResult> generator,
      BlockState state,
      long estimatedScanCells,
      long targetCapacity
   ) {
      FastPlaceSettings settings = FastPlaceSettings.load(player);
      int maxPlacement = settings.maxPlacement();
      if (placementBusy(player)) {
         FastPlaceMessages.actionBar(player, FastPlaceMessages.text("fastformer.message.placement_task_running"));
         return false;
      }
      var generationAdmission = WorldOperationMemory.generationAdmission(targetCapacity, 0L);
      if (!generationAdmission.allowed()) {
         FastPlaceMessages.actionBar(player, FastPlaceMessages.text("fastformer.message.operation_memory_unsafe"));
         return false;
      }
      Optional<MemoryReservation> generationReservation = WorldOperationMemory.reserve(generationAdmission);
      if (generationReservation.isEmpty()) {
         enqueueTask(player, PlacementTask.waitingForGeneration(
            generator, null, taskPlan(player, settings, state, null), targetCapacity, 0L));
         FastPlaceMessages.actionBar(player, FastPlaceMessages.text("fastformer.message.world_write_waiting"));
         return true;
      }
      if (estimatedScanCells <= SYNCHRONOUS_PLACEMENT_LIMIT && !generationAdmission.throttled()) {
         BlockGenerationResult generationResult;
         try {
            generationResult = java.util.Objects.requireNonNull(generator.get(), "generation result");
         } catch (RuntimeException | OutOfMemoryError exception) {
            FastPlaceMessages.actionBar(player, FastPlaceMessages.text("fastformer.message.placement_generation_failed"));
            LOGGER.error("FastFormer synchronous placement generation failed for {}", player.getUUID(), exception);
            return false;
         } finally {
            generationReservation.orElseThrow().close();
         }
         return queuePlacementResult(player, generationResult, state);
      }
      CompletableFuture<BlockGenerationResult> future;
      try {
         future = CompletableFuture.supplyAsync(generator);
      } catch (RuntimeException | OutOfMemoryError exception) {
         generationReservation.orElseThrow().close();
         FastPlaceMessages.actionBar(player, FastPlaceMessages.text("fastformer.message.placement_generation_failed"));
         LOGGER.error("FastFormer asynchronous placement generation could not be scheduled for {}", player.getUUID(), exception);
         return false;
      }
      enqueueTask(player, PlacementTask.generatingResult(
         future,
         taskPlan(player, settings, state, null),
         generationReservation.orElseThrow()
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
      // Preserve the breadth-first order used to discover the plane while
      // avoiding a second full-size copy before the placement task owns it.
      return java.util.Collections.unmodifiableSet(visited);
   }

   public static void setMaxPlacement(ServerPlayer player, int value) {
      FastPlaceSettings settings = FastPlaceSettings.load(player);
      settings.setMaxPlacement(player, value);
      FastPlaceMessages.chat(player, "fastformer.message.max_placement", settings.maxPlacement());
   }

   public static boolean remove(ServerPlayer player) {
      SESSIONS.remove(player.getUUID());
      MODIFIER_HELD.remove(player.getUUID());
      PARKED_SESSIONS.forget(player.getUUID());
      QuickReplaceDedupe.forget(player.getUUID());
      OperationManager.remove(player);
      GeometryManager.remove(player);
      WorldHistoryManager.remove(player);
      ServerInputDispatcher.clearPlacementActions(player.getUUID());
      FastPlaceNetwork.forgetActivity(player);
      return true;
   }

   private static PlacementTaskPlan taskPlan(
      ServerPlayer player,
      FastPlaceSettings settings,
      BlockState state,
      Function<Set<BlockPos>, Map<BlockPos, BlockState>> stateResolver
   ) {
      return new PlacementTaskPlan(
         state,
         stateResolver,
         settings.placementConflictMode(),
         settings.placementUpdateMode(),
         settings.maxPlacement(),
         player.serverLevel().dimension()
      );
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
      QuickReplaceDedupe.forget(owner);
      // Modifier state belongs to the old connection, unlike the workflow
      // points and shape data that remain owned by the player UUID.
      FastPlaceSession session = SESSIONS.get(owner);
      if (session != null) {
         session.setModifierHeld(false);
      }
      FastPlaceNetwork.forgetActivity(player);
   }

   /**
    * Handles a dimension change without cancelling any world task.
    *
    * <p>A running or committed task keeps the save, its original dimension and
    * its own operation id, so it continues to run in the world it started in.
    * Only the old environment binding ends: the editing session of the source
    * dimension moves aside, and the session of the target dimension becomes
    * active. Points that belong to one dimension are never offered to another.</p>
    */
   public static void handleDimensionChange(ServerPlayer player, ResourceKey<Level> from, ResourceKey<Level> to) {
      if (player == null) {
         return;
      }
      UUID owner = player.getUUID();
      MODIFIER_HELD.remove(owner);
      parkDimensionSession(owner, from);
      OperationManager.parkDimensionSession(owner, from);
      GeometryManager.parkDimensionSession(owner, from);
      ServerInputDispatcher.clearPlacementActions(owner);
      restoreDimensionSession(owner, to);
      OperationManager.restoreDimensionSession(owner, to);
      GeometryManager.restoreDimensionSession(owner, to);
      FastPlaceNetwork.forgetActivity(player);
      syncCurrentPreview(player);
   }

   static void parkDimensionSession(UUID owner, ResourceKey<Level> dimension) {
      if (owner == null || dimension == null) {
         return;
      }
      PARKED_SESSIONS.park(owner, dimension, SESSIONS.remove(owner));
   }

   static void restoreDimensionSession(UUID owner, ResourceKey<Level> dimension) {
      if (owner == null || dimension == null) {
         return;
      }
      FastPlaceSession session = PARKED_SESSIONS.take(owner, dimension);
      if (session == null) {
         return;
      }
      if (SESSIONS.putIfAbsent(owner, session) != null) {
         PARKED_SESSIONS.park(owner, dimension, session);
      }
   }

   static void putSessionForTest(UUID owner, FastPlaceSession session) {
      SESSIONS.put(owner, session);
   }

   static boolean activeSessionForTest(UUID owner) {
      return SESSIONS.containsKey(owner);
   }

   static boolean parkedSessionForTest(UUID owner, ResourceKey<Level> dimension) {
      return PARKED_SESSIONS.peek(owner, dimension) != null;
   }

   static List<BlockPos> sessionPointsForTest(UUID owner) {
      FastPlaceSession session = SESSIONS.get(owner);
      return session == null ? List.of() : session.points();
   }

   /** Drops server-bound sessions/tasks before a world instance is replaced. */
   public static void clearServer() {
      for (PlacementTask task : TASKS.values()) {
         task.cancel();
         task.releaseMemoryReservation();
      }
      TASKS.clear();
      HANDOVERS.clear();
      SESSIONS.clear();
      MODIFIER_HELD.clear();
      PARKED_SESSIONS.clear();
      QuickReplaceDedupe.clearAll();
      OperationManager.clearServer();
      GeometryManager.clearServer();
      ServerInputDispatcher.clearAllPlacementActions();
   }

   /**
    * Hands every write-blocked placement task to the recovery path.
    *
    * <p>The scheduler calls this instead of {@link #tickWorld} while the global write gate
    * is closed. A placement task has no gate check inside its own tick, so it cannot end
    * by itself. The queue slot leaves only after the recovery path accepts the snapshot.
    */
   public static void handOverBlockedTasks(net.minecraft.server.MinecraftServer server) {
      for (UUID owner : List.copyOf(TASKS.keySet())) {
         handOverBlockedTask(new WorldTaskContext(server, owner));
      }
   }

   private static void handOverBlockedTask(WorldTaskContext context) {
      PlacementTask task = TASKS.get(context.owner());
      if (task == null) {
         return;
      }
      TaskCancellationResult handover = transferToRecovery(context, task);
      if (handover == TaskCancellationResult.CANCELLED_BEFORE_WRITE
         || handover == TaskCancellationResult.ROLLBACK_STARTED) {
         TASKS.remove(context.owner(), task);
         context.actionBar(FastPlaceMessages.text("fastformer.message.recovery_journal_blocked"));
         return;
      }
      context.actionBar(FastPlaceMessages.text("fastformer.message.recovery_journal_blocked"));
   }

   /**
    * Moves one task into the recovery path, or keeps both the task and the extracted
    * snapshot when the recovery path does not accept them.
    *
    * <p>The snapshot is extracted once. A retry reuses the held snapshot, because the
    * extraction already removed the before/after record from the task.
    */
   private static TaskCancellationResult transferToRecovery(WorldTaskContext context, PlacementTask task) {
      UUID owner = context.owner();
      try {
         WorldRecoverySnapshot held = HANDOVERS.get(owner);
         if (held == null) {
            held = task.stopAndTransferRecovery();
            HANDOVERS.put(owner, held);
         }
         TaskCancellationResult result = WorldHistoryManager.acceptTransferredRecovery(
            context,
            task.dimension(),
            held,
            task.journal(),
            () -> task.releaseAfterCancelledJournal(context),
            task::releaseMemoryReservation
         );
         if (result == TaskCancellationResult.CANCELLED_BEFORE_WRITE
            || result == TaskCancellationResult.ROLLBACK_STARTED) {
            HANDOVERS.remove(owner);
         }
         return result;
      } catch (RuntimeException | OutOfMemoryError failure) {
         // The acceptance boundary accepts a snapshot only by returning a handled result,
         // and the boundary performs no throwing work after a transfer. An exception
         // therefore means "not accepted": keep the task and the extracted snapshot.
         LOGGER.error("FastFormer kept a blocked placement task of {} with its recovery snapshot",
            owner, failure);
         return TaskCancellationResult.RECOVERY_BLOCKED;
      }
   }

   public static void tickWorld(net.minecraft.server.MinecraftServer server) {
      for (UUID owner : List.copyOf(TASKS.keySet())) {
         if (!PersistentRecoveryJournal.writesAllowed()) {
            break;
         }
         if (HANDOVERS.containsKey(owner)) {
            // A pending handover holds the only record of this task's writes. Ticking the
            // task would empty that record, so the handover owns the owner until it ends.
            continue;
         }
         if (WorldHistoryManager.busy(owner)) {
            continue;
         }
         tickTask(new WorldTaskContext(server, owner));
      }
   }

   private static void enqueueTask(ServerPlayer player, PlacementTask task) {
      // A new task cannot own a snapshot extracted from an earlier one.
      HANDOVERS.remove(player.getUUID());
      TASKS.put(player.getUUID(), task);
      WorldTaskContext context = new WorldTaskContext(player.getServer(), player.getUUID());
      context.withResume(() -> resumeTask(context, task)).enqueueResume();
   }

   private static void resumeTask(WorldTaskContext context, PlacementTask task) {
      if (TASKS.get(context.owner()) == task
         && !HANDOVERS.containsKey(context.owner())
         && PersistentRecoveryJournal.writesAllowed()
         && !WorldHistoryManager.busy(context.owner())) {
         tickTask(context);
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
               task.releaseMemoryReservation();
               context.chat(FastPlaceMessages.text("fastformer.message.face_generation_constraints_failed"));
            } else if (task.failed()) {
               TASKS.remove(owner);
               settleFailedTask(context, task);
               context.actionBar(FastPlaceMessages.text("fastformer.message.placement_generation_failed"));
            } else if (task.exceededLimit()) {
               TASKS.remove(owner);
               task.releaseLease(context);
               context.actionBar(FastPlaceMessages.text("fastformer.message.placement_exceeds_max", task.maxPlacement()));
            } else if (task.memoryUnsafe()) {
               TASKS.remove(owner);
               settleFailedTask(context, task);
               context.actionBar(FastPlaceMessages.text("fastformer.message.operation_memory_unsafe"));
            } else if (task.total() == 0) {
               TASKS.remove(owner);
               task.releaseLease(context);
               task.releaseMemoryReservation();
               context.actionBar(FastPlaceMessages.text("fastformer.message.operation_empty"));
            } else if (context.level(task.dimension()) == null) {
               task.markWorldUnloaded();
               task.releaseMemoryReservation();
               context.actionBar(FastPlaceMessages.text("fastformer.message.history_dimension_failed"));
            } else if (!task.ensureMemoryReservation()) {
               if (task.memoryUnsafe()) {
                  TASKS.remove(owner);
                  settleFailedTask(context, task);
                  context.actionBar(FastPlaceMessages.text("fastformer.message.operation_memory_unsafe"));
               } else {
                  context.actionBar(FastPlaceMessages.text("fastformer.message.world_write_waiting"));
               }
            } else if (!task.acquireLease(context)) {
               context.actionBar(FastPlaceMessages.text("fastformer.message.world_write_waiting"));
            } else {
               WorldTaskBudget budget = WorldTaskBudget.forSmallOperation(
                  task.memoryThrottled(), task.total(), task.previousBatchCells(), task.previousBatchNanos()
               );
               if (!task.validateSnapshots(context.level(task.dimension()), budget)) {
                context.actionBar(FastPlaceMessages.text("fastformer.message.placement_validating", task.validationRemaining()));
               } else if (task.memoryUnsafe()) {
                  TASKS.remove(owner);
                  settleFailedTask(context, task);
                  context.actionBar(FastPlaceMessages.text("fastformer.message.operation_memory_unsafe"));
               } else if (task.failed()) {
                  TASKS.remove(owner);
                  settleFailedTask(context, task);
                  LOGGER.warn(
                     "FastFormer placement snapshot validation failed for {} ({}): {}",
                     owner,
                     task.operationId(),
                     task.failureReason()
                  );
                  context.actionBar(FastPlaceMessages.text("fastformer.message.placement_snapshot_validation_failed"));
               } else {
                ServerLevel level = context.level(task.dimension());
               JournalPreparation journalPreparation = task.prepareJournal(context.withResume(() -> resumeTask(context, task)));
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
               if (task.memoryThrottled()) {
                  context.actionBar(FastPlaceMessages.text("fastformer.message.placement_memory_throttled"));
               }
               long batchStartedAt = System.nanoTime();
               int batchStartProcessed = task.processed();
               while (PersistentRecoveryJournal.writesAllowed()
                   && task.blocks().hasNext()
                   && (budget.tryConsume() || (!task.hasWrites() && budget.tryConsumeFirstWrite()))) {
                   BlockPos pos = task.blocks().next();
                   task.consumed();
                   task.place(context, level, pos);
                  if (task.failed()) {
                     break;
                  }
               }
               task.recordBatch(task.processed() - batchStartProcessed, System.nanoTime() - batchStartedAt);

               if (!PersistentRecoveryJournal.writesAllowed()) {
                  TASKS.remove(owner, task);
                  settleFailedTask(context, task);
                  context.actionBar(FastPlaceMessages.text("fastformer.message.recovery_journal_blocked"));
                  return;
               }

               if (task.failed()) {
                  TASKS.remove(owner);
                  settleFailedTask(context, task);
                  LOGGER.warn(
                     "FastFormer placement write failed for {}: {} ({})",
                     owner,
                     task.failureReason(),
                     task.metricsSummary()
                  );
                  context.actionBar(failureStatus(task, context.owner()));
               } else if (task.readyToFinalize()) {
                  if (!task.hasWrites()) {
                     TASKS.remove(owner, task);
                     task.releaseAfterCancelledJournal(context);
                     task.releaseMemoryReservation();
                     task.releaseCommittedTransactionState();
                     task.markComplete();
                     context.chat(FastPlaceMessages.text("fastformer.message.placement_placed", 0));
                     return;
                  }
                  task.releaseGenerationState();
                  task.resizeMemoryReservationForTransaction();
                  if (!task.finalizeSnapshots(level, budget)) {
                      context.actionBar(FastPlaceMessages.text("fastformer.message.placement_finalizing"));
                      return;
                   }
                   if (task.failed()) {
                      TASKS.remove(owner);
                      settleFailedTask(context, task);
                      LOGGER.warn(
                         "FastFormer placement finalization failed for {}: {} ({})",
                         owner,
                         task.failureReason(),
                         task.metricsSummary()
                      );
                      context.actionBar(failureStatus(task, context.owner()));
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
                     context.actionBar(failureStatus(task, context.owner()));
                   } else {
                      JournalPreparation historyCommit = WorldHistoryManager.pollPreparedOperation(
                         context, task.preparedBatch(), task.journal()
                      );
                      if (historyCommit == JournalPreparation.PENDING) {
                         context.actionBar(FastPlaceMessages.text("fastformer.message.history_saving"));
                         return;
                      }
                      if (historyCommit == JournalPreparation.FAILED) {
                      TASKS.remove(owner, task);
                      settleFailedTask(context, task);
                      context.actionBar(failureStatus(task, context.owner()));
                      } else {
                         TASKS.remove(owner, task);
                         task.releaseLease(context);
                         task.releaseMemoryReservation();
                         task.releaseCommittedTransactionState();
                         task.markComplete();
                         LOGGER.info("FastFormer placement operation {} finished: {}", task.operationId(), task.metricsSummary());
                         context.chat(FastPlaceMessages.text("fastformer.message.placement_placed", task.placed()));
                      }
                  }
               } else if (!task.snapshotsComplete()) {
                  context.actionBar(FastPlaceMessages.text("fastformer.message.placement_validating", task.validationRemaining()));
               } else {
                  context.actionBar(FastPlaceMessages.text(
                     "fastformer.message.placement_progress", task.processed(), task.total(), task.placed()
                  ));
                }
               }
            }
         } else if (task.waitingForGenerationMemory()) {
            context.actionBar(FastPlaceMessages.text("fastformer.message.world_write_waiting"));
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
         LOGGER.error(
            "FastFormer placement task failed for {} and was transferred to recovery: {}",
            owner,
            task.metricsSummary(),
            exception
         );
            context.actionBar(failureStatus(task, context.owner()));
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

   private static void settleFailedTask(WorldTaskContext context, PlacementTask task) {
      TaskCancellationResult result = WorldHistoryManager.acceptStoppedTask(context, task);
      if (result.recoveryCreated()) {
         task.markRecoveryTaskCreated();
      }
   }

   private static net.minecraft.network.chat.MutableComponent failureStatus(PlacementTask task, UUID owner) {
      if (task.failurePhase() == WorldOperationPhase.COMMIT && task.hasWrites()) {
         return FastPlaceMessages.text(
            "fastformer.message.placement_history_unavailable",
            task.operationId().toString()
         );
      }
      String key = task.recoveryTaskCreated()
         ? "fastformer.message.placement_failed_rollback"
         : "fastformer.message.placement_failed_no_recovery";
      return FastPlaceMessages.text(
         key,
         task.failurePhase().name(),
         task.operationId().toString(),
         task.failureReason()
      );
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

}
