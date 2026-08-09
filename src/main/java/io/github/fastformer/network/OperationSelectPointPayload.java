package io.github.fastformer.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record OperationSelectPointPayload(int index) implements CustomPacketPayload {
   public static final Type<OperationSelectPointPayload> TYPE = new Type<>(
      ResourceLocation.fromNamespaceAndPath("fastformer", "operation_select_point")
   );
   public static final StreamCodec<FriendlyByteBuf, OperationSelectPointPayload> STREAM_CODEC = CustomPacketPayload.codec(
      OperationSelectPointPayload::write, OperationSelectPointPayload::new
   );

   private OperationSelectPointPayload(FriendlyByteBuf buffer) {
      this(buffer.readVarInt());
   }

   private void write(FriendlyByteBuf buffer) {
      buffer.writeVarInt(this.index);
   }

   @Override
   public Type<OperationSelectPointPayload> type() {
      return TYPE;
   }
}
