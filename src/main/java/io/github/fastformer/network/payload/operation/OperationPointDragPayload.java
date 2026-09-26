package io.github.fastformer.network.payload.operation;

import io.github.fastformer.fastplace.selection.OperationPointDragConstraint;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.BlockPos;

public record OperationPointDragPayload(
   long gestureId,
   long revision,
   OperationCallbackScope callbackScope,
   int pointIndex,
   BlockPos target,
   OperationPointDragConstraint constraint,
   boolean finish
) implements CustomPacketPayload {
   public static final Type<OperationPointDragPayload> TYPE = new Type<>(
      ResourceLocation.fromNamespaceAndPath("fastformer", "operation_point_drag")
   );
   public static final StreamCodec<FriendlyByteBuf, OperationPointDragPayload> STREAM_CODEC = CustomPacketPayload.codec(
      OperationPointDragPayload::write, OperationPointDragPayload::new
   );

   public OperationPointDragPayload {
      if (gestureId <= 0 || revision < 0 || callbackScope == null) {
         throw new IllegalArgumentException("Operation point drag requires an owned preview gesture");
      }
      target = target == null ? BlockPos.ZERO : target.immutable();
      constraint = constraint == null ? OperationPointDragConstraint.FREE : constraint;
   }

   private OperationPointDragPayload(FriendlyByteBuf buffer) {
      this(
         buffer.readVarLong(),
         buffer.readVarLong(),
         OperationCallbackScope.STREAM_CODEC.decode(buffer),
         buffer.readVarInt(),
         buffer.readBlockPos(),
         buffer.readEnum(OperationPointDragConstraint.class),
         buffer.readBoolean()
      );
   }

   private void write(FriendlyByteBuf buffer) {
      buffer.writeVarLong(this.gestureId);
      buffer.writeVarLong(this.revision);
      OperationCallbackScope.STREAM_CODEC.encode(buffer, this.callbackScope);
      buffer.writeVarInt(this.pointIndex);
      buffer.writeBlockPos(this.target);
      buffer.writeEnum(this.constraint);
      buffer.writeBoolean(this.finish);
   }

   @Override
   public Type<OperationPointDragPayload> type() {
      return TYPE;
   }
}
