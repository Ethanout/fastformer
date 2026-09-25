package io.github.fastformer.network.payload.geometry;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record ClosePathPayload(long requestId, long revision,
   io.github.fastformer.network.payload.operation.OperationCallbackScope scope, Kind kind) implements CustomPacketPayload {
   public enum Kind { OPERATION, GEOMETRY, BUILDING }
   public static final Type<ClosePathPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("fastformer", "close_path"));
   public static final StreamCodec<FriendlyByteBuf, ClosePathPayload> STREAM_CODEC = CustomPacketPayload.codec(
      (value, buffer) -> {
         buffer.writeVarLong(value.requestId()); buffer.writeVarLong(value.revision());
         io.github.fastformer.network.payload.operation.OperationCallbackScope.STREAM_CODEC.encode(buffer, value.scope());
         buffer.writeEnum(value.kind());
      }, buffer -> new ClosePathPayload(buffer.readVarLong(), buffer.readVarLong(),
         io.github.fastformer.network.payload.operation.OperationCallbackScope.STREAM_CODEC.decode(buffer), buffer.readEnum(Kind.class)));
   public ClosePathPayload {
      if (requestId <= 0 || revision < 0 || scope == null || kind == null) throw new IllegalArgumentException("Invalid path close identity");
   }

   @Override
   public Type<ClosePathPayload> type() {
      return TYPE;
   }
}
