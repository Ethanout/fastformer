package io.github.fastformer.network.payload.operation;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type;
import net.minecraft.resources.ResourceLocation;

public record OperationExtendPayload(long requestId, long revision, OperationCallbackScope callbackScope,
   int axis, boolean positive, int steps, boolean finish) implements CustomPacketPayload {
   public static boolean validAxis(int axis) {
      return axis >= 0 && axis <= 8;
   }

   public static final Type<OperationExtendPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("fastformer", "operation_extend"));
   public static final StreamCodec<FriendlyByteBuf, OperationExtendPayload> STREAM_CODEC = CustomPacketPayload.codec(
      OperationExtendPayload::write, OperationExtendPayload::new
   );

   public OperationExtendPayload {
      if (requestId <= 0 || revision < 0 || callbackScope == null) throw new IllegalArgumentException("Invalid operation drag identity");
   }

   private OperationExtendPayload(FriendlyByteBuf buffer) {
      this(buffer.readVarLong(), buffer.readVarLong(), OperationCallbackScope.STREAM_CODEC.decode(buffer),
         buffer.readVarInt(), buffer.readBoolean(), buffer.readVarInt(), buffer.readBoolean());
   }

   private void write(FriendlyByteBuf buffer) {
      buffer.writeVarLong(this.requestId);
      buffer.writeVarLong(this.revision);
      OperationCallbackScope.STREAM_CODEC.encode(buffer, this.callbackScope);
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
