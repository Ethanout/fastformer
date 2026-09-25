package io.github.fastformer.network.payload.geometry;

import io.github.fastformer.network.payload.operation.OperationCallbackScope;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record GeometryGizmoDragPayload(long requestId, long revision, OperationCallbackScope callbackScope,
   UUID draftId, int operation, int axis, int steps, boolean finish) implements CustomPacketPayload {
   public static final Type<GeometryGizmoDragPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("fastformer", "geometry_gizmo_drag"));
   public static final StreamCodec<FriendlyByteBuf, GeometryGizmoDragPayload> STREAM_CODEC = CustomPacketPayload.codec(
      GeometryGizmoDragPayload::write, GeometryGizmoDragPayload::new
   );

   public GeometryGizmoDragPayload {
      Objects.requireNonNull(callbackScope);
      Objects.requireNonNull(draftId);
      if (requestId <= 0L || revision <= 0L || callbackScope.equals(OperationCallbackScope.unscoped())
         || draftId.equals(new UUID(0L, 0L))) {
         throw new IllegalArgumentException("Invalid geometry drag snapshot");
      }
   }

   private GeometryGizmoDragPayload(FriendlyByteBuf buffer) {
      this(buffer.readVarLong(), buffer.readVarLong(), OperationCallbackScope.STREAM_CODEC.decode(buffer),
         buffer.readUUID(), buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt(), buffer.readBoolean());
   }

   private void write(FriendlyByteBuf buffer) {
      buffer.writeVarLong(requestId);
      buffer.writeVarLong(revision);
      OperationCallbackScope.STREAM_CODEC.encode(buffer, callbackScope);
      buffer.writeUUID(draftId);
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
