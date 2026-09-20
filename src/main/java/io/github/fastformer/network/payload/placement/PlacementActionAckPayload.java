package io.github.fastformer.network.payload.placement;

import io.github.fastformer.network.payload.operation.OperationCallbackScope;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record PlacementActionAckPayload(long requestId, OperationCallbackScope callbackScope) implements CustomPacketPayload {
   public static final Type<PlacementActionAckPayload> TYPE = new Type<>(
      ResourceLocation.fromNamespaceAndPath("fastformer", "placement_action_ack")
   );
   public static final StreamCodec<FriendlyByteBuf, PlacementActionAckPayload> STREAM_CODEC =
      CustomPacketPayload.codec(PlacementActionAckPayload::write, PlacementActionAckPayload::new);

   public PlacementActionAckPayload {
      if (callbackScope == null) {
         throw new IllegalArgumentException("Placement callback scope is required");
      }
   }

   public PlacementActionAckPayload(long requestId) {
      this(requestId, OperationCallbackScope.unscoped());
   }

   private PlacementActionAckPayload(FriendlyByteBuf buffer) {
      this(buffer.readVarLong(), OperationCallbackScope.STREAM_CODEC.decode(buffer));
   }

   private void write(FriendlyByteBuf buffer) {
      buffer.writeVarLong(requestId);
      OperationCallbackScope.STREAM_CODEC.encode(buffer, callbackScope);
   }

   @Override
   public Type<PlacementActionAckPayload> type() {
      return TYPE;
   }
}
