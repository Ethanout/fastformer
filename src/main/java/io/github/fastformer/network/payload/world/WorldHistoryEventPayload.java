package io.github.fastformer.network.payload.world;

import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Reports a completed history transition, never an optimistic write request. */
public record WorldHistoryEventPayload(UUID operationId, ResourceLocation dimension, Kind kind, boolean complete) implements CustomPacketPayload {
   public enum Kind { RECORD, UNDO, REDO }
   public static final Type<WorldHistoryEventPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("fastformer", "world_history_event"));
   public static final StreamCodec<FriendlyByteBuf, WorldHistoryEventPayload> STREAM_CODEC = StreamCodec.of(
      (buffer, value) -> { buffer.writeUUID(value.operationId()); buffer.writeResourceLocation(value.dimension()); buffer.writeEnum(value.kind()); buffer.writeBoolean(value.complete()); },
      buffer -> new WorldHistoryEventPayload(buffer.readUUID(), buffer.readResourceLocation(), buffer.readEnum(Kind.class), buffer.readBoolean()));
   @Override public Type<WorldHistoryEventPayload> type() { return TYPE; }
}
