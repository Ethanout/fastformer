package io.github.fastformer.network.payload.geometry;

import io.github.fastformer.fastplace.geometry.GeometryInteractionAction;
import io.github.fastformer.fastplace.geometry.GeometryInteractionTarget;
import io.github.fastformer.fastplace.geometry.PointerGesture;
import io.github.fastformer.network.payload.operation.OperationCallbackScope;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

public record GeometryInteractionPayload(
   long requestId,
   long revision,
   OperationCallbackScope callbackScope,
   GeometryInteractionTarget.TargetType targetType,
   int index,
   GeometryInteractionAction action,
   PointerGesture gesture,
   Vec3 eye,
   Vec3 view
) implements CustomPacketPayload {
   public static final Type<GeometryInteractionPayload> TYPE = new Type<>(
      ResourceLocation.fromNamespaceAndPath("fastformer", "geometry_interaction")
   );
   public static final StreamCodec<FriendlyByteBuf, GeometryInteractionPayload> STREAM_CODEC = CustomPacketPayload.codec(
      GeometryInteractionPayload::write,
      GeometryInteractionPayload::new
   );

   public GeometryInteractionPayload {
      if (requestId <= 0 || revision <= 0 || callbackScope == null || callbackScope.equals(OperationCallbackScope.unscoped())) {
         throw new IllegalArgumentException("A geometry interaction requires a scoped preview revision");
      }
      if (!finite(eye) || !finite(view) || Math.abs(view.lengthSqr() - 1.0) > 0.001) {
         throw new IllegalArgumentException("A geometry interaction requires a finite normalized ray");
      }
      targetType = targetType == null ? GeometryInteractionTarget.TargetType.CONTROL_POINT : targetType;
      index = Math.clamp(index, -1, 1024);
      action = action == null ? GeometryInteractionAction.CLEAR_SELECTION : action;
      gesture = gesture == null ? PointerGesture.LEFT_CLICK : gesture;
   }

   public static GeometryInteractionPayload clearSelection(long requestId, long revision, OperationCallbackScope scope,
      PointerGesture gesture, Vec3 eye, Vec3 view) {
      return new GeometryInteractionPayload(
         requestId,
         revision,
         scope,
         GeometryInteractionTarget.TargetType.CONTROL_POINT,
         -1,
         GeometryInteractionAction.CLEAR_SELECTION,
         gesture, eye, view
      );
   }

   private GeometryInteractionPayload(FriendlyByteBuf buffer) {
      this(
         buffer.readVarLong(),
         buffer.readVarLong(),
         OperationCallbackScope.STREAM_CODEC.decode(buffer),
         buffer.readEnum(GeometryInteractionTarget.TargetType.class),
         buffer.readVarInt(),
         buffer.readEnum(GeometryInteractionAction.class),
         buffer.readEnum(PointerGesture.class),
         buffer.readVec3(), buffer.readVec3()
      );
   }

   private void write(FriendlyByteBuf buffer) {
      buffer.writeVarLong(this.requestId);
      buffer.writeVarLong(this.revision);
      OperationCallbackScope.STREAM_CODEC.encode(buffer, this.callbackScope);
      buffer.writeEnum(this.targetType);
      buffer.writeVarInt(this.index);
      buffer.writeEnum(this.action);
      buffer.writeEnum(this.gesture);
      buffer.writeVec3(this.eye);
      buffer.writeVec3(this.view);
   }

   private static boolean finite(Vec3 value) {
      return value != null && Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z);
   }

   @Override
   public Type<GeometryInteractionPayload> type() {
      return TYPE;
   }
}
