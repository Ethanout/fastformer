package io.github.fastformer.network.payload.operation;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

public record OperationWorkspaceResultPayload(
   UUID transferId,
   boolean accepted,
   boolean retryable,
   List<Integer> failedPartIds,
   List<BlockPos> failedTargetPositions,
   OperationCallbackScope callbackScope
)
   implements CustomPacketPayload {
   private static final int MAX_FAILED_PART_IDS = 4096;
   private static final int MAX_FAILED_TARGET_POSITIONS = 65536;
   public static final Type<OperationWorkspaceResultPayload> TYPE = new Type<>(
      ResourceLocation.fromNamespaceAndPath("fastformer", "operation_workspace_result")
   );
   public static final StreamCodec<FriendlyByteBuf, OperationWorkspaceResultPayload> STREAM_CODEC =
      CustomPacketPayload.codec(OperationWorkspaceResultPayload::write, OperationWorkspaceResultPayload::new);

   public OperationWorkspaceResultPayload(UUID transferId, boolean accepted, List<Integer> failedPartIds) {
      this(transferId, accepted, true, failedPartIds, List.of(), unscopedCallbackScope());
   }

   public OperationWorkspaceResultPayload(
      UUID transferId, boolean accepted, List<Integer> failedPartIds, List<BlockPos> failedTargetPositions
   ) {
      this(transferId, accepted, true, failedPartIds, failedTargetPositions, unscopedCallbackScope());
   }

   public OperationWorkspaceResultPayload(
      UUID transferId, boolean accepted, boolean retryable, List<Integer> failedPartIds, List<BlockPos> failedTargetPositions
   ) {
      this(transferId, accepted, retryable, failedPartIds, failedTargetPositions, unscopedCallbackScope());
   }

   public OperationWorkspaceResultPayload {
      if (transferId == null) {
         throw new IllegalArgumentException("Workspace transfer id is required");
      }
      if (callbackScope == null) {
         throw new IllegalArgumentException("Workspace callback scope is required");
      }
      failedPartIds = failedPartIds == null ? List.of() : List.copyOf(failedPartIds);
      failedTargetPositions = failedTargetPositions == null ? List.of() : failedTargetPositions.stream().map(BlockPos::immutable).toList();
      if (failedPartIds.size() > MAX_FAILED_PART_IDS || failedPartIds.stream().anyMatch(id -> id < 1)) {
         throw new IllegalArgumentException("Invalid failed workspace part ids");
      }
      if (failedTargetPositions.size() > MAX_FAILED_TARGET_POSITIONS) {
         throw new IllegalArgumentException("Invalid failed workspace target positions");
      }
   }

   private OperationWorkspaceResultPayload(FriendlyByteBuf buffer) {
      this(
         buffer.readUUID(),
         buffer.readBoolean(),
         buffer.readBoolean(),
         buffer.readCollection(FriendlyByteBuf.limitValue(ArrayList::new, MAX_FAILED_PART_IDS), FriendlyByteBuf::readVarInt),
         buffer.readCollection(
            FriendlyByteBuf.limitValue(ArrayList::new, MAX_FAILED_TARGET_POSITIONS),
            bufferValue -> bufferValue.readBlockPos()
         ),
         OperationCallbackScope.STREAM_CODEC.decode(buffer)
      );
   }

   public OperationWorkspaceResultPayload withCallbackScope(OperationCallbackScope callbackScope) {
      return new OperationWorkspaceResultPayload(transferId, accepted, retryable, failedPartIds, failedTargetPositions, callbackScope);
   }

   private void write(FriendlyByteBuf buffer) {
      buffer.writeUUID(this.transferId);
      buffer.writeBoolean(this.accepted);
      buffer.writeBoolean(this.retryable);
      buffer.writeCollection(this.failedPartIds, FriendlyByteBuf::writeVarInt);
      buffer.writeCollection(this.failedTargetPositions, (buf, pos) -> buf.writeBlockPos(pos));
      OperationCallbackScope.STREAM_CODEC.encode(buffer, this.callbackScope);
   }

   private static OperationCallbackScope unscopedCallbackScope() {
      return new OperationCallbackScope(
         new UUID(0L, 0L), ResourceLocation.fromNamespaceAndPath("fastformer", "unscoped"), new UUID(0L, 0L)
      );
   }

   @Override
   public Type<OperationWorkspaceResultPayload> type() {
      return TYPE;
   }
}
