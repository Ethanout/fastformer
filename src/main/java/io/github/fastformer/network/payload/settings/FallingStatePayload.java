package io.github.fastformer.network.payload.settings;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record FallingStatePayload(boolean fallingDisabled, boolean allowed) implements CustomPacketPayload {
   public static final Type<FallingStatePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("fastformer", "falling_state"));
   public static final StreamCodec<FriendlyByteBuf, FallingStatePayload> STREAM_CODEC = CustomPacketPayload.codec(
      (value, buffer) -> { buffer.writeBoolean(value.fallingDisabled()); buffer.writeBoolean(value.allowed()); },
      buffer -> new FallingStatePayload(buffer.readBoolean(), buffer.readBoolean()));
   @Override public Type<FallingStatePayload> type() { return TYPE; }
}
