package io.github.fastformer.network;

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
   private static final IncomingPayloadTransfers INCOMING_TRANSFERS = new IncomingPayloadTransfers();
   private FastPlaceNetwork() {
   }

   public static void register(IEventBus modBus) {
      modBus.addListener(FastPlaceNetwork::registerPayloads);
   }

   private static void registerPayloads(RegisterPayloadHandlersEvent event) {
      PayloadRegistrar registrar = event.registrar("58").optional();
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
            byte[] completed = INCOMING_TRANSFERS.acceptWorkspace(player.getUUID(), payload);
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
            INCOMING_TRANSFERS.forgetWorkspace(player.getUUID());
            sendWorkspaceResult(player, payload.transferId(), false, java.util.List.of());
         }
      });
   }

   private static void handleShapePlacement(ShapePlacementPayload payload, IPayloadContext context) {
      context.enqueueWork(() -> {
         if (!(context.player() instanceof ServerPlayer player)) {
            return;
         }
         try {
            byte[] completed = INCOMING_TRANSFERS.acceptShape(player.getUUID(), payload);
            if (completed == null) {
               return;
            }
            var blocks = player.registryAccess().lookupOrThrow(Registries.BLOCK);
            var plan = OperationWorkspacePlanCodec.decodeCompressed(completed, blocks);
            if (OperationManager.applyWorkspace(player, payload.transferId(), plan)) {
               FastPlaceManager.cancel(player);
            } else {
               sendWorkspaceResult(player, payload.transferId(), false, java.util.List.of());
            }
         } catch (IOException | RuntimeException exception) {
            INCOMING_TRANSFERS.forgetShape(player.getUUID());
            sendWorkspaceResult(player, payload.transferId(), false, java.util.List.of());
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
         ServerInputDispatcher.placementAction(player, payload);
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

   private static void handleBuildingPreviewSession(
      BuildingPreviewSessionPayload payload, IPayloadContext context
   ) {
      context.enqueueWork(() -> ClientPayloadDispatcher.applyBuildingSession(payload));
   }

   private static void handleBuildingPreviewParameters(
      BuildingPreviewParametersPayload payload, IPayloadContext context
   ) {
      context.enqueueWork(() -> ClientPayloadDispatcher.applyBuildingParameters(payload));
   }

   private static void handleBuildingPreviewEffect(
      BuildingPreviewEffectPayload payload, IPayloadContext context
   ) {
      context.enqueueWork(() -> ClientPayloadDispatcher.applyBuildingEffect(payload));
   }

   private static void handleOperationPreview(OperationPreviewPayload payload, IPayloadContext context) {
      context.enqueueWork(() -> ClientPayloadDispatcher.applyOperationPreview(payload));
   }

   private static void handleGeometryPreview(GeometryPreviewPayload payload, IPayloadContext context) {
      context.enqueueWork(() -> ClientPayloadDispatcher.applyGeometryPreview(payload));
   }

   private static void handleOperationWorkspaceResult(
      OperationWorkspaceResultPayload payload, IPayloadContext context
   ) {
      context.enqueueWork(() -> ClientPayloadDispatcher.applyWorkspaceResult(payload));
   }

   private static void handleActivityState(ActivityStatePayload payload, IPayloadContext context) {
      context.enqueueWork(() -> ClientPayloadDispatcher.applyActivity(payload));
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
   }

   public static void clearServer() {
      PlayerPreviewSync.clearServer();
      INCOMING_TRANSFERS.clear();
   }

   /** Cleans incomplete client payloads without waiting for another packet. */
   public static void tick() {
      INCOMING_TRANSFERS.purgeExpired();
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

}
