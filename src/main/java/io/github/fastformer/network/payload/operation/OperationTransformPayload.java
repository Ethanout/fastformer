package io.github.fastformer.network.payload.operation;

import io.github.fastformer.fastplace.geometry.AxisGizmo;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record OperationTransformPayload(
   int operation, int axis, int direction, int totalSteps, boolean finish
) implements CustomPacketPayload {
   public static final Type<OperationTransformPayload> TYPE = new Type<>(
      ResourceLocation.fromNamespaceAndPath("fastformer", "operation_transform")
   );
   public static final StreamCodec<FriendlyByteBuf, OperationTransformPayload> STREAM_CODEC = CustomPacketPayload.codec(
      OperationTransformPayload::write, OperationTransformPayload::new
   );

   private OperationTransformPayload(FriendlyByteBuf buffer) {
      this(buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt(), buffer.readBoolean());
   }

   private void write(FriendlyByteBuf buffer) {
      buffer.writeVarInt(this.operation);
      buffer.writeVarInt(this.axis);
      buffer.writeVarInt(this.direction);
      buffer.writeVarInt(this.totalSteps);
      buffer.writeBoolean(this.finish);
   }

   public boolean valid() {
      return this.operation >= 0 && this.operation < AxisGizmo.Operation.values().length
         && this.axis >= 0 && this.axis < AxisGizmo.Axis.values().length
         && this.direction >= -1 && this.direction <= 1
         && this.totalSteps >= -128 && this.totalSteps <= 128
         && (this.operation == AxisGizmo.Operation.ROTATE.ordinal() || this.direction != 0);
   }

   @Override
   public Type<OperationTransformPayload> type() {
      return TYPE;
   }
}
