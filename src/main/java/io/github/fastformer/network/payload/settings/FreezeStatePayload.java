package io.github.fastformer.network.payload.settings;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record FreezeStatePayload(boolean frozen, boolean allowed) implements CustomPacketPayload {
   public static final Type<FreezeStatePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("fastformer", "freeze_state"));
   public static final StreamCodec<FriendlyByteBuf, FreezeStatePayload> STREAM_CODEC = CustomPacketPayload.codec(
      (value, buffer) -> { buffer.writeBoolean(value.frozen()); buffer.writeBoolean(value.allowed()); },
      buffer -> new FreezeStatePayload(buffer.readBoolean(), buffer.readBoolean()));
   @Override public Type<FreezeStatePayload> type() { return TYPE; }
}
