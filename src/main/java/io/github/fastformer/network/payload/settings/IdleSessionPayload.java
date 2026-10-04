package io.github.fastformer.network.payload.settings;

import io.github.fastformer.fastplace.session.IdleSessionState;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record IdleSessionPayload(IdleSessionState state, ResourceLocation dimension) implements CustomPacketPayload {
   public static final Type<IdleSessionPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("fastformer", "idle_session"));
   public static final StreamCodec<FriendlyByteBuf, IdleSessionPayload> STREAM_CODEC = CustomPacketPayload.codec(
      (payload, buffer) -> { buffer.writeEnum(payload.state()); buffer.writeResourceLocation(payload.dimension()); },
      buffer -> new IdleSessionPayload(buffer.readEnum(IdleSessionState.class), buffer.readResourceLocation()));

   @Override public Type<IdleSessionPayload> type() { return TYPE; }
}
