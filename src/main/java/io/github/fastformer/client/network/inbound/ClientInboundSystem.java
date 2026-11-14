package io.github.fastformer.client.network.inbound;

import io.github.fastformer.client.input.FastPlaceClientInput;
import io.github.fastformer.client.input.ForwardDashController;
import io.github.fastformer.client.input.HistoryConflictConfirmation;
import io.github.fastformer.client.interaction.ClientInteractionUpdates;
import io.github.fastformer.client.placement.QuickReplaceMode;
import io.github.fastformer.client.session.ClientSessionSystem;
import io.github.fastformer.client.ui.FastFormerSettingsScreen;
import io.github.fastformer.common.net.ClientboundTransport;
import io.github.fastformer.core.net.InboundChannels;
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
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

/** Assembles client handlers; the queue owns routing, invalidation and tick delivery. */
@EventBusSubscriber(modid = "fastformer", value = Dist.CLIENT)
public final class ClientInboundSystem {
   private static final InboundChannels<ResourceLocation, CustomPacketPayload, Connection> INBOUND = createQueue();

   private ClientInboundSystem() { }

   @SubscribeEvent
   public static void setup(FMLClientSetupEvent event) {
      ClientboundTransport.install(delivery -> INBOUND.post(delivery.payload(), delivery.connection()));
   }

   public static void onClientTick() { INBOUND.drain(); }
   public static void endWorldSession() { INBOUND.invalidate(); }

   static InboundChannels<ResourceLocation, CustomPacketPayload, Connection> createQueue() {
      var queue = new InboundChannels<ResourceLocation, CustomPacketPayload, Connection>("client inbound", payload -> payload.type().id());
      preview(queue, BuildingPreviewSessionPayload.class, BuildingPreviewSessionPayload.TYPE,
         BuildingPreviewSessionPayload::callbackScope, payload -> ClientSessionSystem.instance().previewUpdates().buildingSession(payload));
      preview(queue, BuildingPreviewParametersPayload.class, BuildingPreviewParametersPayload.TYPE,
         BuildingPreviewParametersPayload::callbackScope, payload -> ClientSessionSystem.instance().previewUpdates().buildingParameters(payload));
      preview(queue, BuildingPreviewEffectPayload.class, BuildingPreviewEffectPayload.TYPE,
         BuildingPreviewEffectPayload::callbackScope, payload -> ClientSessionSystem.instance().previewUpdates().buildingEffect(payload));
      preview(queue, QuickShapeSubmissionParametersPayload.class, QuickShapeSubmissionParametersPayload.TYPE,
         QuickShapeSubmissionParametersPayload::callbackScope, payload -> ClientSessionSystem.instance().previewUpdates().submissionParameters(payload));
      preview(queue, OperationPreviewPayload.class, OperationPreviewPayload.TYPE,
         OperationPreviewPayload::callbackScope, payload -> ClientSessionSystem.instance().previewUpdates().operation(payload));
      preview(queue, GeometryPreviewPayload.class, GeometryPreviewPayload.TYPE,
         GeometryPreviewPayload::callbackScope, payload -> ClientSessionSystem.instance().previewUpdates().geometry(payload));
      preview(queue, ActivityStatePayload.class, ActivityStatePayload.TYPE,
         ActivityStatePayload::callbackScope, payload -> ClientSessionSystem.instance().previewUpdates().activity(payload));
      preview(queue, SmartFillPreviewPayload.class, SmartFillPreviewPayload.TYPE,
         SmartFillPreviewPayload::callbackScope, FastPlaceClientInput::receiveSmartFill);
      callback(queue, PlacementActionAckPayload.class, PlacementActionAckPayload.TYPE,
         PlacementActionAckPayload::callbackScope, FastPlaceClientInput::acknowledgePlacementRequest);
      callback(queue, OperationWorkspaceResultPayload.class, OperationWorkspaceResultPayload.TYPE,
         OperationWorkspaceResultPayload::callbackScope, payload -> ClientSessionSystem.instance().operations().submissions().applyResult(payload));
      receipt(queue, OperationWorkspaceReceiptPayload.class, OperationWorkspaceReceiptPayload.TYPE,
         payload -> ClientSessionSystem.instance().operations().receipts().apply(payload));
      receipt(queue, WorldHistoryEventPayload.class, WorldHistoryEventPayload.TYPE, payload -> ClientSessionSystem.instance().operations().applyWorldHistoryEvent(payload));
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

   private static <P extends CustomPacketPayload> void preview(InboundChannels<ResourceLocation, CustomPacketPayload, Connection> queue, Class<P> payloadClass,
      CustomPacketPayload.Type<P> type, Function<P, OperationCallbackScope> scope, Consumer<P> handler) {
      queue.receive(type.id(), payloadClass).accept((payload, connection) ->
         ClientSessionSystem.instance().acceptsPreviewCallback(scope.apply(payload), connection)).handle(handler);
   }

   private static <P extends CustomPacketPayload> void callback(InboundChannels<ResourceLocation, CustomPacketPayload, Connection> queue, Class<P> payloadClass,
      CustomPacketPayload.Type<P> type, Function<P, OperationCallbackScope> scope, Consumer<P> handler) {
      queue.receive(type.id(), payloadClass).accept((payload, connection) ->
         ClientSessionSystem.instance().acceptsCallback(scope.apply(payload), connection)).handle(handler);
   }

   private static <P extends CustomPacketPayload> void receipt(InboundChannels<ResourceLocation, CustomPacketPayload, Connection> queue, Class<P> payloadClass,
      CustomPacketPayload.Type<P> type, Consumer<P> handler) {
      queue.receive(type.id(), payloadClass).accept((payload, connection) ->
         ClientSessionSystem.instance().acceptsReceiptCallback(connection)).handle(handler);
   }

   private static void openSettings(OpenSettingsPayload payload) {
      FastFormerSettingsScreen.open(payload.middleConfirmEnabled(),
         payload.placementConflictMode(), payload.placementUpdateMode(), payload.enabledPlacementEffects(),
         payload.emptyHandWrench(), payload.fallingDisabled(), payload.worldUndoHistoryLimit(),
         payload.sessionUndoHistoryLimit(), payload.tinkerHistoryMergeLimits(), payload.skipRepeatedCropStages(),
         payload.vanillaBlockHistory());
   }
}
