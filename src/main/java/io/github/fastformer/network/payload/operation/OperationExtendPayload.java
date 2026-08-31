package io.github.fastformer.network.payload.operation;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type;
import net.minecraft.resources.ResourceLocation;

public record OperationExtendPayload(int axis, boolean positive, int steps, boolean finish) implements CustomPacketPayload {
   public static boolean validAxis(int axis) {
      return axis >= 0 && axis <= 8;
   }

   public static final Type<OperationExtendPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("fastformer", "operation_extend"));
   public static final StreamCodec<FriendlyByteBuf, OperationExtendPayload> STREAM_CODEC = CustomPacketPayload.codec(
      OperationExtendPayload::write, OperationExtendPayload::new
   );

   private OperationExtendPayload(FriendlyByteBuf buffer) {
      this(buffer.readVarInt(), buffer.readBoolean(), buffer.readVarInt(), buffer.readBoolean());
   }

   private void write(FriendlyByteBuf buffer) {
      buffer.writeVarInt(this.axis);
      buffer.writeBoolean(this.positive);
      buffer.writeVarInt(this.steps);
      buffer.writeBoolean(this.finish);
   }

   @Override
   public Type<OperationExtendPayload> type() {
      return TYPE;
   }
}
