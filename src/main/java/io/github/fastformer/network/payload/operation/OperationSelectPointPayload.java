package io.github.fastformer.network.payload.operation;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import io.github.fastformer.network.payload.operation.OperationCallbackScope;
import net.minecraft.world.phys.Vec3;

public record OperationSelectPointPayload(long requestId, long revision, OperationCallbackScope callbackScope, int index, Vec3 eye, Vec3 view) implements CustomPacketPayload {
   public static final Type<OperationSelectPointPayload> TYPE = new Type<>(
      ResourceLocation.fromNamespaceAndPath("fastformer", "operation_select_point")
   );
   public static final StreamCodec<FriendlyByteBuf, OperationSelectPointPayload> STREAM_CODEC = CustomPacketPayload.codec(
      OperationSelectPointPayload::write, OperationSelectPointPayload::new
   );

   public OperationSelectPointPayload {
      if (requestId <= 0 || revision < 0 || callbackScope == null || eye == null || view == null) throw new IllegalArgumentException("Invalid operation point selection");
   }
   private OperationSelectPointPayload(FriendlyByteBuf buffer) {
      this(buffer.readVarLong(), buffer.readVarLong(), OperationCallbackScope.STREAM_CODEC.decode(buffer), buffer.readVarInt(), buffer.readVec3(), buffer.readVec3());
   }

   private void write(FriendlyByteBuf buffer) {
      buffer.writeVarLong(requestId); buffer.writeVarLong(revision); OperationCallbackScope.STREAM_CODEC.encode(buffer, callbackScope); buffer.writeVarInt(index); buffer.writeVec3(eye); buffer.writeVec3(view);
   }

   @Override
   public Type<OperationSelectPointPayload> type() {
      return TYPE;
   }
}
