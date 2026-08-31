package io.github.fastformer.network.payload.geometry;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record ClosePathPayload() implements CustomPacketPayload {
   public static final ClosePathPayload INSTANCE = new ClosePathPayload();
   public static final Type<ClosePathPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("fastformer", "close_path"));
   public static final StreamCodec<FriendlyByteBuf, ClosePathPayload> STREAM_CODEC = StreamCodec.unit(INSTANCE);

   @Override
   public Type<ClosePathPayload> type() {
      return TYPE;
   }
}
