package io.github.fastformer.network;

import io.github.fastformer.fastplace.FastPlaceManager;
import io.github.fastformer.fastplace.FastPlaceSettings;
import io.github.fastformer.fastplace.FastPlaceSession;
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
        PayloadRegistrar registrar = event.registrar("1").optional();
        registrar.playToServer(
                CycleStageModePayload.TYPE,
                CycleStageModePayload.STREAM_CODEC,
                FastPlaceNetwork::handleCycleStageMode);
        registrar.playToClient(
                PreviewStatePayload.TYPE,
                PreviewStatePayload.STREAM_CODEC,
                FastPlaceNetwork::handlePreviewState);
    }

    private static void handleCycleStageMode(CycleStageModePayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player
                && player.isCreative()
                && FastPlaceSettings.load(player).enabled()
                && FastPlaceManager.active(player)) {
            FastPlaceManager.cycleStageMode(player);
        }
    }

    private static void handlePreviewState(PreviewStatePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> applyClientPreview(payload));
    }

    public static void syncPreview(ServerPlayer player, FastPlaceSession session) {
        sendPreview(player, PreviewStatePayload.active(session.points(), FastPlaceSettings.load(player)));
    }

    public static void clearPreview(ServerPlayer player) {
        sendPreview(player, PreviewStatePayload.inactive());
    }

    private static void sendPreview(ServerPlayer player, PreviewStatePayload payload) {
        if (player.connection.hasChannel(PreviewStatePayload.TYPE)) {
            PacketDistributor.sendToPlayer(player, payload);
        }
    }

    private static void applyClientPreview(PreviewStatePayload payload) {
        try {
            Class<?> handler = Class.forName("io.github.fastformer.client.FastPlaceClientPreview");
            handler.getMethod("apply", PreviewStatePayload.class).invoke(null, payload);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unable to apply FastFormer client preview state", exception);
        }
    }
}
