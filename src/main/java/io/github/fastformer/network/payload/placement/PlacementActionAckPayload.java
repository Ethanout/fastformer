package io.github.fastformer.network.payload.placement;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record PlacementActionAckPayload(long requestId) implements CustomPacketPayload {
   public static final Type<PlacementActionAckPayload> TYPE = new Type<>(
      ResourceLocation.fromNamespaceAndPath("fastformer", "placement_action_ack")
   );
   public static final StreamCodec<FriendlyByteBuf, PlacementActionAckPayload> STREAM_CODEC =
      CustomPacketPayload.codec((payload, buffer) -> buffer.writeVarLong(payload.requestId()),
         buffer -> new PlacementActionAckPayload(buffer.readVarLong()));

   @Override
   public Type<PlacementActionAckPayload> type() {
      return TYPE;
   }
}
