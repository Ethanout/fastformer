package io.github.fastformer.network;

import io.github.fastformer.fastplace.FastPlaceManager;
import io.github.fastformer.fastplace.FastPlaceActivity;
import io.github.fastformer.fastplace.FastPlaceSession;
import io.github.fastformer.fastplace.FastPlaceSettings;
import io.github.fastformer.fastplace.OperationConflictMode;
import io.github.fastformer.fastplace.PlacementUpdateMode;
import io.github.fastformer.fastplace.OperationSession;
import io.github.fastformer.fastplace.OperationManager;
import io.github.fastformer.fastplace.GeometrySession;
import io.github.fastformer.fastplace.GeometryManager;
import io.github.fastformer.fastplace.ServerInputDispatcher;
import io.github.fastformer.fastplace.OperationWorkspacePlanCodec;
import io.github.fastformer.fastplace.QuickReplaceManager;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

public final class FastPlaceNetwork {
   private static final long WORKSPACE_TRANSFER_TIMEOUT_NANOS = 30_000_000_000L;
   private static final Map<UUID, FastPlaceActivity> LAST_ACTIVITY = new ConcurrentHashMap<>();
   private static final Map<UUID, IncomingWorkspaceTransfer> WORKSPACE_TRANSFERS = new ConcurrentHashMap<>();
   /** Monotonic per-player sequence for operation preview packets. */
   private static final Map<UUID, Long> OPERATION_PREVIEW_REVISIONS = new ConcurrentHashMap<>();
   private FastPlaceNetwork() {
   }

   public static void register(IEventBus modBus) {
      modBus.addListener(FastPlaceNetwork::registerPayloads);
   }

   private static void registerPayloads(RegisterPayloadHandlersEvent event) {
      PayloadRegistrar registrar = event.registrar("57").optional();
      registrar.playToServer(ModifierStatePayload.TYPE, ModifierStatePayload.STREAM_CODEC, FastPlaceNetwork::handleModifierState);
      registrar.playToServer(MiddleConfirmSettingPayload.TYPE, MiddleConfirmSettingPayload.STREAM_CODEC, FastPlaceNetwork::handleMiddleConfirmSetting);
      registrar.playToServer(FaceRasterizationSettingPayload.TYPE, FaceRasterizationSettingPayload.STREAM_CODEC, FastPlaceNetwork::handleFaceRasterizationSetting);
      registrar.playToServer(SettingsActionPayload.TYPE, SettingsActionPayload.STREAM_CODEC, FastPlaceNetwork::handleSettingsAction);
      registrar.playToServer(OperationPointPayload.TYPE, OperationPointPayload.STREAM_CODEC, FastPlaceNetwork::handleOperationPoint);
      registrar.playToServer(OperationExtendPayload.TYPE, OperationExtendPayload.STREAM_CODEC, FastPlaceNetwork::handleOperationExtend);
      registrar.playToServer(OperationSelectPointPayload.TYPE, OperationSelectPointPayload.STREAM_CODEC, FastPlaceNetwork::handleOperationSelectPoint);
      registrar.playToServer(OperationRemovePointPayload.TYPE, OperationRemovePointPayload.STREAM_CODEC, FastPlaceNetwork::handleOperationRemovePoint);
      registrar.playToServer(OperationPointDragPayload.TYPE, OperationPointDragPayload.STREAM_CODEC, FastPlaceNetwork::handleOperationPointDrag);
      registrar.playToServer(OperationInsertPointPayload.TYPE, OperationInsertPointPayload.STREAM_CODEC, FastPlaceNetwork::handleOperationInsertPoint);
      registrar.playToServer(OperationApplyPayload.TYPE, OperationApplyPayload.STREAM_CODEC, FastPlaceNetwork::handleOperationApply);
      registrar.playToServer(
         OperationWorkspaceApplyPayload.TYPE,
         OperationWorkspaceApplyPayload.STREAM_CODEC,
         FastPlaceNetwork::handleOperationWorkspaceApply
      );
      registrar.playToServer(OperationTransformPayload.TYPE, OperationTransformPayload.STREAM_CODEC, FastPlaceNetwork::handleOperationTransform);
      registrar.playToServer(ConfirmPayload.TYPE, ConfirmPayload.STREAM_CODEC, FastPlaceNetwork::handleGeometryConfirm);
      registrar.playToServer(StartPlacementPayload.TYPE, StartPlacementPayload.STREAM_CODEC, FastPlaceNetwork::handleStartPlacement);
      registrar.playToServer(QuickShapePayload.TYPE, QuickShapePayload.STREAM_CODEC, FastPlaceNetwork::handleQuickShape);
      registrar.playToServer(QuickReplacePayload.TYPE, QuickReplacePayload.STREAM_CODEC, FastPlaceNetwork::handleQuickReplace);
      registrar.playToServer(GeometryRemovePointPayload.TYPE, GeometryRemovePointPayload.STREAM_CODEC, FastPlaceNetwork::handleGeometryRemovePoint);
      registrar.playToServer(GeometrySelectModePayload.TYPE, GeometrySelectModePayload.STREAM_CODEC, FastPlaceNetwork::handleGeometrySelectMode);
      registrar.playToServer(GeometryGizmoDragPayload.TYPE, GeometryGizmoDragPayload.STREAM_CODEC, FastPlaceNetwork::handleGeometryGizmoDrag);
      registrar.playToServer(GeometryInteractionPayload.TYPE, GeometryInteractionPayload.STREAM_CODEC, FastPlaceNetwork::handleGeometryInteraction);
      registrar.playToServer(GeometryPointPayload.TYPE, GeometryPointPayload.STREAM_CODEC, FastPlaceNetwork::handleGeometryPoint);
      registrar.playToServer(ClosePathPayload.TYPE, ClosePathPayload.STREAM_CODEC, FastPlaceNetwork::handleClosePath);
      registrar.playToServer(CycleStageModePayload.TYPE, CycleStageModePayload.STREAM_CODEC, FastPlaceNetwork::handleCycleStageMode);
      registrar.playToServer(ScrollCandidatePayload.TYPE, ScrollCandidatePayload.STREAM_CODEC, FastPlaceNetwork::handleScrollCandidate);
      registrar.playToServer(QuitFastPlacePayload.TYPE, QuitFastPlacePayload.STREAM_CODEC, FastPlaceNetwork::handleQuit);
      registrar.playToServer(UndoFastPlacePayload.TYPE, UndoFastPlacePayload.STREAM_CODEC, FastPlaceNetwork::handleUndo);
      registrar.playToServer(WorldUndoPayload.TYPE, WorldUndoPayload.STREAM_CODEC, FastPlaceNetwork::handleWorldUndo);
      registrar.playToServer(WorldRedoPayload.TYPE, WorldRedoPayload.STREAM_CODEC, FastPlaceNetwork::handleWorldRedo);
      registrar.playToClient(BuildingPreviewPayload.TYPE, BuildingPreviewPayload.STREAM_CODEC, FastPlaceNetwork::handleBuildingPreview);
      registrar.playToClient(OperationPreviewPayload.TYPE, OperationPreviewPayload.STREAM_CODEC, FastPlaceNetwork::handleOperationPreview);
      registrar.playToClient(
         OperationWorkspaceResultPayload.TYPE,
         OperationWorkspaceResultPayload.STREAM_CODEC,
         FastPlaceNetwork::handleOperationWorkspaceResult
      );
      registrar.playToClient(GeometryPreviewPayload.TYPE, GeometryPreviewPayload.STREAM_CODEC, FastPlaceNetwork::handleGeometryPreview);
      registrar.playToClient(ActivityStatePayload.TYPE, ActivityStatePayload.STREAM_CODEC, FastPlaceNetwork::handleActivityState);
      registrar.playToClient(OpenSettingsPayload.TYPE, OpenSettingsPayload.STREAM_CODEC, FastPlaceNetwork::handleOpenSettings);
   }

   private static void handleCycleStageMode(CycleStageModePayload payload, IPayloadContext context) {
      context.enqueueWork(() -> {
         if (context.player() instanceof ServerPlayer player) {
            ServerInputDispatcher.shortModifier(player, payload.hasCandidate() ? payload.candidate() : null, payload.hasCandidate());
         }
      });
   }

   private static void handleModifierState(ModifierStatePayload payload, IPayloadContext context) {
      context.enqueueWork(() -> {
         if (context.player() instanceof ServerPlayer player) {
            ServerInputDispatcher.setModifierHeld(player, payload.down());
         }
      });
   }

   private static void handleMiddleConfirmSetting(MiddleConfirmSettingPayload payload, IPayloadContext context) {
      context.enqueueWork(() -> {
         if (context.player() instanceof ServerPlayer player) {
            FastPlaceSettings settings = FastPlaceSettings.load(player);
            settings.setMiddleConfirmEnabled(player, payload.enabled());
            FastPlaceManager.syncCurrentPreview(player);
         }
      });
   }

   private static void handleFaceRasterizationSetting(
      FaceRasterizationSettingPayload payload,
      IPayloadContext context
   ) {
      context.enqueueWork(() -> {
         if (context.player() instanceof ServerPlayer player) {
            FastPlaceSettings settings = FastPlaceSettings.load(player);
            settings.setFaceRasterizationMode(player, payload.mode());
            FastPlaceManager.syncCurrentPreview(player);
         }
      });
   }

   private static void handleSettingsAction(SettingsActionPayload payload, IPayloadContext context) {
      context.enqueueWork(() -> {
         if (context.player() instanceof ServerPlayer player) {
            FastPlaceSettings settings = FastPlaceSettings.load(player);
            switch (payload.action()) {
               case CYCLE_RAYCAST_PLACEMENT -> settings.cycleRaycastPlacement(player);
               case CYCLE_PLACEMENT_CONFLICT -> settings.setPlacementConflictMode(
                  player, next(settings.placementConflictMode(), OperationConflictMode.values())
               );
               case CYCLE_PLACEMENT_UPDATE -> settings.setPlacementUpdateMode(
                  player, next(settings.placementUpdateMode(), PlacementUpdateMode.values())
               );
               case TOGGLE_SMART_WOOD_FRAME -> settings.toggleSmartWoodFrame(player);
               case TOGGLE_EMPTY_HAND_WRENCH -> settings.toggleEmptyHandWrench(player);
               case TOGGLE_GLOBAL_FREEZE -> {
                  var manager = player.getServer().tickRateManager();
                  manager.setFrozen(!manager.isFrozen());
               }
               case DECREASE_WORLD_HISTORY -> settings.setWorldUndoHistoryLimit(player, settings.worldUndoHistoryLimit() - 10);
               case INCREASE_WORLD_HISTORY -> settings.setWorldUndoHistoryLimit(player, settings.worldUndoHistoryLimit() + 10);
               case DECREASE_SESSION_HISTORY -> settings.setSessionUndoHistoryLimit(player, settings.sessionUndoHistoryLimit() - 10);
               case INCREASE_SESSION_HISTORY -> settings.setSessionUndoHistoryLimit(player, settings.sessionUndoHistoryLimit() + 10);
            }
            FastPlaceManager.syncCurrentPreview(player);
         }
      });
   }

   private static <E extends Enum<E>> E next(E current, E[] values) {
      return values[(current.ordinal() + 1) % values.length];
   }

   private static void handleOperationPoint(OperationPointPayload payload, IPayloadContext context) {
      context.enqueueWork(() -> {
         if (context.player() instanceof ServerPlayer player) {
            ServerInputDispatcher.operationPoint(player, payload.role());
         }
      });
   }

   private static void handleQuickReplace(QuickReplacePayload payload, IPayloadContext context) {
      context.enqueueWork(() -> {
         if (context.player() instanceof ServerPlayer player) {
            QuickReplaceManager.replaceCrosshair(player);
         }
      });
   }

   private static void handleOperationRemovePoint(OperationRemovePointPayload payload, IPayloadContext context) {
      context.enqueueWork(() -> {
         if (context.player() instanceof ServerPlayer player) {
            ServerInputDispatcher.operationRemovePoint(player, payload.index());
         }
      });
   }

   private static void handleOperationExtend(OperationExtendPayload payload, IPayloadContext context) {
      context.enqueueWork(() -> {
         if (context.player() instanceof ServerPlayer player) {
            if (OperationExtendPayload.validAxis(payload.axis())) {
               ServerInputDispatcher.extend(player, payload.axis(), payload.positive(), payload.steps(), payload.finish());
            }
         }
      });
   }

   private static void handleOperationSelectPoint(OperationSelectPointPayload payload, IPayloadContext context) {
      context.enqueueWork(() -> {
         if (context.player() instanceof ServerPlayer player) {
            ServerInputDispatcher.operationSelectPoint(player, payload.index());
         }
      });
   }

   private static void handleOperationPointDrag(OperationPointDragPayload payload, IPayloadContext context) {
      context.enqueueWork(() -> {
         if (context.player() instanceof ServerPlayer player) {
            ServerInputDispatcher.operationPointDrag(
               player, payload.pointIndex(), payload.target(), payload.constraint(), payload.finish()
            );
         }
      });
   }

   private static void handleOperationInsertPoint(OperationInsertPointPayload payload, IPayloadContext context) {
      context.enqueueWork(() -> {
         if (context.player() instanceof ServerPlayer player) {
            ServerInputDispatcher.operationInsertPoint(player);
         }
      });
   }

   private static void handleOperationApply(OperationApplyPayload payload, IPayloadContext context) {
      context.enqueueWork(() -> {
         if (context.player() instanceof ServerPlayer player) {
            ServerInputDispatcher.applyOperation(player, payload.copy());
         }
      });
   }

   private static void handleOperationWorkspaceApply(
      OperationWorkspaceApplyPayload payload, IPayloadContext context
   ) {
      context.enqueueWork(() -> {
         if (!(context.player() instanceof ServerPlayer player)) {
            return;
         }
         try {
            byte[] completed = acceptWorkspaceChunk(player.getUUID(), payload);
            if (completed == null) {
               return;
            }
            var blocks = player.registryAccess().lookupOrThrow(Registries.BLOCK);
            if (!ServerInputDispatcher.applyWorkspace(
               player, payload.transferId(), OperationWorkspacePlanCodec.decodeCompressed(completed, blocks)
            )) {
               sendWorkspaceResult(player, payload.transferId(), false, java.util.List.of());
            }
         } catch (IOException | RuntimeException exception) {
            WORKSPACE_TRANSFERS.remove(player.getUUID());
            sendWorkspaceResult(player, payload.transferId(), false, java.util.List.of());
         }
      });
   }

   private static byte[] acceptWorkspaceChunk(UUID owner, OperationWorkspaceApplyPayload payload) throws IOException {
      IncomingWorkspaceTransfer transfer = WORKSPACE_TRANSFERS.get(owner);
      if (transfer != null && transfer.expired(System.nanoTime())) {
         WORKSPACE_TRANSFERS.remove(owner, transfer);
         transfer = null;
      }
      if (transfer == null || !transfer.transferId.equals(payload.transferId())) {
         if (payload.chunkIndex() != 0) {
            throw new IOException("Workspace transfer must start with chunk zero");
         }
         transfer = new IncomingWorkspaceTransfer(payload.transferId(), payload.chunkCount());
         WORKSPACE_TRANSFERS.put(owner, transfer);
      }
      if (transfer.chunkCount != payload.chunkCount()) {
         throw new IOException("Workspace transfer metadata changed");
      }
      byte[] completed = transfer.accept(payload.chunkIndex(), payload.data());
      if (completed != null) {
         WORKSPACE_TRANSFERS.remove(owner, transfer);
      }
      return completed;
   }

   private static void handleOperationTransform(OperationTransformPayload payload, IPayloadContext context) {
      context.enqueueWork(() -> {
         if (context.player() instanceof ServerPlayer player && payload.valid()) {
            ServerInputDispatcher.operationTransform(
               player, payload.operation(), payload.axis(), payload.direction(), payload.totalSteps(), payload.finish()
            );
         }
      });
   }

   private static void handleGeometryConfirm(ConfirmPayload payload, IPayloadContext context) {
      context.enqueueWork(() -> {
         if (context.player() instanceof ServerPlayer player) {
            ServerInputDispatcher.confirm(player);
         }
      });
   }

   private static void handleQuickShape(QuickShapePayload payload, IPayloadContext context) {
      context.enqueueWork(() -> {
         if (context.player() instanceof ServerPlayer player) {
            ServerInputDispatcher.quickShape(player);
         }
      });
   }

   private static void handleStartPlacement(StartPlacementPayload payload, IPayloadContext context) {
      context.enqueueWork(() -> {
         if (context.player() instanceof ServerPlayer player) {
            ServerInputDispatcher.startPlacement(player, payload.embedded());
         }
      });
   }

   private static void handleGeometryRemovePoint(GeometryRemovePointPayload payload, IPayloadContext context) {
      context.enqueueWork(() -> {
         if (context.player() instanceof ServerPlayer player) {
            ServerInputDispatcher.legacyGeometryRemovePoint(player, payload.point());
         }
      });
   }

   private static void handleGeometrySelectMode(GeometrySelectModePayload payload, IPayloadContext context) {
      context.enqueueWork(() -> {
         if (context.player() instanceof ServerPlayer player) {
            ServerInputDispatcher.selectGeometryMode(player, payload.mode());
         }
      });
   }

   private static void handleGeometryGizmoDrag(GeometryGizmoDragPayload payload, IPayloadContext context) {
      context.enqueueWork(() -> {
         if (context.player() instanceof ServerPlayer player) {
            ServerInputDispatcher.geometryGizmoDrag(player, payload.operation(), payload.axis(), payload.steps(), payload.finish());
         }
      });
   }

   private static void handleGeometryInteraction(GeometryInteractionPayload payload, IPayloadContext context) {
      context.enqueueWork(() -> {
         if (context.player() instanceof ServerPlayer player) {
            ServerInputDispatcher.geometryInteraction(player, payload.targetType(), payload.index(), payload.action(), payload.gesture());
         }
      });
   }

   private static void handleGeometryPoint(GeometryPointPayload payload, IPayloadContext context) {
      context.enqueueWork(() -> {
         if (context.player() instanceof ServerPlayer player) {
            ServerInputDispatcher.geometryPoint(player);
         }
      });
   }

   private static void handleClosePath(ClosePathPayload payload, IPayloadContext context) {
      context.enqueueWork(() -> {
         if (context.player() instanceof ServerPlayer player) {
            ServerInputDispatcher.closeActivePath(player);
         }
      });
   }

   private static void handleBuildingPreview(BuildingPreviewPayload payload, IPayloadContext context) {
      context.enqueueWork(() -> applyClientBuildingPreview(payload));
   }

   private static void handleOperationPreview(OperationPreviewPayload payload, IPayloadContext context) {
      context.enqueueWork(() -> applyClientOperationPreview(payload));
   }

   private static void handleGeometryPreview(GeometryPreviewPayload payload, IPayloadContext context) {
      context.enqueueWork(() -> applyClientGeometryPreview(payload));
   }

   private static void handleOperationWorkspaceResult(
      OperationWorkspaceResultPayload payload, IPayloadContext context
   ) {
      context.enqueueWork(() -> {
         try {
            Class<?> handler = Class.forName("io.github.fastformer.client.operation.ClientOperationController");
            handler.getMethod("applyWorkspaceResult", OperationWorkspaceResultPayload.class).invoke(null, payload);
         } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unable to apply workspace result", exception);
         }
      });
   }

   private static void handleActivityState(ActivityStatePayload payload, IPayloadContext context) {
      context.enqueueWork(() -> applyClientActivityState(payload));
   }

   private static void handleOpenSettings(OpenSettingsPayload payload, IPayloadContext context) {
      context.enqueueWork(() -> applyClientOpenSettings(payload));
   }

   private static void handleScrollCandidate(ScrollCandidatePayload payload, IPayloadContext context) {
      context.enqueueWork(() -> {
         if (context.player() instanceof ServerPlayer player) {
            ServerInputDispatcher.scroll(player, payload.steps());
         }
      });
   }

   private static void handleQuit(QuitFastPlacePayload payload, IPayloadContext context) {
      context.enqueueWork(() -> {
         if (context.player() instanceof ServerPlayer player) {
            ServerInputDispatcher.quit(player);
         }
      });
   }

   private static void handleUndo(UndoFastPlacePayload payload, IPayloadContext context) {
      context.enqueueWork(() -> {
         if (context.player() instanceof ServerPlayer player) {
            ServerInputDispatcher.undo(player);
         }
      });
   }

   private static void handleWorldUndo(WorldUndoPayload payload, IPayloadContext context) {
      context.enqueueWork(() -> {
         if (context.player() instanceof ServerPlayer player) {
            ServerInputDispatcher.worldUndo(player);
         }
      });
   }

   private static void handleWorldRedo(WorldRedoPayload payload, IPayloadContext context) {
      context.enqueueWork(() -> {
         if (context.player() instanceof ServerPlayer player) {
            ServerInputDispatcher.redo(player);
         }
      });
   }

   public static void syncPreview(ServerPlayer player, FastPlaceSession session) {
      FastPlaceSettings settings = FastPlaceSettings.load(player);
      sendPreview(
         player,
          BuildingPreviewPayload.active(
             session.points(),
             session.faceBaseOffset(),
             session.volumeBaseOffset(),
             session.perpendicularAnchor(),
             FastPlaceManager.effectiveModes(settings, session).raycastPlacement(),
             session.modifierHeld(),
             session.polygonClosed(),
             session.polygonHeightConfirmed(),
             session.polygonVolumeShape(),
             session.freeScrollOffset(),
             session.faceTieBias(),
             session.placementContext(),
             settings
          )
      );
      syncActivity(player);
   }

   public static void syncOperation(ServerPlayer player, OperationSession session) {
      sendOperationPreview(
         player,
         OperationPreviewPayload.active(
            nextOperationPreviewRevision(player),
            session.hasFirst(),
            session.hasSecond(),
            session.points(),
            session.minOffset(),
            session.maxOffset(),
            session.selectionMode(),
            session.prismBasePointCount(),
            session.selectedPointIndex(),
            session.hullInflation(),
            session.mode(),
            session.stageMode(),
            session.translation(),
            session.stackRegion().min(),
            session.stackRegion().max(),
            session.rotation(),
            session.adjustmentStarted(),
            FastPlaceManager.modifierHeld(player),
            FastPlaceManager.modifierHeld(player)
         )
      );
      syncActivity(player);
   }

   public static void clearPreview(ServerPlayer player) {
      syncSettings(player);
   }

   public static void syncSettings(ServerPlayer player) {
      sendPreview(player, BuildingPreviewPayload.inactive(FastPlaceSettings.load(player)));
      sendOperationPreview(player, OperationPreviewPayload.inactive(nextOperationPreviewRevision(player)));
      sendGeometry(player, GeometryPreviewPayload.inactive());
      syncActivity(player);
   }

   public static void syncGeometry(ServerPlayer player, GeometrySession session) {
      sendGeometry(
         player,
         new GeometryPreviewPayload(
            true,
            session.mode(),
            session.points(),
            session.pointLocations(),
            session.pointRoles(),
            session.closed(),
            FastPlaceManager.modifierHeld(player),
            session.extrusion(),
            session.polyhedronShapeVariant(),
            session.coneShapeVariant(),
            session.compoundShapeVariant(),
            session.polyhedronSizeMode(),
            FastPlaceSettings.load(player).fillMode(),
            session.conePlaneMode(),
            session.coneRadius(),
            session.coneScaleX(),
            session.coneScaleZ(),
            session.coneTopScaleOffset(),
            session.coneTopOffset(),
            session.coneRotationRadians(),
            session.coneGizmoLocal(),
            session.rotation(),
            session.polyhedronLocalScale(),
            session.polyhedronWorldScale(),
            session.polyhedronGizmoLocal(),
            session.selectedControlPoint()
         )
      );
      syncActivity(player);
   }

   public static void clearGeometry(ServerPlayer player) {
      sendGeometry(player, GeometryPreviewPayload.inactive());
      syncActivity(player);
   }

   public static void syncActivity(ServerPlayer player) {
      IncomingWorkspaceTransfer transfer = WORKSPACE_TRANSFERS.get(player.getUUID());
      if (transfer != null && transfer.expired(System.nanoTime())) {
         WORKSPACE_TRANSFERS.remove(player.getUUID(), transfer);
      }
      FastPlaceActivity activity = currentActivity(player);
      if (LAST_ACTIVITY.put(player.getUUID(), activity) == activity) {
         return;
      }
      if (player.connection.hasChannel(ActivityStatePayload.TYPE)) {
         PacketDistributor.sendToPlayer(player, new ActivityStatePayload(activity), new CustomPacketPayload[0]);
      }
   }

   public static void forgetActivity(ServerPlayer player) {
      LAST_ACTIVITY.remove(player.getUUID());
      WORKSPACE_TRANSFERS.remove(player.getUUID());
   }

   public static void clearServer() {
      LAST_ACTIVITY.clear();
      WORKSPACE_TRANSFERS.clear();
      OPERATION_PREVIEW_REVISIONS.clear();
   }

   private static long nextOperationPreviewRevision(ServerPlayer player) {
      if (player == null) {
         return 0L;
      }
      return OPERATION_PREVIEW_REVISIONS.merge(player.getUUID(), 1L, Long::sum);
   }

   private static final class IncomingWorkspaceTransfer {
      private final UUID transferId;
      private final int chunkCount;
      private final byte[][] chunks;
      private int received;
      private int bytes;
      private long updatedAt = System.nanoTime();

      private IncomingWorkspaceTransfer(UUID transferId, int chunkCount) {
         this.transferId = transferId;
         this.chunkCount = chunkCount;
         this.chunks = new byte[chunkCount][];
      }

      private byte[] accept(int index, byte[] data) throws IOException {
         if (this.chunks[index] == null) {
            if ((long)this.bytes + data.length > OperationWorkspacePlanCodec.MAX_COMPRESSED_BYTES) {
               throw new IOException("Workspace transfer exceeds compressed limit");
            }
            this.chunks[index] = data.clone();
            this.bytes += data.length;
            this.received++;
            this.updatedAt = System.nanoTime();
         }
         if (this.received != this.chunkCount) {
            return null;
         }
         ByteArrayOutputStream output = new ByteArrayOutputStream(this.bytes);
         for (byte[] chunk : this.chunks) {
            output.writeBytes(chunk);
         }
         return output.toByteArray();
      }

      private boolean expired(long now) {
         return now - this.updatedAt >= WORKSPACE_TRANSFER_TIMEOUT_NANOS;
      }
   }

   private static FastPlaceActivity currentActivity(ServerPlayer player) {
      if (FastPlaceManager.restoreActive(player) || OperationManager.restoreActive(player)) {
         return FastPlaceActivity.RESTORE_TASK;
      }
      if (OperationManager.taskActive(player)) {
         return FastPlaceActivity.OPERATION_TASK;
      }
      if (FastPlaceManager.taskActive(player)) {
         return FastPlaceActivity.PLACEMENT_TASK;
      }
      if (OperationManager.active(player)) {
         return FastPlaceActivity.OPERATION_SESSION;
      }
      if (GeometryManager.active(player)) {
         return FastPlaceActivity.GEOMETRY_SESSION;
      }
      if (FastPlaceManager.active(player)) {
         return FastPlaceActivity.BUILDING_SESSION;
      }
      return FastPlaceActivity.NONE;
   }

   private static void sendPreview(ServerPlayer player, BuildingPreviewPayload payload) {
      if (player.connection.hasChannel(BuildingPreviewPayload.TYPE)) {
         PacketDistributor.sendToPlayer(player, payload, new CustomPacketPayload[0]);
      }
   }

   private static void sendOperationPreview(ServerPlayer player, OperationPreviewPayload payload) {
      if (player.connection.hasChannel(OperationPreviewPayload.TYPE)) {
         PacketDistributor.sendToPlayer(player, payload, new CustomPacketPayload[0]);
      }
   }

   private static void sendGeometry(ServerPlayer player, GeometryPreviewPayload payload) {
      if (player.connection.hasChannel(GeometryPreviewPayload.TYPE)) {
         PacketDistributor.sendToPlayer(player, payload, new CustomPacketPayload[0]);
      }
   }

   private static void applyClientBuildingPreview(BuildingPreviewPayload payload) {
      try {
         Class<?> handler = Class.forName("io.github.fastformer.client.FastPlaceClientPreview");
         handler.getMethod("applyBuilding", BuildingPreviewPayload.class).invoke(null, payload);
      } catch (ReflectiveOperationException var2) {
         throw new IllegalStateException("Unable to apply FastFormer client preview state", var2);
      }
   }

   private static void applyClientOperationPreview(OperationPreviewPayload payload) {
      try {
         Class<?> handler = Class.forName("io.github.fastformer.client.FastPlaceClientPreview");
         handler.getMethod("applyOperation", OperationPreviewPayload.class).invoke(null, payload);
      } catch (ReflectiveOperationException var2) {
         throw new IllegalStateException("Unable to apply FastFormer operation preview state", var2);
      }
   }

   private static void applyClientGeometryPreview(GeometryPreviewPayload payload) {
      try {
         Class<?> handler = Class.forName("io.github.fastformer.client.FastPlaceClientPreview");
         handler.getMethod("applyGeometry", GeometryPreviewPayload.class).invoke(null, payload);
      } catch (ReflectiveOperationException exception) {
         throw new IllegalStateException("Unable to apply FastFormer geometry preview state", exception);
      }
   }

   public static void sendWorkspaceResult(
      ServerPlayer player, UUID transferId, boolean accepted, java.util.List<Integer> failedIds
   ) {
      if (player != null && player.connection.hasChannel(OperationWorkspaceResultPayload.TYPE)) {
         PacketDistributor.sendToPlayer(
            player, new OperationWorkspaceResultPayload(transferId, accepted, failedIds), new CustomPacketPayload[0]
         );
      }
   }

   private static void applyClientActivityState(ActivityStatePayload payload) {
      try {
         Class<?> handler = Class.forName("io.github.fastformer.client.FastPlaceClientPreview");
         handler.getMethod("applyActivity", ActivityStatePayload.class).invoke(null, payload);
      } catch (ReflectiveOperationException exception) {
         throw new IllegalStateException("Unable to apply FastFormer activity state", exception);
      }
   }

   private static void applyClientOpenSettings(OpenSettingsPayload payload) {
      try {
         Class<?> handler = Class.forName("io.github.fastformer.client.FastFormerSettingsScreen");
         handler.getMethod(
            "open", boolean.class, io.github.fastformer.fastplace.FaceRasterizationMode.class,
            io.github.fastformer.fastplace.RaycastPlacement.class,
            io.github.fastformer.fastplace.OperationConflictMode.class,
            io.github.fastformer.fastplace.PlacementUpdateMode.class,
            boolean.class, boolean.class, boolean.class, int.class, int.class
         ).invoke(
            null, payload.middleConfirmEnabled(), payload.faceRasterizationMode(), payload.raycastPlacement(),
            payload.placementConflictMode(), payload.placementUpdateMode(), payload.smartWoodFrame(),
            payload.emptyHandWrench(), payload.globalFrozen(),
            payload.worldUndoHistoryLimit(), payload.sessionUndoHistoryLimit()
         );
      } catch (ReflectiveOperationException exception) {
         throw new IllegalStateException("Unable to open FastFormer settings", exception);
      }
   }
}
