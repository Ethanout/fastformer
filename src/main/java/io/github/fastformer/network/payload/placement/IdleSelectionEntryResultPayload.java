package io.github.fastformer.network.payload.placement;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record IdleSelectionEntryResultPayload(long requestId, ResourceLocation dimension, BlockPos position, boolean accepted)
   implements CustomPacketPayload {
   public IdleSelectionEntryResultPayload {
      if (requestId <= 0 || dimension == null || position == null) throw new IllegalArgumentException("Invalid idle selection result");
      position = position.immutable();
   }
   public static final Type<IdleSelectionEntryResultPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("fastformer", "idle_selection_entry_result"));
   public static final StreamCodec<FriendlyByteBuf, IdleSelectionEntryResultPayload> STREAM_CODEC = CustomPacketPayload.codec(
      (payload, buffer) -> {
         buffer.writeVarLong(payload.requestId()); buffer.writeResourceLocation(payload.dimension());
         buffer.writeBlockPos(payload.position()); buffer.writeBoolean(payload.accepted());
      }, buffer -> new IdleSelectionEntryResultPayload(buffer.readVarLong(), buffer.readResourceLocation(), buffer.readBlockPos(), buffer.readBoolean()));
   @Override public Type<IdleSelectionEntryResultPayload> type() { return TYPE; }
}
