package io.github.fastformer.network;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record OperationWorkspaceResultPayload(boolean accepted, List<Integer> failedPartIds)
   implements CustomPacketPayload {
   public static final Type<OperationWorkspaceResultPayload> TYPE = new Type<>(
      ResourceLocation.fromNamespaceAndPath("fastformer", "operation_workspace_result")
   );
   public static final StreamCodec<FriendlyByteBuf, OperationWorkspaceResultPayload> STREAM_CODEC =
      CustomPacketPayload.codec(OperationWorkspaceResultPayload::write, OperationWorkspaceResultPayload::new);

   public OperationWorkspaceResultPayload {
      failedPartIds = failedPartIds == null ? List.of() : List.copyOf(failedPartIds);
      if (failedPartIds.size() > 10 || failedPartIds.stream().anyMatch(id -> id < 1 || id > 10)) {
         throw new IllegalArgumentException("Invalid failed workspace part ids");
      }
   }

   private OperationWorkspaceResultPayload(FriendlyByteBuf buffer) {
      this(
         buffer.readBoolean(),
         buffer.readCollection(FriendlyByteBuf.limitValue(ArrayList::new, 10), FriendlyByteBuf::readVarInt)
      );
   }

   private void write(FriendlyByteBuf buffer) {
      buffer.writeBoolean(this.accepted);
      buffer.writeCollection(this.failedPartIds, FriendlyByteBuf::writeVarInt);
   }

   @Override
   public Type<OperationWorkspaceResultPayload> type() {
      return TYPE;
   }
}
