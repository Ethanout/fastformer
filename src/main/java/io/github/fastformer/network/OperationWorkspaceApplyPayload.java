package io.github.fastformer.network;

import java.util.Arrays;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** One bounded chunk of a compressed client workspace apply plan. */
public record OperationWorkspaceApplyPayload(
   UUID transferId,
   int chunkIndex,
   int chunkCount,
   byte[] data
) implements CustomPacketPayload {
   public static final int MAX_CHUNK_BYTES = 24 * 1024;
   public static final int MAX_CHUNKS = 4096;
   public static final Type<OperationWorkspaceApplyPayload> TYPE = new Type<>(
      ResourceLocation.fromNamespaceAndPath("fastformer", "operation_workspace_apply")
   );
   public static final StreamCodec<FriendlyByteBuf, OperationWorkspaceApplyPayload> STREAM_CODEC =
      CustomPacketPayload.codec(OperationWorkspaceApplyPayload::write, OperationWorkspaceApplyPayload::new);

   public OperationWorkspaceApplyPayload {
      if (transferId == null || chunkCount < 1 || chunkCount > MAX_CHUNKS
         || chunkIndex < 0 || chunkIndex >= chunkCount || data == null
         || data.length == 0 || data.length > MAX_CHUNK_BYTES) {
         throw new IllegalArgumentException("Invalid workspace transfer chunk");
      }
      data = Arrays.copyOf(data, data.length);
   }

   private OperationWorkspaceApplyPayload(FriendlyByteBuf buffer) {
      this(buffer.readUUID(), buffer.readVarInt(), buffer.readVarInt(), buffer.readByteArray(MAX_CHUNK_BYTES));
   }

   private void write(FriendlyByteBuf buffer) {
      buffer.writeUUID(this.transferId);
      buffer.writeVarInt(this.chunkIndex);
      buffer.writeVarInt(this.chunkCount);
      buffer.writeByteArray(this.data);
   }

   @Override
   public byte[] data() {
      return Arrays.copyOf(this.data, this.data.length);
   }

   @Override
   public Type<OperationWorkspaceApplyPayload> type() {
      return TYPE;
   }
}
