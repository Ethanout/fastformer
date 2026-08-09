package io.github.fastformer.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record GeometryGizmoDragPayload(int operation, int axis, int steps, boolean finish) implements CustomPacketPayload {
   public static final Type<GeometryGizmoDragPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("fastformer", "geometry_gizmo_drag"));
   public static final StreamCodec<FriendlyByteBuf, GeometryGizmoDragPayload> STREAM_CODEC = CustomPacketPayload.codec(
      GeometryGizmoDragPayload::write, GeometryGizmoDragPayload::new
   );

   private GeometryGizmoDragPayload(FriendlyByteBuf buffer) {
      this(buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt(), buffer.readBoolean());
   }

   private void write(FriendlyByteBuf buffer) {
      buffer.writeVarInt(this.operation);
      buffer.writeVarInt(this.axis);
      buffer.writeVarInt(this.steps);
      buffer.writeBoolean(this.finish);
   }

   @Override
   public Type<GeometryGizmoDragPayload> type() {
      return TYPE;
   }
}
