package io.github.fastformer.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;

public record OperationApplyPayload(boolean copy) implements CustomPacketPayload {
   public static final Type<OperationApplyPayload> TYPE = new Type<>(
      ResourceLocation.fromNamespaceAndPath("fastformer", "operation_apply")
   );
   public static final StreamCodec<FriendlyByteBuf, OperationApplyPayload> STREAM_CODEC = CustomPacketPayload.codec(
      OperationApplyPayload::write, OperationApplyPayload::new
   );

   private OperationApplyPayload(FriendlyByteBuf buffer) {
      this(buffer.readBoolean());
   }

   private void write(FriendlyByteBuf buffer) {
      buffer.writeBoolean(this.copy);
   }

   @Override
   public Type<OperationApplyPayload> type() {
      return TYPE;
   }
}
