package io.github.fastformer.network;

import io.github.fastformer.fastplace.geometry.GeometryInteractionAction;
import io.github.fastformer.fastplace.geometry.GeometryInteractionTarget;
import io.github.fastformer.fastplace.geometry.PointerGesture;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record GeometryInteractionPayload(
   GeometryInteractionTarget.TargetType targetType,
   int index,
   GeometryInteractionAction action,
   PointerGesture gesture
) implements CustomPacketPayload {
   public static final Type<GeometryInteractionPayload> TYPE = new Type<>(
      ResourceLocation.fromNamespaceAndPath("fastformer", "geometry_interaction")
   );
   public static final StreamCodec<FriendlyByteBuf, GeometryInteractionPayload> STREAM_CODEC = CustomPacketPayload.codec(
      GeometryInteractionPayload::write,
      GeometryInteractionPayload::new
   );

   public GeometryInteractionPayload {
      targetType = targetType == null ? GeometryInteractionTarget.TargetType.CONTROL_POINT : targetType;
      index = Math.clamp(index, -1, 1024);
      action = action == null ? GeometryInteractionAction.CLEAR_SELECTION : action;
      gesture = gesture == null ? PointerGesture.LEFT_CLICK : gesture;
   }

   public static GeometryInteractionPayload clearSelection(PointerGesture gesture) {
      return new GeometryInteractionPayload(
         GeometryInteractionTarget.TargetType.CONTROL_POINT,
         -1,
         GeometryInteractionAction.CLEAR_SELECTION,
         gesture
      );
   }

   private GeometryInteractionPayload(FriendlyByteBuf buffer) {
      this(
         buffer.readEnum(GeometryInteractionTarget.TargetType.class),
         buffer.readVarInt(),
         buffer.readEnum(GeometryInteractionAction.class),
         buffer.readEnum(PointerGesture.class)
      );
   }

   private void write(FriendlyByteBuf buffer) {
      buffer.writeEnum(this.targetType);
      buffer.writeVarInt(this.index);
      buffer.writeEnum(this.action);
      buffer.writeEnum(this.gesture);
   }

   @Override
   public Type<GeometryInteractionPayload> type() {
      return TYPE;
   }
}
