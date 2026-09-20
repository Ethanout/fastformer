package io.github.fastformer.network;

import com.mojang.logging.LogUtils;
import io.github.fastformer.fastplace.session.*;
import io.github.fastformer.fastplace.FastPlaceManager;
import io.github.fastformer.fastplace.session.FastPlaceSession;
import io.github.fastformer.fastplace.FastPlaceSettings;
import io.github.fastformer.fastplace.OperationConflictMode;
import io.github.fastformer.fastplace.PlacementUpdateMode;
import io.github.fastformer.fastplace.session.OperationSession;
import io.github.fastformer.fastplace.OperationManager;
import io.github.fastformer.fastplace.session.GeometrySession;
import io.github.fastformer.fastplace.ServerInputDispatcher;
import io.github.fastformer.fastplace.OperationWorkspacePlanCodec;
import io.github.fastformer.fastplace.QuickReplaceManager;
import io.github.fastformer.network.payload.geometry.*;
import io.github.fastformer.network.payload.operation.*;
import io.github.fastformer.network.payload.placement.*;
import io.github.fastformer.network.payload.preview.*;
import io.github.fastformer.network.payload.settings.*;
import io.github.fastformer.network.payload.world.*;
import io.github.fastformer.network.client.ClientPayloadDispatcher;
import io.github.fastformer.network.sync.PlayerPreviewSync;
import io.github.fastformer.network.transfer.IncomingPayloadTransfers;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import java.util.UUID;
import java.io.IOException;

public final class FastPlaceNetwork {
   private static final org.slf4j.Logger LOGGER = LogUtils.getLogger();
   private static final IncomingPayloadTransfers INCOMING_TRANSFERS = new IncomingPayloadTransfers();
   private static final java.util.Map<WorkspaceCallbackKey, OperationCallbackScope> WORKSPACE_CALLBACK_SCOPES =
      new java.util.concurrent.ConcurrentHashMap<>();

   private record WorkspaceCallbackKey(UUID playerId, UUID transferId) {}
   private FastPlaceNetwork() {
   }

   public static void register(IEventBus modBus) {
      modBus.addListener(FastPlaceNetwork::registerPayloads);
   }

   private static void registerPayloads(RegisterPayloadHandlersEvent event) {
      PayloadRegistrar registrar = event.registrar("61").optional();
      registrar.playToClient(PlacementActionAckPayload.TYPE, PlacementActionAckPayload.STREAM_CODEC,
         (payload, context) -> {
            var connection = context.connection();
            context.enqueueWork(() -> ClientPayloadDispatcher.acknowledgePlacement(payload, connection));
         });
      registrar.playToServer(ModifierStatePayload.TYPE, ModifierStatePayload.STREAM_CODEC, FastPlaceNetwork::handleModifierState);
      registrar.playToServer(MiddleConfirmSettingPayload.TYPE, MiddleConfirmSettingPayload.STREAM_CODEC, FastPlaceNetwork::handleMiddleConfirmSetting);
      registrar.playToServer(FaceRasterizationSettingPayload.TYPE, FaceRasterizationSettingPayload.STREAM_CODEC, FastPlaceNetwork::handleFaceRasterizationSetting);
      registrar.playToServer(SettingsActionPayload.TYPE, SettingsActionPayload.STREAM_CODEC, FastPlaceNetwork::handleSettingsAction);
      registrar.playToServer(
         PlacementEffectSettingPayload.TYPE,
         PlacementEffectSettingPayload.STREAM_CODEC,
         FastPlaceNetwork::handlePlacementEffectSetting
      );
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
      registrar.playToServer(
         ShapePlacementPayload.TYPE,
         ShapePlacementPayload.STREAM_CODEC,
         FastPlaceNetwork::handleShapePlacement
      );
      registrar.playToServer(OperationTransformPayload.TYPE, OperationTransformPayload.STREAM_CODEC, FastPlaceNetwork::handleOperationTransform);
      registrar.playToServer(StartPlacementPayload.TYPE, StartPlacementPayload.STREAM_CODEC, FastPlaceNetwork::handleStartPlacement);
      registrar.playToServer(
         PlacementActionPayload.TYPE,
         PlacementActionPayload.STREAM_CODEC,
         FastPlaceNetwork::handlePlacementAction
      );
      registrar.playToServer(QuickReplacePayload.TYPE, QuickReplacePayload.STREAM_CODEC, FastPlaceNetwork::handleQuickReplace);
      registrar.playToServer(QuickShapeConfirmPayload.TYPE, QuickShapeConfirmPayload.STREAM_CODEC,
         FastPlaceNetwork::handleQuickShapeConfirm);
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
      registrar.playToClient(
         BuildingPreviewSessionPayload.TYPE,
         BuildingPreviewSessionPayload.STREAM_CODEC,
         FastPlaceNetwork::handleBuildingPreviewSession
      );
      registrar.playToClient(
         BuildingPreviewParametersPayload.TYPE,
         BuildingPreviewParametersPayload.STREAM_CODEC,
         FastPlaceNetwork::handleBuildingPreviewParameters
      );
      registrar.playToClient(
         BuildingPreviewEffectPayload.TYPE,
         BuildingPreviewEffectPayload.STREAM_CODEC,
         FastPlaceNetwork::handleBuildingPreviewEffect
      );
      registrar.playToClient(OperationPreviewPayload.TYPE, OperationPreviewPayload.STREAM_CODEC, FastPlaceNetwork::handleOperationPreview);
      registrar.playToClient(
         OperationWorkspaceResultPayload.TYPE,
         OperationWorkspaceResultPayload.STREAM_CODEC,
         FastPlaceNetwork::handleOperationWorkspaceResult
      );
      registrar.playToServer(
         OperationWorkspaceReceiptQueryPayload.TYPE,
         OperationWorkspaceReceiptQueryPayload.STREAM_CODEC,
         FastPlaceNetwork::handleOperationWorkspaceReceiptQuery
      );
      registrar.playToClient(
         OperationWorkspaceReceiptPayload.TYPE,
         OperationWorkspaceReceiptPayload.STREAM_CODEC,
         FastPlaceNetwork::handleOperationWorkspaceReceipt
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
               case CYCLE_PLACEMENT_CONFLICT -> settings.setPlacementConflictMode(
                  player, next(settings.placementConflictMode(), OperationConflictMode.values())
               );
               case CYCLE_PLACEMENT_UPDATE -> settings.setPlacementUpdateMode(
                  player, next(settings.placementUpdateMode(), PlacementUpdateMode.values())
               );
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

   private static void handlePlacementEffectSetting(
      PlacementEffectSettingPayload payload, IPayloadContext context
   ) {
      context.enqueueWork(() -> {
         if (!(context.player() instanceof ServerPlayer player)
            || !io.github.fastformer.fastplace.placement.effect.PlacementEffectRegistry.contains(payload.effectId())) {
            return;
         }
         FastPlaceSettings.load(player).setPlacementEffectEnabled(player, payload.effectId(), payload.enabled());
         FastPlaceManager.syncCurrentPreview(player);
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
            try {
               ServerInputDispatcher.applyOperation(player, payload.copy(), payload.requestId());
            } finally {
               acknowledgePlacement(player, payload.requestId());
            }
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
            rememberWorkspaceCallbackScope(player, payload.transferId(), payload.chunkIndex());
            byte[] completed = INCOMING_TRANSFERS.acceptWorkspace(player.getUUID(), payload);
            if (completed == null) {
               return;
            }
            var blocks = player.registryAccess().lookupOrThrow(Registries.BLOCK);
            io.github.fastformer.fastplace.WorkspaceAdmission admission = ServerInputDispatcher.applyWorkspace(
               player, payload.transferId(), OperationWorkspacePlanCodec.decodeCompressed(completed, blocks)
            );
            // One sender, one packet. A queued task reports its own result, a refusal
            // reports a retryable failure, and a replay reports the state that the ledger
            // holds. A replay of running work reports nothing.
            sendAdmissionResult(player, payload.transferId(), admission);
         } catch (IOException | RuntimeException exception) {
            INCOMING_TRANSFERS.forgetWorkspace(player.getUUID(), payload.transferId());
            // A transfer that already reached the ledger is not a failure. The running task
            // owns its result, so the report is skipped for that case.
            reportFailedAdmission(player, payload.transferId());
         }
      });
   }

   private static void handleShapePlacement(ShapePlacementPayload payload, IPayloadContext context) {
      context.enqueueWork(() -> {
         if (!(context.player() instanceof ServerPlayer player)) {
            return;
         }
         try {
            rememberWorkspaceCallbackScope(player, payload.transferId(), payload.chunkIndex());
            byte[] completed = INCOMING_TRANSFERS.acceptShape(player.getUUID(), payload);
            if (completed == null) {
               return;
            }
            var blocks = player.registryAccess().lookupOrThrow(Registries.BLOCK);
            var plan = OperationWorkspacePlanCodec.decodeCompressed(completed, blocks);
            io.github.fastformer.fastplace.WorkspaceAdmission admission =
               OperationManager.applyWorkspace(player, payload.transferId(), plan);
            if (admission.isQueued()) {
               FastPlaceManager.cancel(player);
            } else {
               sendAdmissionResult(player, payload.transferId(), admission);
            }
         } catch (IOException | RuntimeException exception) {
            INCOMING_TRANSFERS.forgetShape(player.getUUID(), payload.transferId());
            reportFailedAdmission(player, payload.transferId());
         }
      });
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

   private static void handlePlacementAction(PlacementActionPayload payload, IPayloadContext context) {
      context.enqueueWork(() -> {
         if (!(context.player() instanceof ServerPlayer player)) {
            return;
         }
         try {
            ServerInputDispatcher.placementAction(player, payload);
         } finally {
            acknowledgePlacement(player, payload.requestId());
         }
      });
   }

   private static void handleStartPlacement(StartPlacementPayload payload, IPayloadContext context) {
      context.enqueueWork(() -> {
         if (context.player() instanceof ServerPlayer player) {
            ServerInputDispatcher.startPlacement(player, payload.placement());
         }
      });
   }

   private static void handleQuickShapeConfirm(QuickShapeConfirmPayload payload, IPayloadContext context) {
      context.enqueueWork(() -> {
         if (!(context.player() instanceof ServerPlayer player)) return;
         try {
            ServerInputDispatcher.confirmQuickShape(player, payload);
         } finally {
            acknowledgePlacement(player, payload.requestId());
         }
      });
   }

   private static void acknowledgePlacement(ServerPlayer player, long requestId) {
      FastPlaceManager.syncCurrentPreview(player);
      syncActivity(player);
      PacketDistributor.sendToPlayer(player, new PlacementActionAckPayload(requestId, PlayerPreviewSync.callbackScope(player)));
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

   private static void handleBuildingPreviewSession(
      BuildingPreviewSessionPayload payload, IPayloadContext context
   ) {
      var connection = context.connection();
      context.enqueueWork(() -> ClientPayloadDispatcher.applyBuildingSession(payload, connection));
   }

   private static void handleBuildingPreviewParameters(
      BuildingPreviewParametersPayload payload, IPayloadContext context
   ) {
      var connection = context.connection();
      context.enqueueWork(() -> ClientPayloadDispatcher.applyBuildingParameters(payload, connection));
   }

   private static void handleBuildingPreviewEffect(
      BuildingPreviewEffectPayload payload, IPayloadContext context
   ) {
      var connection = context.connection();
      context.enqueueWork(() -> ClientPayloadDispatcher.applyBuildingEffect(payload, connection));
   }

   private static void handleOperationPreview(OperationPreviewPayload payload, IPayloadContext context) {
      var connection = context.connection();
      context.enqueueWork(() -> ClientPayloadDispatcher.applyOperationPreview(payload, connection));
   }

   private static void handleGeometryPreview(GeometryPreviewPayload payload, IPayloadContext context) {
      var connection = context.connection();
      context.enqueueWork(() -> ClientPayloadDispatcher.applyGeometryPreview(payload, connection));
   }

   private static void handleOperationWorkspaceResult(
      OperationWorkspaceResultPayload payload, IPayloadContext context
   ) {
      var connection = context.connection();
      context.enqueueWork(() -> ClientPayloadDispatcher.applyWorkspaceResult(payload, connection));
   }

   private static void handleOperationWorkspaceReceipt(
      OperationWorkspaceReceiptPayload payload, IPayloadContext context
   ) {
      var connection = context.connection();
      context.enqueueWork(() -> ClientPayloadDispatcher.applyWorkspaceReceipt(payload, connection));
   }

   private static void handleActivityState(ActivityStatePayload payload, IPayloadContext context) {
      var connection = context.connection();
      context.enqueueWork(() -> ClientPayloadDispatcher.applyActivity(payload, connection));
   }

   private static void handleOpenSettings(OpenSettingsPayload payload, IPayloadContext context) {
      context.enqueueWork(() -> ClientPayloadDispatcher.openSettings(payload));
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
      PlayerPreviewSync.syncPreview(player, session);
   }

   public static void syncOperation(ServerPlayer player, OperationSession session) {
      PlayerPreviewSync.syncOperation(player, session);
   }

   public static void clearPreview(ServerPlayer player) {
      PlayerPreviewSync.clearPreview(player);
   }

   public static void syncSettings(ServerPlayer player) {
      PlayerPreviewSync.syncSettings(player);
   }

   public static void syncGeometry(ServerPlayer player, GeometrySession session) {
      PlayerPreviewSync.syncGeometry(player, session);
   }

   public static void clearGeometry(ServerPlayer player) {
      PlayerPreviewSync.clearGeometry(player);
   }

   public static void syncActivity(ServerPlayer player) {
      PlayerPreviewSync.syncActivity(player);
   }

   public static void forgetActivity(ServerPlayer player) {
      PlayerPreviewSync.forgetActivity(player);
      INCOMING_TRANSFERS.forget(player.getUUID());
      WORKSPACE_CALLBACK_SCOPES.keySet().removeIf(key -> key.playerId().equals(player.getUUID()));
   }

   public static void clearServer() {
      PlayerPreviewSync.clearServer();
      INCOMING_TRANSFERS.clear();
      WORKSPACE_CALLBACK_SCOPES.clear();
   }

   /** Cleans incomplete client payloads without waiting for another packet. */
   public static void tick(net.minecraft.server.MinecraftServer server) {
      for (var expired : INCOMING_TRANSFERS.purgeExpired()) {
         ServerPlayer online = server.getPlayerList().getPlayer(expired.owner());
         if (online == null) {
            // No dimension is known for an incomplete upload, and no work was queued
            // from it. The client times out on its own and keeps its draft open.
            continue;
         }
         // This upload never reached admission, so the transfer carries no record and the
         // report is real. The guard checks that against the ledger rather than assuming it.
         reportFailedAdmission(online, expired.transferId());
      }
   }

   /**
    * Records one workspace outcome and tries to deliver it.
    *
    * <p>The server, the owner, and the dimension carry the answer when the player is
    * away. The ledger keeps it, the callback scope always leaves the map, and the player
    * learns the result after the next login through a receipt query.</p>
    *
    * @param server          server that owns the transfer, required even when nobody is online
    * @param owner           player UUID that owns the transfer
    * @param dimension       dimension that ran the transfer
    * @param recoveryCreated true when the server moved the work to its recovery path
    */
   public static void sendWorkspaceResult(
      net.minecraft.server.MinecraftServer server, UUID owner, net.minecraft.resources.ResourceLocation dimension,
      ServerPlayer player, UUID transferId, boolean accepted, boolean retryable, boolean recoveryCreated,
      java.util.List<Integer> failedIds, java.util.List<net.minecraft.core.BlockPos> failedTargets
   ) {
      if (transferId == null) {
         return;
      }
      // The record happens before the send. An offline player never receives the packet,
      // so the ledger must hold the answer first.
      io.github.fastformer.network.payload.operation.OperationSubmissionOutcome recorded =
         io.github.fastformer.fastplace.world.WorkspaceSubmissionLedger.finish(
            server, owner, dimension, transferId, accepted, retryable, recoveryCreated
         );
      // Remove the scope even when the send cannot happen. A retained entry would grow
      // for every transfer that completed while its owner was away.
      OperationCallbackScope remembered = WORKSPACE_CALLBACK_SCOPES.remove(
         new WorkspaceCallbackKey(owner, transferId)
      );
      if (player == null) {
         return;
      }
      // The packet reports the state that the ledger holds, not the state that the caller
      // wanted to record. The ledger may already hold something stronger.
      deliverWorkspaceResult(player, transferId, recorded, remembered, failedIds, failedTargets);
   }

   /**
    * Delivers the one result packet of one admission.
    *
    * <p>This is the only place that turns an admission into a packet. A queued task sends
    * its own result later, a replay reports the recorded state, and a refused admission
    * reports a retryable failure and records it, because no state exists for it yet.</p>
    */
   public static void sendAdmissionResult(
      ServerPlayer player, UUID transferId, io.github.fastformer.fastplace.WorkspaceAdmission admission
   ) {
      sendAdmissionResult(player, transferId, admission, java.util.List.of(), java.util.List.of());
   }

   public static void sendAdmissionResult(
      ServerPlayer player, UUID transferId, io.github.fastformer.fastplace.WorkspaceAdmission admission,
      java.util.List<Integer> failedIds, java.util.List<net.minecraft.core.BlockPos> failedTargets
   ) {
      if (player == null || transferId == null || admission == null) {
         return;
      }
      if (admission.keepsRequestScope()) {
         // A queued task reports its own result later, and that packet removes the scope. A
         // replay of running work shares the entry with the request that queued that task,
         // so it leaves the entry alone as well.
         return;
      }
      // This admission closes the request: it delivers a result or refuses the upload. The
      // scope of this request leaves the map here, and the packet below carries it.
      OperationCallbackScope remembered = WORKSPACE_CALLBACK_SCOPES.remove(
         new WorkspaceCallbackKey(player.getUUID(), transferId)
      );
      if (!admission.sendsResult()) {
         return;
      }
      io.github.fastformer.network.payload.operation.OperationSubmissionOutcome outcome =
         admission.deliveredOutcome();
      if (admission.kind() == io.github.fastformer.fastplace.WorkspaceAdmission.Kind.REJECTED) {
         // No state exists for a refused admission in the common case, so record the
         // retryable failure and let a later reconnect query answer it instead of UNKNOWN.
         // The ledger decides the delivered state: it may already hold something stronger
         // for this transfer, because a gate can refuse before the replay check.
         io.github.fastformer.network.payload.operation.OperationSubmissionOutcome recorded =
            io.github.fastformer.fastplace.world.WorkspaceSubmissionLedger.finish(
               player.getServer(), player.getUUID(), player.serverLevel().dimension().location(),
               transferId, false, true, false
            );
         if (recorded != null) {
            outcome = recorded;
         }
      }
      deliverWorkspaceResult(player, transferId, outcome, remembered, failedIds, failedTargets);
   }

   /**
    * Sends the result packet of one settled transfer.
    *
    * <p>A state that is not deliverable sends nothing. Running work is not a finished
    * result, and a client that reads such a packet treats it as a failure and sends the
    * same work again.</p>
    */
   private static void deliverWorkspaceResult(
      ServerPlayer player, UUID transferId,
      io.github.fastformer.network.payload.operation.OperationSubmissionOutcome outcome,
      OperationCallbackScope remembered,
      java.util.List<Integer> failedIds, java.util.List<net.minecraft.core.BlockPos> failedTargets
   ) {
      if (outcome == null || !outcome.deliverable()) {
         return;
      }
      OperationCallbackScope callbackScope = remembered == null
         ? PlayerPreviewSync.callbackScope(player) : remembered;
      try {
         if (player.connection == null || !player.connection.hasChannel(OperationWorkspaceResultPayload.TYPE)) {
            return;
         }
         PacketDistributor.sendToPlayer(
            player,
            new OperationWorkspaceResultPayload(
               transferId, outcome.applied(), outcome.retryable(), failedIds, failedTargets
            ).withCallbackScope(callbackScope),
            new CustomPacketPayload[0]
         );
      } catch (RuntimeException exception) {
         // The ledger already holds this result, and the client asks for it again after the
         // next login. A transport fault must not travel out of the admission path, where a
         // caller would read it as a failure of the admission itself.
         LOGGER.warn("Unable to deliver a FastFormer workspace result", exception);
      }
   }

   /**
    * Records and delivers one outcome for a player that is online.
    *
    * <p>The player supplies the server, the owner, and the dimension. The recovery flag
    * is false because this path serves an admission failure, which never created work.</p>
    */
   public static void sendWorkspaceResult(
      ServerPlayer player, UUID transferId, boolean accepted, boolean retryable, java.util.List<Integer> failedIds,
      java.util.List<net.minecraft.core.BlockPos> failedTargets
   ) {
      if (player == null) {
         return;
      }
      sendWorkspaceResult(
         player.getServer(), player.getUUID(), player.serverLevel().dimension().location(), player,
         transferId, accepted, retryable, false, failedIds, failedTargets
      );
   }

   /** Answers the receipt query of one client from the server ledger. */
   private static void handleOperationWorkspaceReceiptQuery(
      OperationWorkspaceReceiptQueryPayload payload, IPayloadContext context
   ) {
      context.enqueueWork(() -> {
         if (!(context.player() instanceof ServerPlayer player)) {
            return;
         }
         net.minecraft.server.MinecraftServer server = player.getServer();
         net.minecraft.resources.ResourceLocation dimension = payload.dimension();
         java.util.ArrayList<OperationWorkspaceReceiptPayload.Entry> entries =
            new java.util.ArrayList<>(payload.transferIds().size());
         for (UUID transferId : payload.transferIds()) {
            OperationWorkspaceReceiptPayload.Entry entry = new OperationWorkspaceReceiptPayload.Entry(
               transferId, answerFor(server, player.getUUID(), dimension, transferId)
            );
            entries.add(entry);
         }
         PacketDistributor.sendToPlayer(
            player,
            new OperationWorkspaceReceiptPayload(dimension, entries),
            new CustomPacketPayload[0]
         );
      });
   }

   /**
    * Resolves the answer for one transfer.
    *
    * <p>A recorded {@code IN_PROGRESS} is checked against the running task, because the
    * task is the authority on whether the work still runs. A task that already stopped
    * leaves no running work, so the result is unknown rather than pending forever.</p>
    */
   private static io.github.fastformer.network.payload.operation.OperationSubmissionOutcome answerFor(
      net.minecraft.server.MinecraftServer server, UUID owner,
      net.minecraft.resources.ResourceLocation dimension, UUID transferId
   ) {
      var recorded = io.github.fastformer.fastplace.world.WorkspaceSubmissionLedger.outcomeFor(
         server, owner, dimension, transferId
      );
      if (recorded == io.github.fastformer.network.payload.operation.OperationSubmissionOutcome.IN_PROGRESS
         && !io.github.fastformer.fastplace.OperationManager.transferActive(owner, transferId)) {
         // The task that owned this transfer is gone and no final state was recorded,
         // which happens when the server stopped before the task settled.
         return io.github.fastformer.network.payload.operation.OperationSubmissionOutcome.UNKNOWN;
      }
      return recorded;
   }

   /**
    * Remembers the request scope of one workspace upload.
    *
    * <p>The key is the owner and the transfer, so the request that queued a task and any
    * later replay of the same transfer share one entry. A replay of work that still runs
    * must not replace that entry: the running task's result packet has to carry the scope
    * of the request that queued it, not the scope of the replay.</p>
    */
   static void rememberWorkspaceCallbackScope(ServerPlayer player, UUID transferId, int chunkIndex) {
      if (chunkIndex != 0 || player == null || transferId == null) {
         return;
      }
      if (io.github.fastformer.fastplace.OperationManager.recordedOutcome(player, transferId).open()) {
         // A live task owns this transfer. Leave its scope in place.
         return;
      }
      WORKSPACE_CALLBACK_SCOPES.put(
         new WorkspaceCallbackKey(player.getUUID(), transferId), PlayerPreviewSync.callbackScope(player)
      );
   }

   /**
    * Reports a failure for an upload that never reached admission.
    *
    * <p>A decode failure, a truncated upload, or a fault before the queue is a real
    * failure. A transfer that the ledger already holds is not: the running task owns its
    * result, and a failure report here would record a contradiction and invite the player
    * to send the same work again.</p>
    */
   static void reportFailedAdmission(ServerPlayer player, UUID transferId) {
      if (player == null || transferId == null) {
         return;
      }
      if (io.github.fastformer.fastplace.OperationManager.recordedOutcome(player, transferId)
         != io.github.fastformer.network.payload.operation.OperationSubmissionOutcome.UNKNOWN) {
         // The transfer already reached the ledger. The task state is the authority.
         return;
      }
      if (io.github.fastformer.fastplace.OperationManager.transferActive(player.getUUID(), transferId)) {
         // A live task owns this transfer even though the ledger holds no record for it. A
         // fault between the queue and the record leaves exactly this state. The task
         // reports its own result when it settles, so a failure here would contradict it.
         return;
      }
      // No record exists, so nothing was queued and nothing applied. The player may send a
      // new attempt, which carries a new transfer id.
      sendWorkspaceResult(
         player, transferId, false, true, java.util.List.of(), java.util.List.of()
      );
   }

   /** True when a request scope is remembered for one transfer. Serves the delivery tests. */
   static boolean hasCallbackScopeForTest(UUID owner, UUID transferId) {
      return WORKSPACE_CALLBACK_SCOPES.containsKey(new WorkspaceCallbackKey(owner, transferId));
   }

   /** The number of remembered request scopes. Serves the delivery tests. */
   static int callbackScopeCountForTest() {
      return WORKSPACE_CALLBACK_SCOPES.size();
   }

}
