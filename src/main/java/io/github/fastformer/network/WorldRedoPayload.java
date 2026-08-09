package io.github.fastformer.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Ctrl+Y: active operation-session redo takes precedence over world history. */
public record WorldRedoPayload() implements CustomPacketPayload {
   public static final WorldRedoPayload INSTANCE = new WorldRedoPayload();
   public static final Type<WorldRedoPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("fastformer", "world_redo"));
   public static final StreamCodec<FriendlyByteBuf, WorldRedoPayload> STREAM_CODEC = StreamCodec.unit(INSTANCE);

   @Override
   public Type<WorldRedoPayload> type() {
      return TYPE;
   }
}
