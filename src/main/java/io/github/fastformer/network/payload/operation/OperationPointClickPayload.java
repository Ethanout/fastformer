package io.github.fastformer.network.payload.operation;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** One short, completed operation control-point gesture. */
public record OperationPointClickPayload(
   long gestureId, long revision, OperationCallbackScope callbackScope, Action action, int pointIndex
) implements CustomPacketPayload {
   public static final Type<OperationPointClickPayload> TYPE = new Type<>(
      ResourceLocation.fromNamespaceAndPath("fastformer", "operation_point_click")
   );
   public static final StreamCodec<FriendlyByteBuf, OperationPointClickPayload> STREAM_CODEC =
      CustomPacketPayload.codec(OperationPointClickPayload::write, OperationPointClickPayload::new);

   public OperationPointClickPayload {
      if (gestureId <= 0 || revision < 0 || callbackScope == null || action == null || pointIndex < 0) {
         throw new IllegalArgumentException("Operation point click requires a completed owned gesture");
      }
   }

   private OperationPointClickPayload(FriendlyByteBuf buffer) {
      this(buffer.readVarLong(), buffer.readVarLong(), OperationCallbackScope.STREAM_CODEC.decode(buffer),
         buffer.readEnum(Action.class), buffer.readVarInt());
   }

   private void write(FriendlyByteBuf buffer) {
      buffer.writeVarLong(gestureId);
      buffer.writeVarLong(revision);
      OperationCallbackScope.STREAM_CODEC.encode(buffer, callbackScope);
      buffer.writeEnum(action);
      buffer.writeVarInt(pointIndex);
   }

   @Override public Type<OperationPointClickPayload> type() { return TYPE; }

   public enum Action { CLOSE, REMOVE }
}
