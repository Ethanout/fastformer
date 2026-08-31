package io.github.fastformer.network.payload.placement;

import java.util.Arrays;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** A bounded chunk of a client-generated, already-resolved placement shape. */
public record ShapePlacementPayload(
   UUID transferId,
   int chunkIndex,
   int chunkCount,
   byte[] data
) implements CustomPacketPayload {
   public static final int MAX_CHUNK_BYTES = 24 * 1024;
   public static final int MAX_CHUNKS = 4096;
   public static final Type<ShapePlacementPayload> TYPE = new Type<>(
      ResourceLocation.fromNamespaceAndPath("fastformer", "shape_placement")
   );
   public static final StreamCodec<FriendlyByteBuf, ShapePlacementPayload> STREAM_CODEC =
      CustomPacketPayload.codec(ShapePlacementPayload::write, ShapePlacementPayload::new);

   public ShapePlacementPayload {
      if (transferId == null || chunkCount < 1 || chunkCount > MAX_CHUNKS
         || chunkIndex < 0 || chunkIndex >= chunkCount || data == null
         || data.length == 0 || data.length > MAX_CHUNK_BYTES) {
         throw new IllegalArgumentException("Invalid shape placement chunk");
      }
      data = Arrays.copyOf(data, data.length);
   }

   private ShapePlacementPayload(FriendlyByteBuf buffer) {
      this(buffer.readUUID(), buffer.readVarInt(), buffer.readVarInt(), buffer.readByteArray(MAX_CHUNK_BYTES));
   }

   private void write(FriendlyByteBuf buffer) {
      buffer.writeUUID(transferId);
      buffer.writeVarInt(chunkIndex);
      buffer.writeVarInt(chunkCount);
      buffer.writeByteArray(data);
   }

   @Override
   public byte[] data() {
      return Arrays.copyOf(data, data.length);
   }

   @Override
   public Type<ShapePlacementPayload> type() {
      return TYPE;
   }
}
