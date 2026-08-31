package io.github.fastformer.network.payload.placement;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record ConfirmPayload() implements CustomPacketPayload {
   public static final ConfirmPayload INSTANCE = new ConfirmPayload();
   public static final Type<ConfirmPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("fastformer", "confirm"));
   public static final StreamCodec<FriendlyByteBuf, ConfirmPayload> STREAM_CODEC = StreamCodec.unit(INSTANCE);

   @Override
   public Type<ConfirmPayload> type() {
      return TYPE;
   }
}
