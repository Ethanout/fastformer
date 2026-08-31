package io.github.fastformer.network.payload.world;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Ctrl+Z: session rollback takes precedence; otherwise world undo. */
public record WorldUndoPayload() implements CustomPacketPayload {
   public static final WorldUndoPayload INSTANCE = new WorldUndoPayload();
   public static final Type<WorldUndoPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("fastformer", "world_undo"));
   public static final StreamCodec<FriendlyByteBuf, WorldUndoPayload> STREAM_CODEC = StreamCodec.unit(INSTANCE);

   @Override
   public Type<WorldUndoPayload> type() {
      return TYPE;
   }
}
