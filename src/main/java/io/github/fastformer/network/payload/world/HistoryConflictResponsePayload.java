package io.github.fastformer.network.payload.world;

import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record HistoryConflictResponsePayload(UUID token, Choice choice) implements CustomPacketPayload {
   public enum Choice { CANCEL, OVERWRITE, SKIP }
   public static final Type<HistoryConflictResponsePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("fastformer", "history_conflict_response"));
   public static final StreamCodec<FriendlyByteBuf, HistoryConflictResponsePayload> STREAM_CODEC = StreamCodec.of(
      (buffer, value) -> { buffer.writeUUID(value.token()); buffer.writeEnum(value.choice()); },
      buffer -> new HistoryConflictResponsePayload(buffer.readUUID(), buffer.readEnum(Choice.class)));
   @Override public Type<HistoryConflictResponsePayload> type() { return TYPE; }
}
