package io.github.fastformer.client.network.inbound;

import io.github.fastformer.client.input.FastPlaceClientInput;
import io.github.fastformer.client.input.ForwardDashController;
import io.github.fastformer.client.input.HistoryConflictConfirmation;
import io.github.fastformer.client.interaction.ClientInteractionUpdates;
import io.github.fastformer.client.operation.controller.ClientOperationController;
import io.github.fastformer.client.placement.QuickReplaceMode;
import io.github.fastformer.client.render.core.FastPlaceClientPreviewCore;
import io.github.fastformer.client.session.ClientSessionSystem;
import io.github.fastformer.client.ui.FastFormerSettingsScreen;
import io.github.fastformer.network.transport.ClientboundTransport;
import io.github.fastformer.protocol.callback.OperationCallbackScope;
import io.github.fastformer.protocol.s2c.geometry.GeometryPreviewPayload;
import io.github.fastformer.protocol.s2c.movement.ForwardDashStartPayload;
import io.github.fastformer.protocol.s2c.operation.OperationPreviewPayload;
import io.github.fastformer.protocol.s2c.placement.IdleInteractionSnapshotPayload;
import io.github.fastformer.protocol.s2c.placement.IdleSelectionEntryResultPayload;
import io.github.fastformer.protocol.s2c.placement.PlacementActionAckPayload;
import io.github.fastformer.protocol.s2c.placement.QuickReplaceStatePayload;
import io.github.fastformer.protocol.s2c.preview.ActivityStatePayload;
import io.github.fastformer.protocol.s2c.preview.BuildingPreviewEffectPayload;
import io.github.fastformer.protocol.s2c.preview.BuildingPreviewParametersPayload;
import io.github.fastformer.protocol.s2c.preview.BuildingPreviewSessionPayload;
import io.github.fastformer.protocol.s2c.preview.QuickShapeSubmissionParametersPayload;
import io.github.fastformer.protocol.s2c.preview.SmartFillPreviewPayload;
import io.github.fastformer.protocol.s2c.settings.FallingStatePayload;
import io.github.fastformer.protocol.s2c.settings.InteractionUpdatesPayload;
import io.github.fastformer.protocol.s2c.settings.OpenSettingsPayload;
import io.github.fastformer.protocol.s2c.workspace.OperationWorkspaceReceiptPayload;
import io.github.fastformer.protocol.s2c.workspace.OperationWorkspaceResultPayload;
import io.github.fastformer.protocol.s2c.world.HistoryConflictPayload;
import io.github.fastformer.protocol.s2c.world.WorldHistoryEventPayload;
import java.util.function.Consumer;
import java.util.function.Function;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

/** Assembles client handlers; the queue owns routing, invalidation and tick delivery. */
@EventBusSubscriber(modid = "fastformer", value = Dist.CLIENT)
public final class ClientInboundSystem {
   private static final InboundPayloadQueue INBOUND = createQueue();

   private ClientInboundSystem() { }

   @SubscribeEvent
   public static void setup(FMLClientSetupEvent event) {
      ClientboundTransport.install(INBOUND::post);
   }

   public static void onClientTick() { INBOUND.drain(); }
   public static void endWorldSession() { INBOUND.invalidate(); }

   static InboundPayloadQueue createQueue() {
      InboundPayloadQueue queue = new InboundPayloadQueue();
      preview(queue, BuildingPreviewSessionPayload.class, BuildingPreviewSessionPayload.TYPE,
         BuildingPreviewSessionPayload::callbackScope, FastPlaceClientPreviewCore::applyBuildingSession);
      preview(queue, BuildingPreviewParametersPayload.class, BuildingPreviewParametersPayload.TYPE,
         BuildingPreviewParametersPayload::callbackScope, FastPlaceClientPreviewCore::applyBuildingParameters);
      preview(queue, BuildingPreviewEffectPayload.class, BuildingPreviewEffectPayload.TYPE,
         BuildingPreviewEffectPayload::callbackScope, FastPlaceClientPreviewCore::applyBuildingEffect);
      preview(queue, QuickShapeSubmissionParametersPayload.class, QuickShapeSubmissionParametersPayload.TYPE,
         QuickShapeSubmissionParametersPayload::callbackScope, FastPlaceClientPreviewCore::applyQuickShapeSubmissionParameters);
      preview(queue, OperationPreviewPayload.class, OperationPreviewPayload.TYPE,
         OperationPreviewPayload::callbackScope, FastPlaceClientPreviewCore::applyOperation);
      preview(queue, GeometryPreviewPayload.class, GeometryPreviewPayload.TYPE,
         GeometryPreviewPayload::callbackScope, FastPlaceClientPreviewCore::applyGeometry);
      preview(queue, ActivityStatePayload.class, ActivityStatePayload.TYPE,
         ActivityStatePayload::callbackScope, FastPlaceClientPreviewCore::applyActivity);
      preview(queue, SmartFillPreviewPayload.class, SmartFillPreviewPayload.TYPE,
         SmartFillPreviewPayload::callbackScope, FastPlaceClientInput::receiveSmartFill);
      callback(queue, PlacementActionAckPayload.class, PlacementActionAckPayload.TYPE,
         PlacementActionAckPayload::callbackScope, FastPlaceClientInput::acknowledgePlacementRequest);
      callback(queue, OperationWorkspaceResultPayload.class, OperationWorkspaceResultPayload.TYPE,
         OperationWorkspaceResultPayload::callbackScope, ClientOperationController::applyWorkspaceResult);
      receipt(queue, OperationWorkspaceReceiptPayload.class, OperationWorkspaceReceiptPayload.TYPE,
         ClientOperationController::applyWorkspaceReceipt);
      receipt(queue, WorldHistoryEventPayload.class, WorldHistoryEventPayload.TYPE, ClientOperationController::applyWorldHistoryEvent);
      receipt(queue, QuickReplaceStatePayload.class, QuickReplaceStatePayload.TYPE, QuickReplaceMode::receive);
      receipt(queue, InteractionUpdatesPayload.class, InteractionUpdatesPayload.TYPE, ClientInteractionUpdates::receive);
      receipt(queue, IdleInteractionSnapshotPayload.class, IdleInteractionSnapshotPayload.TYPE, FastPlaceClientInput::receiveIdleInteraction);
      receipt(queue, IdleSelectionEntryResultPayload.class, IdleSelectionEntryResultPayload.TYPE, FastPlaceClientInput::receiveIdleSelectionEntry);
      receipt(queue, HistoryConflictPayload.class, HistoryConflictPayload.TYPE, HistoryConflictConfirmation::receive);
      receipt(queue, ForwardDashStartPayload.class, ForwardDashStartPayload.TYPE, ForwardDashController::receive);
      receipt(queue, OpenSettingsPayload.class, OpenSettingsPayload.TYPE, ClientInboundSystem::openSettings);
      receipt(queue, FallingStatePayload.class, FallingStatePayload.TYPE, FastFormerSettingsScreen::applyFallingState);
      queue.freeze();
      return queue;
   }

   private static <P extends CustomPacketPayload> void preview(InboundPayloadQueue queue, Class<P> payloadClass,
      CustomPacketPayload.Type<P> type, Function<P, OperationCallbackScope> scope, Consumer<P> handler) {
      queue.register(payloadClass, type, (payload, connection) ->
         ClientSessionSystem.instance().acceptsPreviewCallback(scope.apply(payload), connection), handler);
   }

   private static <P extends CustomPacketPayload> void callback(InboundPayloadQueue queue, Class<P> payloadClass,
      CustomPacketPayload.Type<P> type, Function<P, OperationCallbackScope> scope, Consumer<P> handler) {
      queue.register(payloadClass, type, (payload, connection) ->
         ClientSessionSystem.instance().acceptsCallback(scope.apply(payload), connection), handler);
   }

   private static <P extends CustomPacketPayload> void receipt(InboundPayloadQueue queue, Class<P> payloadClass,
      CustomPacketPayload.Type<P> type, Consumer<P> handler) {
      queue.register(payloadClass, type, (payload, connection) ->
         ClientSessionSystem.instance().acceptsReceiptCallback(connection), handler);
   }

   private static void openSettings(OpenSettingsPayload payload) {
      FastFormerSettingsScreen.open(payload.middleConfirmEnabled(),
         payload.placementConflictMode(), payload.placementUpdateMode(), payload.enabledPlacementEffects(),
         payload.emptyHandWrench(), payload.fallingDisabled(), payload.worldUndoHistoryLimit(),
         payload.sessionUndoHistoryLimit(), payload.tinkerHistoryMergeLimits(), payload.skipRepeatedCropStages(),
         payload.vanillaBlockHistory());
   }
}
