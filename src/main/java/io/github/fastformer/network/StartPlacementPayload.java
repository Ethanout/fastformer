package io.github.fastformer.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Starts the building selection session at the remote crosshair hit. */
public record StartPlacementPayload(boolean embedded) implements CustomPacketPayload {
   public static final StartPlacementPayload INSTANCE = new StartPlacementPayload(false);
   public static final StartPlacementPayload EMBEDDED_INSTANCE = new StartPlacementPayload(true);
   public static final Type<StartPlacementPayload> TYPE = new Type<>(
      ResourceLocation.fromNamespaceAndPath("fastformer", "start_placement")
   );
   public static final StreamCodec<FriendlyByteBuf, StartPlacementPayload> STREAM_CODEC = StreamCodec.composite(
      ByteBufCodecs.BOOL, StartPlacementPayload::embedded, StartPlacementPayload::new
   );

   @Override
   public Type<StartPlacementPayload> type() {
      return TYPE;
   }
}
