package io.github.fastformer.network.payload.operation;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record OperationWorkspaceResultPayload(UUID transferId, boolean accepted, List<Integer> failedPartIds)
   implements CustomPacketPayload {
   private static final int MAX_FAILED_PART_IDS = 4096;
   public static final Type<OperationWorkspaceResultPayload> TYPE = new Type<>(
      ResourceLocation.fromNamespaceAndPath("fastformer", "operation_workspace_result")
   );
   public static final StreamCodec<FriendlyByteBuf, OperationWorkspaceResultPayload> STREAM_CODEC =
      CustomPacketPayload.codec(OperationWorkspaceResultPayload::write, OperationWorkspaceResultPayload::new);

   public OperationWorkspaceResultPayload {
      if (transferId == null) {
         throw new IllegalArgumentException("Workspace transfer id is required");
      }
      failedPartIds = failedPartIds == null ? List.of() : List.copyOf(failedPartIds);
      if (failedPartIds.size() > MAX_FAILED_PART_IDS || failedPartIds.stream().anyMatch(id -> id < 1)) {
         throw new IllegalArgumentException("Invalid failed workspace part ids");
      }
   }

   private OperationWorkspaceResultPayload(FriendlyByteBuf buffer) {
      this(
         buffer.readUUID(),
         buffer.readBoolean(),
         buffer.readCollection(FriendlyByteBuf.limitValue(ArrayList::new, MAX_FAILED_PART_IDS), FriendlyByteBuf::readVarInt)
      );
   }

   private void write(FriendlyByteBuf buffer) {
      buffer.writeUUID(this.transferId);
      buffer.writeBoolean(this.accepted);
      buffer.writeCollection(this.failedPartIds, FriendlyByteBuf::writeVarInt);
   }

   @Override
   public Type<OperationWorkspaceResultPayload> type() {
      return TYPE;
   }
}
