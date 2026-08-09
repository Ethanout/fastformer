package io.github.fastformer.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type;
import net.minecraft.resources.ResourceLocation;

public final class UndoFastPlacePayload implements CustomPacketPayload {
   public static final Type<UndoFastPlacePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("fastformer", "undo_fast_place"));
   public static final UndoFastPlacePayload INSTANCE = new UndoFastPlacePayload();
   public static final StreamCodec<FriendlyByteBuf, UndoFastPlacePayload> STREAM_CODEC = StreamCodec.unit(INSTANCE);

   private UndoFastPlacePayload() {
   }

   public Type<UndoFastPlacePayload> type() {
      return TYPE;
   }
}
