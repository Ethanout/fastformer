package io.github.fastformer.network.payload.operation;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record OperationInsertPointPayload() implements CustomPacketPayload {
   public static final OperationInsertPointPayload INSTANCE = new OperationInsertPointPayload();
   public static final Type<OperationInsertPointPayload> TYPE = new Type<>(
      ResourceLocation.fromNamespaceAndPath("fastformer", "operation_insert_point")
   );
   public static final StreamCodec<ByteBuf, OperationInsertPointPayload> STREAM_CODEC = StreamCodec.unit(INSTANCE);

   @Override
   public Type<OperationInsertPointPayload> type() {
      return TYPE;
   }
}
