package io.github.fastformer.network.payload.placement;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record QuickReplaceStatePayload(long requestId, boolean active) implements CustomPacketPayload {
   public static final Type<QuickReplaceStatePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("fastformer", "quick_replace_state"));
   public static final StreamCodec<FriendlyByteBuf, QuickReplaceStatePayload> STREAM_CODEC = StreamCodec.of(
      (buffer, value) -> { buffer.writeVarLong(value.requestId()); buffer.writeBoolean(value.active()); },
      buffer -> new QuickReplaceStatePayload(buffer.readVarLong(), buffer.readBoolean()));
   @Override public Type<QuickReplaceStatePayload> type() { return TYPE; }
}
