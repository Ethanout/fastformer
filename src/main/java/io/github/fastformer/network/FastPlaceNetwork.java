package io.github.fastformer.network;

import io.github.fastformer.server.intent.HistoryIntents;
import io.github.fastformer.server.intent.SessionIntents;
import io.github.fastformer.server.history.HistorySystem;
import io.github.fastformer.server.network.ServerChannels;
import io.github.fastformer.server.network.upload.UploadSystem;
import io.github.fastformer.server.network.upload.UploadKind;
import io.github.fastformer.protocol.c2s.movement.ForwardDashPayload;
import io.github.fastformer.protocol.s2c.movement.ForwardDashStartPayload;
import io.github.fastformer.protocol.c2s.settings.SettingPayload;

import io.github.fastformer.server.replace.QuickReplaceSystem;
import io.github.fastformer.fastplace.quickshape.QuickShapeDraft;
import io.github.fastformer.fastplace.session.GeometrySession;
import io.github.fastformer.fastplace.session.OperationSession;
import io.github.fastformer.network.transport.ClientboundTransport;
import io.github.fastformer.protocol.c2s.geometry.ClosePathPayload;
import io.github.fastformer.protocol.c2s.geometry.CycleStageModePayload;
import io.github.fastformer.protocol.c2s.geometry.GeometryGizmoDragPayload;
import io.github.fastformer.protocol.c2s.geometry.GeometryInteractionPayload;
import io.github.fastformer.protocol.c2s.geometry.GeometryPointPayload;
import io.github.fastformer.protocol.s2c.geometry.GeometryPreviewPayload;
import io.github.fastformer.protocol.c2s.geometry.GeometrySelectModePayload;
import io.github.fastformer.protocol.c2s.geometry.GeometryUndoPayload;
import io.github.fastformer.protocol.c2s.geometry.ScrollCandidatePayload;
import io.github.fastformer.protocol.c2s.operation.OperationApplyPayload;
import io.github.fastformer.protocol.c2s.operation.OperationExtendPayload;
import io.github.fastformer.protocol.c2s.operation.OperationInsertPointPayload;
import io.github.fastformer.protocol.c2s.operation.OperationPointClickPayload;
import io.github.fastformer.protocol.c2s.operation.OperationPointDragPayload;
import io.github.fastformer.protocol.c2s.operation.OperationPointPayload;
import io.github.fastformer.protocol.s2c.operation.OperationPreviewPayload;
import io.github.fastformer.protocol.c2s.operation.OperationSelectPointPayload;
import io.github.fastformer.protocol.c2s.operation.OperationTransformPayload;
import io.github.fastformer.protocol.c2s.workspace.OperationWorkspaceApplyPayload;
import io.github.fastformer.protocol.s2c.workspace.OperationWorkspaceReceiptPayload;
import io.github.fastformer.protocol.c2s.workspace.OperationWorkspaceReceiptQueryPayload;
import io.github.fastformer.protocol.s2c.workspace.OperationWorkspaceResultPayload;
import io.github.fastformer.protocol.c2s.placement.IdleInteractionCapturePayload;
import io.github.fastformer.protocol.s2c.placement.IdleInteractionSnapshotPayload;
import io.github.fastformer.protocol.c2s.placement.IdleSelectionEntryPayload;
import io.github.fastformer.protocol.s2c.placement.IdleSelectionEntryResultPayload;
import io.github.fastformer.protocol.s2c.placement.PlacementActionAckPayload;
import io.github.fastformer.protocol.c2s.placement.PlacementActionPayload;
import io.github.fastformer.protocol.c2s.placement.QuickReplacePayload;
import io.github.fastformer.protocol.c2s.placement.QuickReplaceSessionPayload;
import io.github.fastformer.protocol.s2c.placement.QuickReplaceStatePayload;
import io.github.fastformer.protocol.c2s.placement.QuickShapeConfirmPayload;
import io.github.fastformer.protocol.c2s.placement.QuickShapePointerPayload;
import io.github.fastformer.protocol.c2s.placement.QuitFastPlacePayload;
import io.github.fastformer.protocol.c2s.workspace.ShapePlacementPayload;
import io.github.fastformer.protocol.c2s.placement.SmartFillCommandPayload;
import io.github.fastformer.protocol.c2s.placement.StartPlacementPayload;
import io.github.fastformer.protocol.c2s.placement.TinkerInteractionPayload;
import io.github.fastformer.protocol.c2s.placement.UndoFastPlacePayload;
import io.github.fastformer.protocol.s2c.preview.ActivityStatePayload;
import io.github.fastformer.protocol.s2c.preview.BuildingPreviewEffectPayload;
import io.github.fastformer.protocol.s2c.preview.BuildingPreviewParametersPayload;
import io.github.fastformer.protocol.s2c.preview.BuildingPreviewSessionPayload;
import io.github.fastformer.protocol.s2c.preview.QuickShapeSubmissionParametersPayload;
import io.github.fastformer.protocol.s2c.preview.SmartFillPreviewPayload;
import io.github.fastformer.protocol.s2c.settings.FallingStatePayload;
import io.github.fastformer.protocol.s2c.settings.InteractionUpdatesPayload;
import io.github.fastformer.protocol.c2s.settings.ModifierStatePayload;
import io.github.fastformer.protocol.s2c.settings.OpenSettingsPayload;
import io.github.fastformer.protocol.s2c.world.HistoryConflictPayload;
import io.github.fastformer.protocol.c2s.world.HistoryConflictResponsePayload;
import io.github.fastformer.protocol.s2c.world.WorldHistoryEventPayload;
import io.github.fastformer.protocol.c2s.world.WorldRedoPayload;
import io.github.fastformer.protocol.c2s.world.WorldUndoPayload;
import io.github.fastformer.server.sync.PreviewSync;
import io.github.fastformer.server.input.ServerInputDispatcher;
import io.github.fastformer.server.session.QuickShapeSystem;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public final class FastPlaceNetwork {
   private FastPlaceNetwork() {
   }

   public static void register(IEventBus modBus) {
      modBus.addListener(FastPlaceNetwork::registerPayloads);
   }

   private static void registerPayloads(RegisterPayloadHandlersEvent event) {
      PayloadRegistrar registrar = event.registrar("84").optional();
      var channels = new ServerChannels(registrar);
      channels.receive(ForwardDashPayload.TYPE, ForwardDashPayload.STREAM_CODEC)
         .handle((payload, player) -> {
         io.github.fastformer.server.movement.ForwardDash.execute(player, payload.requestId(), payload.distance());
      });
      registrar.playToClient(ForwardDashStartPayload.TYPE,
         ForwardDashStartPayload.STREAM_CODEC,
         FastPlaceNetwork::receiveClientbound);
      channels.receive(SmartFillCommandPayload.TYPE, SmartFillCommandPayload.STREAM_CODEC)
         .handle((payload, player) -> {
         io.github.fastformer.server.fill.SmartFillSystem.handle(player, payload);
      });
      registrar.playToClient(SmartFillPreviewPayload.TYPE, SmartFillPreviewPayload.STREAM_CODEC,
         FastPlaceNetwork::receiveClientbound);
      registrar.playToClient(WorldHistoryEventPayload.TYPE, WorldHistoryEventPayload.STREAM_CODEC,
         FastPlaceNetwork::receiveClientbound);
      channels.receive(QuickReplaceSessionPayload.TYPE, QuickReplaceSessionPayload.STREAM_CODEC)
         .handle((payload, player) -> {
         boolean active = QuickReplaceSystem.setActive(player, payload.active());
         PacketDistributor.sendToPlayer(player, new QuickReplaceStatePayload(payload.requestId(), active));
      });
      registrar.playToClient(QuickReplaceStatePayload.TYPE, QuickReplaceStatePayload.STREAM_CODEC,
         FastPlaceNetwork::receiveClientbound);
      registrar.playToClient(InteractionUpdatesPayload.TYPE, InteractionUpdatesPayload.STREAM_CODEC,
         FastPlaceNetwork::receiveClientbound);
      registrar.playToClient(FallingStatePayload.TYPE, FallingStatePayload.STREAM_CODEC,
         FastPlaceNetwork::receiveClientbound);
      registrar.playToClient(QuickShapeSubmissionParametersPayload.TYPE, QuickShapeSubmissionParametersPayload.STREAM_CODEC,
         FastPlaceNetwork::receiveClientbound);
      registrar.playToClient(PlacementActionAckPayload.TYPE, PlacementActionAckPayload.STREAM_CODEC,
         FastPlaceNetwork::receiveClientbound);
      channels.receive(ModifierStatePayload.TYPE, ModifierStatePayload.STREAM_CODEC)
         .toIntent(payload -> new SessionIntents.Submode(payload.down()));
      channels.receive(IdleInteractionCapturePayload.TYPE, IdleInteractionCapturePayload.STREAM_CODEC)
         .handle((payload, player) -> {
         io.github.fastformer.server.input.IdleInteractionSnapshots.capture(player, payload);
      });
      registrar.playToClient(IdleInteractionSnapshotPayload.TYPE, IdleInteractionSnapshotPayload.STREAM_CODEC,
         FastPlaceNetwork::receiveClientbound);
      channels.receive(TinkerInteractionPayload.TYPE, TinkerInteractionPayload.STREAM_CODEC)
         .handle((payload, player) -> {
         ServerInputDispatcher.tinker(player, payload);
      });
      channels.receive(IdleSelectionEntryPayload.TYPE, IdleSelectionEntryPayload.STREAM_CODEC)
         .handle((payload, player) -> {
         var point = ServerInputDispatcher.idleSelectionEntry(player, payload);
         QuickShapeSystem.of(player).syncCurrentPreview(player);
         syncActivity(player);
         PacketDistributor.sendToPlayer(player, new IdleSelectionEntryResultPayload(payload.requestId(),
            player.level().dimension().location(), point == null ? net.minecraft.core.BlockPos.ZERO : point, point != null));
      });
      registrar.playToClient(IdleSelectionEntryResultPayload.TYPE, IdleSelectionEntryResultPayload.STREAM_CODEC,
         FastPlaceNetwork::receiveClientbound);
      channels.receive(SettingPayload.TYPE, SettingPayload.STREAM_CODEC)
         .toIntent(SettingPayload::request);
      channels.receive(OperationPointPayload.TYPE, OperationPointPayload.STREAM_CODEC)
         .handle((payload, player) -> ServerInputDispatcher.operationPoint(player, payload.role()));
      channels.receive(OperationExtendPayload.TYPE, OperationExtendPayload.STREAM_CODEC)
         .handle(FastPlaceNetwork::handleOperationExtend);
      channels.receive(OperationSelectPointPayload.TYPE, OperationSelectPointPayload.STREAM_CODEC)
         .handle((payload, player) -> ServerInputDispatcher.operationSelectPoint(player, payload));
      channels.receive(OperationPointDragPayload.TYPE, OperationPointDragPayload.STREAM_CODEC)
         .handle((payload, player) -> ServerInputDispatcher.operationPointDrag(player, payload));
      channels.receive(OperationPointClickPayload.TYPE, OperationPointClickPayload.STREAM_CODEC)
         .handle((payload, player) -> ServerInputDispatcher.operationPointClick(player, payload));
      channels.receive(OperationInsertPointPayload.TYPE, OperationInsertPointPayload.STREAM_CODEC)
         .handle((payload, player) -> ServerInputDispatcher.operationInsertPoint(player, payload));
      channels.receive(OperationApplyPayload.TYPE, OperationApplyPayload.STREAM_CODEC)
         .handle(FastPlaceNetwork::handleOperationApply);
      channels.receive(OperationWorkspaceApplyPayload.TYPE, OperationWorkspaceApplyPayload.STREAM_CODEC)
         .handle((payload, player) -> UploadSystem.of(player).receive(player, UploadKind.WORKSPACE, payload));
      channels.receive(ShapePlacementPayload.TYPE, ShapePlacementPayload.STREAM_CODEC)
         .handle((payload, player) -> UploadSystem.of(player).receive(player, UploadKind.SHAPE, payload));
      channels.receive(OperationTransformPayload.TYPE, OperationTransformPayload.STREAM_CODEC)
         .handle(FastPlaceNetwork::handleOperationTransform);
      channels.receive(StartPlacementPayload.TYPE, StartPlacementPayload.STREAM_CODEC)
         .handle(FastPlaceNetwork::handleStartPlacement);
      channels.receive(PlacementActionPayload.TYPE, PlacementActionPayload.STREAM_CODEC)
         .handle(FastPlaceNetwork::handlePlacementAction);
      channels.receive(QuickReplacePayload.TYPE, QuickReplacePayload.STREAM_CODEC)
         .handle((payload, player) -> QuickReplaceSystem.sample(player, payload.strokeId(), payload.finished()));
      channels.receive(QuickShapeConfirmPayload.TYPE, QuickShapeConfirmPayload.STREAM_CODEC)
         .handle(FastPlaceNetwork::handleQuickShapeConfirm);
      channels.receive(QuickShapePointerPayload.TYPE, QuickShapePointerPayload.STREAM_CODEC)
         .handle(FastPlaceNetwork::handleQuickShapePointer);
      channels.receive(GeometrySelectModePayload.TYPE, GeometrySelectModePayload.STREAM_CODEC)
         .handle((payload, player) -> ServerInputDispatcher.selectGeometryMode(player, payload.mode()));
      channels.receive(GeometryGizmoDragPayload.TYPE, GeometryGizmoDragPayload.STREAM_CODEC)
         .handle((payload, player) -> ServerInputDispatcher.geometryGizmoDrag(player, payload));
      channels.receive(GeometryInteractionPayload.TYPE, GeometryInteractionPayload.STREAM_CODEC)
         .handle((payload, player) -> ServerInputDispatcher.geometryInteraction(player, payload));
      channels.receive(GeometryPointPayload.TYPE, GeometryPointPayload.STREAM_CODEC)
         .handle((payload, player) -> ServerInputDispatcher.geometryPoint(player, payload));
      channels.receive(GeometryUndoPayload.TYPE, GeometryUndoPayload.STREAM_CODEC)
         .handle((payload, player) -> ServerInputDispatcher.geometryUndo(player, payload));
      channels.receive(ClosePathPayload.TYPE, ClosePathPayload.STREAM_CODEC)
         .handle((payload, player) -> ServerInputDispatcher.closeActivePath(player, payload));
      channels.receive(CycleStageModePayload.TYPE, CycleStageModePayload.STREAM_CODEC)
         .toIntent(payload -> new SessionIntents.CycleMode(payload.hasCandidate() ? payload.candidate() : null));
      channels.receive(ScrollCandidatePayload.TYPE, ScrollCandidatePayload.STREAM_CODEC)
         .toIntent(payload -> new SessionIntents.Adjust(payload.steps()));
      channels.receive(QuitFastPlacePayload.TYPE, QuitFastPlacePayload.STREAM_CODEC)
         .toIntent(payload -> new HistoryIntents.Quit());
      channels.receive(UndoFastPlacePayload.TYPE, UndoFastPlacePayload.STREAM_CODEC)
         .toIntent(payload -> new HistoryIntents.Undo(1, HistoryIntents.UndoScope.SESSION_OR_WORLD));
      channels.receive(WorldUndoPayload.TYPE, WorldUndoPayload.STREAM_CODEC)
         .toIntent(payload -> new HistoryIntents.Undo(1, HistoryIntents.UndoScope.SESSION_OR_WORLD));
      channels.receive(HistoryConflictResponsePayload.TYPE, HistoryConflictResponsePayload.STREAM_CODEC)
         .handle((payload, player) -> {
         HistorySystem.of(player).respondToConflict(player, payload);
      });
      registrar.playToClient(HistoryConflictPayload.TYPE, HistoryConflictPayload.STREAM_CODEC,
         FastPlaceNetwork::receiveClientbound);
      channels.receive(WorldRedoPayload.TYPE, WorldRedoPayload.STREAM_CODEC)
         .toIntent(payload -> new HistoryIntents.Redo(1));
      registrar.playToClient(
         BuildingPreviewSessionPayload.TYPE,
         BuildingPreviewSessionPayload.STREAM_CODEC,
         FastPlaceNetwork::receiveClientbound
      );
      registrar.playToClient(
         BuildingPreviewParametersPayload.TYPE,
         BuildingPreviewParametersPayload.STREAM_CODEC,
         FastPlaceNetwork::receiveClientbound
      );
      registrar.playToClient(
         BuildingPreviewEffectPayload.TYPE,
         BuildingPreviewEffectPayload.STREAM_CODEC,
         FastPlaceNetwork::receiveClientbound
      );
      registrar.playToClient(OperationPreviewPayload.TYPE, OperationPreviewPayload.STREAM_CODEC, FastPlaceNetwork::receiveClientbound);
      registrar.playToClient(
         OperationWorkspaceResultPayload.TYPE,
         OperationWorkspaceResultPayload.STREAM_CODEC,
         FastPlaceNetwork::receiveClientbound
      );
      channels.receive(OperationWorkspaceReceiptQueryPayload.TYPE, OperationWorkspaceReceiptQueryPayload.STREAM_CODEC)
         .handle((payload, player) -> UploadSystem.of(player).receipts().query(player, payload));
      registrar.playToClient(
         OperationWorkspaceReceiptPayload.TYPE,
         OperationWorkspaceReceiptPayload.STREAM_CODEC,
         FastPlaceNetwork::receiveClientbound
      );
      registrar.playToClient(GeometryPreviewPayload.TYPE, GeometryPreviewPayload.STREAM_CODEC, FastPlaceNetwork::receiveClientbound);
      registrar.playToClient(ActivityStatePayload.TYPE, ActivityStatePayload.STREAM_CODEC, FastPlaceNetwork::receiveClientbound);
      registrar.playToClient(OpenSettingsPayload.TYPE, OpenSettingsPayload.STREAM_CODEC, FastPlaceNetwork::receiveClientbound);
   }

   private static void handleOperationExtend(OperationExtendPayload payload, ServerPlayer player) {
      if (OperationExtendPayload.validAxis(payload.axis())) {
         ServerInputDispatcher.extend(player, payload);
      }
   }

   private static void handleOperationApply(OperationApplyPayload payload, ServerPlayer player) {
      try {
         ServerInputDispatcher.applyOperation(player, payload.copy(), payload.requestId());
      } finally {
         acknowledgePlacement(player, payload.requestId());
      }
   }

   private static void handleOperationTransform(OperationTransformPayload payload, ServerPlayer player) {
      if (payload.valid()) {
         ServerInputDispatcher.operationTransform(
            player, payload
         );
      }
   }

   private static void handlePlacementAction(PlacementActionPayload payload, ServerPlayer player) {
      try {
         ServerInputDispatcher.placementAction(player, payload);
      } finally {
         acknowledgePlacement(player, payload.requestId());
      }
   }

   private static void handleStartPlacement(StartPlacementPayload payload, ServerPlayer player) {
      try {
         ServerInputDispatcher.startPlacement(player, payload);
      } finally {
         acknowledgePlacement(player, payload.requestId());
      }
   }

   private static void handleQuickShapeConfirm(QuickShapeConfirmPayload payload, ServerPlayer player) {
      try {
         ServerInputDispatcher.confirmQuickShape(player, payload);
      } finally {
         acknowledgePlacement(player, payload.requestId());
      }
   }

   private static void handleQuickShapePointer(QuickShapePointerPayload payload, ServerPlayer player) {
      try {
         ServerInputDispatcher.quickShapePointer(player, payload);
      } finally {
         if (payload.action() == QuickShapePointerPayload.Action.MIDDLE
            || payload.action() == QuickShapePointerPayload.Action.SUBMIT_CURRENT) acknowledgePlacement(player, payload.requestId());
      }
   }

   private static void acknowledgePlacement(ServerPlayer player, long requestId) {
      QuickShapeSystem.of(player).syncCurrentPreview(player);
      syncActivity(player);
      PacketDistributor.sendToPlayer(player, new PlacementActionAckPayload(requestId, PreviewSync.callbackScope(player)));
   }


   private static void receiveClientbound(CustomPacketPayload payload, IPayloadContext context) {
      var connection = context.connection();
      context.enqueueWork(() -> ClientboundTransport.receive(payload, connection));
   }

   public static void syncPreview(ServerPlayer player, QuickShapeDraft session) {
      PreviewSync.syncPreview(player, session);
   }

   public static void syncOperation(ServerPlayer player, OperationSession session) {
      PreviewSync.syncOperation(player, session);
   }

   public static void clearPreview(ServerPlayer player) {
      PreviewSync.clearPreview(player);
   }

   public static void syncSettings(ServerPlayer player) {
      PreviewSync.syncSettings(player);
   }

   public static void syncGeometry(ServerPlayer player, GeometrySession session) {
      PreviewSync.syncGeometry(player, session);
   }

   public static void clearGeometry(ServerPlayer player) {
      PreviewSync.clearGeometry(player);
   }

   public static void syncActivity(ServerPlayer player) {
      PreviewSync.syncActivity(player);
   }

   public static void forgetActivity(ServerPlayer player) {
      PreviewSync.forgetActivity(player);
      UploadSystem.forget(player);
   }
}
