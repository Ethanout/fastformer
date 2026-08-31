package io.github.fastformer.network.payload.placement;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type;
import net.minecraft.resources.ResourceLocation;

public final class QuitFastPlacePayload implements CustomPacketPayload {
   public static final Type<QuitFastPlacePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("fastformer", "quit_fast_place"));
   public static final QuitFastPlacePayload INSTANCE = new QuitFastPlacePayload();
   public static final StreamCodec<FriendlyByteBuf, QuitFastPlacePayload> STREAM_CODEC = StreamCodec.unit(INSTANCE);

   private QuitFastPlacePayload() {
   }

   public Type<QuitFastPlacePayload> type() {
      return TYPE;
   }
}
