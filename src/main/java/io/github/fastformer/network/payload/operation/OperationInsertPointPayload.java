package io.github.fastformer.network.payload.operation;

import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

public record OperationInsertPointPayload(long requestId, long revision, OperationCallbackScope callbackScope, Vec3 eye, Vec3 view) implements CustomPacketPayload {
   public static final Type<OperationInsertPointPayload> TYPE = new Type<>(
      ResourceLocation.fromNamespaceAndPath("fastformer", "operation_insert_point")
   );
   public static final StreamCodec<net.minecraft.network.FriendlyByteBuf, OperationInsertPointPayload> STREAM_CODEC = CustomPacketPayload.codec(OperationInsertPointPayload::write, OperationInsertPointPayload::new);
   public OperationInsertPointPayload {
      if (requestId <= 0 || revision < 0 || callbackScope == null
         || !io.github.fastformer.network.RaySnapshotValidation.valid(eye, view)) {
         throw new IllegalArgumentException("Invalid operation edge insertion");
      }
   }
   private OperationInsertPointPayload(net.minecraft.network.FriendlyByteBuf buffer) { this(buffer.readVarLong(), buffer.readVarLong(), OperationCallbackScope.STREAM_CODEC.decode(buffer), buffer.readVec3(), buffer.readVec3()); }
   private void write(net.minecraft.network.FriendlyByteBuf buffer) { buffer.writeVarLong(requestId); buffer.writeVarLong(revision); OperationCallbackScope.STREAM_CODEC.encode(buffer, callbackScope); buffer.writeVec3(eye); buffer.writeVec3(view); }

   @Override
   public Type<OperationInsertPointPayload> type() {
      return TYPE;
   }
}
