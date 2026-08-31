package io.github.fastformer.network.payload.operation;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record OperationRemovePointPayload(int index) implements CustomPacketPayload {
   public static final Type<OperationRemovePointPayload> TYPE = new Type<>(
      ResourceLocation.fromNamespaceAndPath("fastformer", "operation_remove_point")
   );
   public static final StreamCodec<FriendlyByteBuf, OperationRemovePointPayload> STREAM_CODEC = CustomPacketPayload.codec(
      OperationRemovePointPayload::write, OperationRemovePointPayload::new
   );

   private OperationRemovePointPayload(FriendlyByteBuf buffer) {
      this(buffer.readVarInt());
   }

   private void write(FriendlyByteBuf buffer) {
      buffer.writeVarInt(this.index);
   }

   @Override
   public Type<OperationRemovePointPayload> type() {
      return TYPE;
   }
}
