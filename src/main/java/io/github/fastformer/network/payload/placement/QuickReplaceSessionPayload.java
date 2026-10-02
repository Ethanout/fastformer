package io.github.fastformer.network.payload.placement;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record QuickReplaceSessionPayload(long requestId, boolean active) implements CustomPacketPayload {
   public static final Type<QuickReplaceSessionPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("fastformer", "quick_replace_session"));
   public static final StreamCodec<FriendlyByteBuf, QuickReplaceSessionPayload> STREAM_CODEC = StreamCodec.of(
      (buffer, value) -> { buffer.writeVarLong(value.requestId()); buffer.writeBoolean(value.active()); },
      buffer -> new QuickReplaceSessionPayload(buffer.readVarLong(), buffer.readBoolean()));
   @Override public Type<QuickReplaceSessionPayload> type() { return TYPE; }
}
