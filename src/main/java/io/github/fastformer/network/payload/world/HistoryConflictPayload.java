package io.github.fastformer.network.payload.world;

import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** One task-scoped confirmation. An empty count clears only the matching prompt. */
public record HistoryConflictPayload(UUID token, ResourceLocation dimension, int total, List<BlockPos> positions)
   implements CustomPacketPayload {
   public static final int MAX_POSITIONS = 4096;
   public static final Type<HistoryConflictPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("fastformer", "history_conflict"));
   public static final StreamCodec<FriendlyByteBuf, HistoryConflictPayload> STREAM_CODEC = StreamCodec.of(
      (buffer, value) -> {
         buffer.writeUUID(value.token()); buffer.writeResourceLocation(value.dimension()); buffer.writeVarInt(value.total());
         buffer.writeVarInt(value.positions().size()); value.positions().forEach(buffer::writeBlockPos);
      }, buffer -> {
         UUID token = buffer.readUUID(); var dimension = buffer.readResourceLocation(); int total = buffer.readVarInt();
         int count = buffer.readVarInt();
         if (total < 0 || count < 0 || count > MAX_POSITIONS || count > total) throw new IllegalArgumentException("Invalid conflict count");
         var positions = new java.util.ArrayList<BlockPos>(count);
         for (int i = 0; i < count; i++) positions.add(buffer.readBlockPos());
         return new HistoryConflictPayload(token, dimension, total, positions);
      });
   public HistoryConflictPayload { positions = List.copyOf(positions); }
   @Override public Type<HistoryConflictPayload> type() { return TYPE; }
}
