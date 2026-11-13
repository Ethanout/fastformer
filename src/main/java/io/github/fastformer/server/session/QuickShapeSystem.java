package io.github.fastformer.server.session;


import io.github.fastformer.server.history.HistorySystem;

import io.github.fastformer.server.gate.ServerOperationGates;
import io.github.fastformer.server.session.slot.PlayerSessions;

import com.mojang.logging.LogUtils;
import io.github.fastformer.fastplace.geometry.GeometryNumbers;
import io.github.fastformer.fastplace.geometry.generation.BlockGenerationResult;
import io.github.fastformer.fastplace.geometry.generation.FastPlaceGeometry;
import io.github.fastformer.fastplace.geometry.generation.LineTieBias;
import io.github.fastformer.fastplace.placement.context.PlaceableItems;
import io.github.fastformer.fastplace.placement.context.PlacementContextSnapshot;
import io.github.fastformer.fastplace.placement.effect.PlacementEffectResolver;
import io.github.fastformer.fastplace.placement.effect.ResolvedPlacementEffect;
import io.github.fastformer.fastplace.placement.plan.PlacementBlockEstimate;
import io.github.fastformer.fastplace.placement.plan.PlacementGenerationPlan;
import io.github.fastformer.fastplace.quickshape.FaceMode;
import io.github.fastformer.fastplace.quickshape.LineMode;
import io.github.fastformer.fastplace.quickshape.PointMode;
import io.github.fastformer.fastplace.quickshape.PolygonVolumeShape;
import io.github.fastformer.fastplace.quickshape.QuickShapeDraft;
import io.github.fastformer.fastplace.quickshape.QuickShapeMode;
import io.github.fastformer.fastplace.quickshape.QuickShapeModeRules;
import io.github.fastformer.fastplace.quickshape.QuickShapeStage;
import io.github.fastformer.fastplace.quickshape.QuickShapeTransitionExecutor;
import io.github.fastformer.fastplace.quickshape.QuickShapeWorkflow;
import io.github.fastformer.fastplace.quickshape.RaycastPlacement;
import io.github.fastformer.fastplace.quickshape.VolumeMode;
import io.github.fastformer.fastplace.session.*;
import io.github.fastformer.fastplace.settings.FastPlaceSettings;
import io.github.fastformer.server.task.block.PlacementTask;
import io.github.fastformer.server.task.block.PlacementTaskPlan;
import io.github.fastformer.fastplace.text.FastPlaceMessages;
import io.github.fastformer.fastplace.world.*;
import io.github.fastformer.fastplace.world.memory.MemoryReservation;
import io.github.fastformer.fastplace.world.memory.WorldOperationMemory;
import io.github.fastformer.network.FastPlaceNetwork;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

public final class QuickShapeSystem implements io.github.fastformer.core.lifecycle.Scoped {
   private static final Logger LOGGER = LogUtils.getLogger();
   /** Generate ordinary placements on the server thread up to this size. */
   private static final long SYNCHRONOUS_PLACEMENT_LIMIT = 262_144L;
   final PlayerSessions<QuickShapeDraft> sessions = new PlayerSessions<>();
   private final SessionSystem runtime;
   QuickShapeSystem(SessionSystem runtime) {
      this.runtime = runtime;
   }

   public static QuickShapeSystem of(ServerPlayer player) { return of(player.getServer()); }
   public static QuickShapeSystem of(net.minecraft.server.MinecraftServer server) { return SessionSystem.of(server).quickShape; }

   public Optional<QuickShapeDraft> session(ServerPlayer player) {
      return Optional.ofNullable(sessions.active(player.getUUID()));
   }

   public boolean active(ServerPlayer player) {
      return sessions.hasActive(player.getUUID());
   }

   public static FastPlaceGeometry.Modes effectiveModes(FastPlaceSettings settings, QuickShapeDraft session) {
      FastPlaceGeometry.Modes modes = settings.modes()
         .withLineMode(io.github.fastformer.server.interaction.profile.ProfileConstraints.effectiveLineMode(session, settings))
         .withModifierHeld(session != null && session.modifierHeld());
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
      if (session != null && session.stage(settings.faceMode())
         == QuickShapeStage.LINE && io.github.fastformer.server.interaction.profile.ProfileConstraints.effectiveLineMode(session, settings) == LineMode.RAYCAST) {
         return modes.withRaycastPlacement(
            session.modifierHeld() ? RaycastPlacement.EMBEDDED : RaycastPlacement.SURFACE
         );
      }
      return modes;
   }

   public void addPoint(ServerPlayer player, BlockHitResult hit) {
      addPoint(player, hit.getBlockPos(), hit.getBlockPos().relative(hit.getDirection()), hit, runtime.modifierHeld(player));
   }

   public void addPoint(ServerPlayer player, BlockPos hitBlock, BlockPos surfaceBlock) {
      addPoint(player, hitBlock, surfaceBlock, null, runtime.modifierHeld(player));
   }

   public void addInitialPoint(ServerPlayer player, BlockHitResult hit, boolean modifierHeld) {
      addInitialPoint(player, hit, modifierHeld, player.getEyePosition(), player.getViewVector(1.0F));
   }

   /** Starts a draft with the physical press ray, rather than the server's later camera state. */
   public void addInitialPoint(ServerPlayer player, BlockHitResult hit, boolean embedded, Vec3 eye, Vec3 view) {
      addPoint(player, hit.getBlockPos(), hit.getBlockPos().relative(hit.getDirection()), hit,
         runtime.modifierHeld(player), eye, view, embedded ? RaycastPlacement.EMBEDDED : RaycastPlacement.SURFACE);
   }

   private void addPoint(
      ServerPlayer player, BlockPos hitBlock, BlockPos surfaceBlock, BlockHitResult hit, boolean modifierHeld
   ) {
      addPoint(player, hitBlock, surfaceBlock, hit, modifierHeld, player.getEyePosition(), player.getViewVector(1.0F));
   }

   private void addPoint(
      ServerPlayer player, BlockPos hitBlock, BlockPos surfaceBlock, BlockHitResult hit, boolean modifierHeld, Vec3 eye, Vec3 view
   ) {
      addPoint(player, hitBlock, surfaceBlock, hit, modifierHeld, eye, view, null);
   }

   /** Explicit entry anchors point one to the captured interaction, regardless of point mode. */
   public void beginAtInteraction(ServerPlayer player, BlockHitResult hit, Vec3 eye, Vec3 view, boolean embedded) {
      beginAtInteraction(player, PlacementContextSnapshot.capture(player.level(), player, player.getMainHandItem(), hit, embedded), eye, view);
   }

   public void beginAtInteraction(ServerPlayer player, PlacementContextSnapshot placement, Vec3 eye, Vec3 view) {
      beginAtInteraction(player, placement, eye, view, io.github.fastformer.common.interaction.binding.SessionProfiles.NONE,
         io.github.fastformer.common.interaction.binding.SessionProfile.NONE);
   }

   public void beginAtInteraction(ServerPlayer player, PlacementContextSnapshot placement, Vec3 eye, Vec3 view,
      net.minecraft.resources.ResourceLocation profileId, io.github.fastformer.common.interaction.binding.SessionProfile profile) {
      if (active(player)) return;
      QuickShapeDraft draft = new QuickShapeDraft(profileId, profile);
      sessions.activate(player.getUUID(), draft);
      draft.setModifierHeld(runtime.modifierHeld(player));
      draft.capturePlacementContext(placement);
      dispatchQuickShape(player, draft, FastPlaceSettings.load(player).faceMode(),
         new QuickShapeWorkflow.Event.ConfirmPoint(placement.placementPosition(), eye, view, draft.modifierHeld()));
   }

   private void addPoint(
      ServerPlayer player, BlockPos hitBlock, BlockPos surfaceBlock, BlockHitResult hit, boolean modifierHeld,
      Vec3 eye, Vec3 view, RaycastPlacement initialPlacement
   ) {
      QuickShapeDraft session = sessions.getOrCreate(player.getUUID(), QuickShapeDraft::new);
      session.setModifierHeld(modifierHeld);
      FastPlaceSettings settings = FastPlaceSettings.load(player);
      int previousPointCount = session.points().size();
      FastPlaceGeometry.Modes modes = effectiveModes(settings, session);
      if (previousPointCount == 0 && initialPlacement != null) modes = modes.withRaycastPlacement(initialPlacement);
      if (previousPointCount == 0 && hit != null) {
         boolean embedded = settings.pointMode() == PointMode.RAYCAST
            && modes.raycastPlacement() == RaycastPlacement.EMBEDDED;
         PlacementContextSnapshot placement = PlacementContextSnapshot.capture(
            player.level(), player, player.getMainHandItem(), hit, embedded
         );
         session.capturePlacementContext(placement);
         surfaceBlock = placement.placementPosition();
      }
      BlockPos point = FastPlaceGeometry.resolveCandidate(
         session.points(),
         session.polygonClosed(),
         hitBlock,
         surfaceBlock,
         session.faceBaseOffset(),
         session.volumeBaseOffset(),
         session.perpendicularAnchor(),
         eye,
         view,
         session.freeScrollOffset(),
         modes
      );
      dispatchQuickShape(player, session, settings.faceMode(), new QuickShapeWorkflow.Event.ConfirmPoint(
         point, eye, view, modifierHeld));
   }

   private boolean dispatchQuickShape(ServerPlayer player, QuickShapeDraft draft, FaceMode faceMode,
      QuickShapeWorkflow.Event event) {
      return QuickShapeTransitionExecutor.execute(draft, faceMode, event, new QuickShapeTransitionExecutor.Effects() {
         @Override
         public boolean accepts(QuickShapeWorkflow.Decision decision) {
            if (decision.action() != QuickShapeWorkflow.Action.SUBMIT) return true;
            if (placementBusy(player)) {
               FastPlaceMessages.actionBar(player, FastPlaceMessages.text("fastformer.message.placement_task_running"));
               return false;
            }
            if (PlaceableItems.placementState(player.getMainHandItem(), player, draft.placementContext()).isEmpty()) {
               FastPlaceNetwork.syncPreview(player, draft);
               return false;
            }
            return true;
         }

         @Override
         public void preview() {
            FastPlaceNetwork.syncPreview(player, draft);
         }

         @Override
         public void submit() {
            fill(player);
         }

         @Override
         public void finishClosedPath() {
            FastPlaceMessages.actionBar(player, FastPlaceMessages.text("fastformer.message.closed_points", draft.points().size()));
            runtime.cancelEditing(player);
         }

         @Override
         public void cancelEmptyDraft() {
            runtime.cancelEditing(player);
            FastPlaceMessages.actionBar(player, FastPlaceMessages.text("fastformer.message.cancelled"));
         }
      });
   }

   public io.github.fastformer.fastplace.quickshape.QuickShapeCandidateContext candidateContext(ServerPlayer player) {
      QuickShapeDraft draft = sessions.active(player.getUUID());
      if (draft == null) return null;
      return new io.github.fastformer.fastplace.quickshape.QuickShapeCandidateContext(draft.points(), draft.polygonClosed(),
         draft.faceBaseOffset(), draft.volumeBaseOffset(), draft.perpendicularAnchor(), draft.freeScrollOffset(),
         effectiveModes(FastPlaceSettings.load(player), draft), draft.modifierHeld());
   }

   public boolean confirmCapturedPoint(ServerPlayer player,
      io.github.fastformer.protocol.c2s.placement.QuickShapePointerPayload input, BlockHitResult hit,
      io.github.fastformer.fastplace.quickshape.QuickShapeCandidateContext context) {
      QuickShapeDraft draft = sessions.active(player.getUUID());
      if (draft == null || context.modifierHeld() != input.modifierHeld()) return false;
      FastPlaceSettings settings = FastPlaceSettings.load(player);
      BlockPos candidate = context.resolve(hit, input.eye(), input.view());
      if (!candidate.equals(input.candidate())) return false;
      return dispatchQuickShape(player, draft, settings.faceMode(), new QuickShapeWorkflow.Event.ConfirmPoint(
         candidate, input.eye(), input.view(), input.modifierHeld()));
   }

   public void undo(ServerPlayer player) {
      QuickShapeDraft session = sessions.active(player.getUUID());
      if (session != null) {
         dispatchQuickShape(player, session, FastPlaceSettings.load(player).faceMode(), QuickShapeWorkflow.Event.Undo.INSTANCE);
      }
   }

   public void sync(ServerPlayer player) {
      QuickShapeDraft session = sessions.active(player.getUUID());
      if (session != null) {
         FastPlaceNetwork.syncPreview(player, session);
      } else {
         FastPlaceNetwork.syncSettings(player);
      }
   }

   void endEditing(ServerPlayer player) {
      QuickShapeDraft session = sessions.end(player.getUUID());
      if (session != null) {
         session.onDestroyed();
         FastPlaceNetwork.clearPreview(player);
      }
   }

   public void cycleStageMode(ServerPlayer player) {
      cycleStageMode(player, null);
   }

   public void setStageMode(ServerPlayer player, QuickShapeMode mode) {
      FastPlaceSettings settings = FastPlaceSettings.load(player);
      QuickShapeDraft session = sessions.active(player.getUUID());
      if (!io.github.fastformer.server.interaction.profile.ProfileConstraints.allowsModeSwitch(session)) return;
      if (session != null && session.polygonClosed() && mode.stage() == QuickShapeStage.VOLUME) {
         return;
      }
      if (!QuickShapeModeRules.allowedModes(mode.stage(), settings.storedLineMode()).contains(mode)) {
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

   public void cycleStageMode(ServerPlayer player, BlockPos lineCandidate) {
      QuickShapeDraft session = sessions.active(player.getUUID());
      if (!io.github.fastformer.server.interaction.profile.ProfileConstraints.allowsModeSwitch(session)) return;
      FastPlaceSettings settings = FastPlaceSettings.load(player);
      if (session != null && session.polygonClosed() && !session.polygonHeightConfirmed()) {
         session.cyclePolygonVolumeShape();
         FastPlaceNetwork.syncPreview(player, session);
         return;
      }
      QuickShapeStage stage = session == null ? QuickShapeStage.LINE : effectiveStage(session, settings);
      BlockPos freeScrollCandidateOffset = null;
      if (session != null && stage == QuickShapeStage.LINE && lineCandidate != null) {
         freeScrollCandidateOffset = lineCandidate.subtract(session.points().getFirst());
      }
      QuickShapeMode nextMode = settings.cycleMode(player, stage);
      if (session != null) {
         session.onModeChanged(nextMode == LineMode.FREE_SCROLL ? freeScrollCandidateOffset : null);
         FastPlaceNetwork.syncPreview(player, session);
      } else {
         FastPlaceNetwork.syncSettings(player);
      }
   }

   public void cycleFillMode(ServerPlayer player) {
      FastPlaceSettings settings = FastPlaceSettings.load(player);
      settings.cycleFillMode(player);
      runtime.syncCurrentPreview(player);
   }

   public void adjustFreeScrollOffset(ServerPlayer player, int steps) {
      QuickShapeDraft session = sessions.active(player.getUUID());
      if (session != null && io.github.fastformer.server.interaction.profile.ProfileConstraints.effectiveLineMode(
         session, FastPlaceSettings.load(player)) != LineMode.FREE_SCROLL) return;
      if (session != null && steps != 0) {
         session.adjustFreeScrollOffset(player.getViewVector(1.0F), steps);
         FastPlaceNetwork.syncPreview(player, session);
      }
   }

   public boolean closePolygon(ServerPlayer player) {
      QuickShapeDraft session = sessions.active(player.getUUID());
      if (session != null && session.profile().maxStage().filter(
         stage -> stage.equals("point") || stage.equals("line")).isPresent()) return false;
      FastPlaceSettings settings = FastPlaceSettings.load(player);
      return session != null && dispatchQuickShape(player, session, settings.faceMode(), QuickShapeWorkflow.Event.ClosePath.INSTANCE);
   }

   public void scrollContext(ServerPlayer player, int steps) {
      QuickShapeDraft session = sessions.active(player.getUUID());
      if (session != null && steps != 0) {
         if (session.polygonClosed()) {
            return;
         }
         FastPlaceSettings settings = FastPlaceSettings.load(player);
         switch (effectiveStage(session, settings)) {
            case LINE -> {
               if (io.github.fastformer.server.interaction.profile.ProfileConstraints.effectiveLineMode(session, settings) != LineMode.FREE_SCROLL) {
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
   public boolean setContextValue(ServerPlayer player, double value) {
      if (!GeometryNumbers.finite(value)) {
         return false;
      }
      QuickShapeDraft session = sessions.active(player.getUUID());
      if (session == null) {
         return false;
      }
      FastPlaceSettings settings = FastPlaceSettings.load(player);
      if (session.polygonClosed()) {
         return false;
      }
      return switch (effectiveStage(session, settings)) {
         case LINE -> {
            if (io.github.fastformer.server.interaction.profile.ProfileConstraints.effectiveLineMode(session, settings) != LineMode.FREE_SCROLL) {
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
   public boolean setFaceAngle(ServerPlayer player, double value) {
      if (!GeometryNumbers.finite(value)) {
         return false;
      }
      QuickShapeDraft session = sessions.active(player.getUUID());
      if (session != null && effectiveStage(session, FastPlaceSettings.load(player)) == QuickShapeStage.FACE) {
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

   public boolean setLineAngleDistance(ServerPlayer player, int value) {
      QuickShapeDraft session = sessions.active(player.getUUID());
      if (session != null && session.stage(FastPlaceSettings.load(player).faceMode())
         == QuickShapeStage.LINE) {
         FastPlaceSettings settings = FastPlaceSettings.load(player);
         if (io.github.fastformer.server.interaction.profile.ProfileConstraints.effectiveLineMode(session, settings) != LineMode.FREE_SCROLL) {
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

   public boolean setVolumeDistance(ServerPlayer player, int value) {
      QuickShapeDraft session = sessions.active(player.getUUID());
      if (session != null
         && !session.polygonClosed()
         && effectiveStage(session, FastPlaceSettings.load(player)) == QuickShapeStage.VOLUME
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

   public void fill(ServerPlayer player) {
      fill(player, false);
   }

   public void fillConfirmed(ServerPlayer player) {
      fill(player, true);
   }

   private void fill(ServerPlayer player, boolean confirmedOnly) {
      QuickShapeDraft session = sessions.active(player.getUUID());
      if (session != null) {
         if (placementBusy(player)) {
            FastPlaceMessages.actionBar(player, FastPlaceMessages.text("fastformer.message.placement_task_running"));
            return;
         }
         FastPlaceSettings settings = FastPlaceSettings.load(player);
         List<BlockPos> points = confirmedOnly ? List.copyOf(session.points()) : session.submissionPoints(io.github.fastformer.server.interaction.profile.ProfileConstraints.effectiveLineMode(session, settings));
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
                  session.stage(modes.faceMode()),
                  modes.fillMode(),
                  points.size(),
                  generationPlan.estimatedBlocks(),
                  generationPlan.estimatedTargetBlocks(),
                  generationPlan.additionalGeneratedBlockSets(),
                  generationAdmission.requestedBytes(),
                  generationAdmission.usableBytes()
               );
               FastPlaceNetwork.syncPreview(player, session);
               FastPlaceMessages.actionBar(player, FastPlaceMessages.text("fastformer.message.operation_memory_unsafe"));
               return;
            }
            if (generationPlan.estimatedTargetBlocks() > SYNCHRONOUS_PLACEMENT_LIMIT || generationAdmission.throttled()) {
               Optional<MemoryReservation> generationReservation = WorldOperationMemory.reserveGeneration(
                  generationPlan.estimatedTargetBlocks(), generationPlan.additionalGeneratedBlockSets()
               );
               if (generationReservation.isEmpty()) {
                  runtime.placementTasks().enqueue(player, generationPlan.waitForGenerationMemory());
                  runtime.cancelEditing(player);
                  FastPlaceMessages.actionBar(player, FastPlaceMessages.text("fastformer.message.world_write_waiting"));
                  return;
               }
               runtime.placementTasks().enqueue(player, generationPlan.generateAsync(generationReservation.orElseThrow()));
               runtime.cancelEditing(player);
               FastPlaceMessages.actionBar(player, FastPlaceMessages.text("fastformer.message.placement_generating"));
            } else {
               PlacementGenerationPlan.GeneratedPlacement generated;
               Optional<MemoryReservation> generationReservation = WorldOperationMemory.reserveGeneration(
                  generationPlan.estimatedTargetBlocks(), generationPlan.additionalGeneratedBlockSets()
               );
               if (generationReservation.isEmpty()) {
                  runtime.placementTasks().enqueue(player, generationPlan.waitForGenerationMemory());
                  FastPlaceNetwork.syncPreview(player, session);
                  FastPlaceMessages.actionBar(player, FastPlaceMessages.text("fastformer.message.world_write_waiting"));
                  return;
               }
               try {
                  generated = generationPlan.generateNow();
               } catch (RuntimeException | OutOfMemoryError exception) {
                  FastPlaceNetwork.syncPreview(player, session);
                  FastPlaceMessages.actionBar(player, FastPlaceMessages.text("fastformer.message.placement_generation_failed"));
                  LOGGER.error("FastFormer synchronous placement generation failed for {}", player.getUUID(), exception);
                  return;
               } finally {
                  generationReservation.orElseThrow().close();
               }
               BlockGenerationResult generationResult = generated.result();
               Set<BlockPos> blocks = generationResult.blocks();
               if (generationResult.status() == BlockGenerationResult.Status.CONSTRAINTS_FAILED) {
                  FastPlaceNetwork.syncPreview(player, session);
                  FastPlaceMessages.chat(player, "fastformer.message.face_generation_constraints_failed");
               } else if (generationResult.status() == BlockGenerationResult.Status.LIMIT_EXCEEDED
                  || blocks.size() > maxPlacement) {
                  FastPlaceNetwork.syncPreview(player, session);
                  FastPlaceMessages.actionBar(player, FastPlaceMessages.text("fastformer.message.placement_too_large", maxPlacement));
               } else if (blocks.isEmpty()) {
                  FastPlaceNetwork.syncPreview(player, session);
                  FastPlaceMessages.actionBar(player, FastPlaceMessages.text("fastformer.message.operation_empty"));
                } else {
                   runtime.placementTasks().enqueue(player, PlacementTask.ready(blocks, generationPlan.taskPlan()));
                   runtime.cancelEditing(player);
                   FastPlaceMessages.actionBar(player, FastPlaceMessages.text("fastformer.message.placement_queued", blocks.size()));
                }
            }
         } else {
            FastPlaceMessages.actionBar(player, FastPlaceMessages.text("fastformer.message.placement_hold_block"));
         }
      }
   }

   public boolean queuePlacement(ServerPlayer player, Set<BlockPos> blocks, BlockState state) {
      return queuePlacementResult(player, BlockGenerationResult.fromLegacy(blocks), state);
   }

   /** Air-only fill uses the same task, recovery journal and undo record as placement. */
   public boolean queueAirFill(ServerPlayer player, Set<BlockPos> blocks, BlockState state) {
      var settings = FastPlaceSettings.load(player);
      if (blocks.isEmpty() || blocks.size() > settings.maxPlacement() || placementBusy(player)) return false;
      var plan = new PlacementTaskPlan(state, null, io.github.fastformer.workspace.submission.OperationConflictMode.KEEP_EXISTING,
         settings.placementUpdateMode(), settings.maxPlacement(), player.level().dimension(), true);
      runtime.placementTasks().enqueue(player, PlacementTask.ready(blocks, plan));
      FastPlaceMessages.actionBar(player, FastPlaceMessages.text("fastformer.fill.queued", blocks.size()));
      return true;
   }

   private boolean queuePlacementResult(
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
      runtime.placementTasks().enqueue(player, PlacementTask.ready(targets, taskPlan(player, settings, state, null)));
      FastPlaceMessages.actionBar(player, FastPlaceMessages.text("fastformer.message.placement_queued", targets.size()));
      return true;
   }

   public boolean queueGeneratedPlacement(
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
         runtime.placementTasks().enqueue(player, PlacementTask.waitingForGeneration(
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
      runtime.placementTasks().enqueue(player, PlacementTask.generatingResult(
         future,
         taskPlan(player, settings, state, null),
         generationReservation.orElseThrow()
      ));
      FastPlaceMessages.actionBar(player, FastPlaceMessages.text("fastformer.message.placement_generating"));
      return true;
   }

   void discardPlayer(UUID owner) {
      sessions.discard(owner);
   }

   private PlacementTaskPlan taskPlan(
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

   void parkDimensionSession(UUID owner, ResourceKey<Level> dimension) {
      sessions.park(owner, dimension);
   }

   void restoreDimensionSession(UUID owner, ResourceKey<Level> dimension) {
      sessions.restore(owner, dimension);
   }

   /** Drops server-bound sessions/tasks before a world instance is replaced. */
   @Override public void onDiscard() { sessions.clear(); }

   public boolean undoLast(ServerPlayer player) {
      return HistorySystem.of(player).requestUndo(player, 1);
   }

   private boolean placementBusy(ServerPlayer player) {
      return !ServerOperationGates.START_WRITE.test(player).allowed();
   }

   private QuickShapeStage effectiveStage(QuickShapeDraft session, FastPlaceSettings settings) {
      return session.stage(settings.faceMode());
   }

   public static LineTieBias effectiveFaceTieBias(QuickShapeDraft session, FastPlaceSettings settings) {
      if (settings.faceMode() == FaceMode.POLYGON || session.points().size() < 3) {
         return LineTieBias.DEFAULT;
      }
      return session.effectiveFaceTieBias(session.modifierHeld());
   }


}
