package io.github.fastformer.network;

import io.github.fastformer.FastFormer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public final class CycleStageModePayload implements CustomPacketPayload {
    public static final Type<CycleStageModePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(FastFormer.MOD_ID, "cycle_stage_mode"));
    public static final CycleStageModePayload INSTANCE = new CycleStageModePayload();
    public static final StreamCodec<FriendlyByteBuf, CycleStageModePayload> STREAM_CODEC = StreamCodec.unit(INSTANCE);

    private CycleStageModePayload() {
    }

    @Override
    public Type<CycleStageModePayload> type() {
        return TYPE;
    }
}
