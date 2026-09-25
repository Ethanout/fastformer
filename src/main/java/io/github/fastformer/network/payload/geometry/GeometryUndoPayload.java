package io.github.fastformer.network.payload.geometry;

import io.github.fastformer.network.payload.operation.OperationCallbackScope;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/** Requests one captured geometry-session undo using the server-side session ledger. */
public record GeometryUndoPayload(
   long requestId,
   long revision,
   OperationCallbackScope callbackScope,
   UUID draftId,
   Vec3 eye,
   Vec3 view
) implements CustomPacketPayload {
   public static final Type<GeometryUndoPayload> TYPE = new Type<>(
      ResourceLocation.fromNamespaceAndPath("fastformer", "geometry_undo")
   );
   public static final StreamCodec<FriendlyByteBuf, GeometryUndoPayload> STREAM_CODEC = CustomPacketPayload.codec(
      GeometryUndoPayload::write, GeometryUndoPayload::new
   );

   public GeometryUndoPayload {
      Objects.requireNonNull(callbackScope);
      Objects.requireNonNull(draftId);
      Objects.requireNonNull(eye);
      Objects.requireNonNull(view);
      if (requestId <= 0L || revision <= 0L || callbackScope.equals(OperationCallbackScope.unscoped())
         || draftId.equals(new UUID(0L, 0L)) || !finite(eye) || !finite(view)
         || Math.abs(view.lengthSqr() - 1.0) > 0.001) {
         throw new IllegalArgumentException("Invalid geometry undo snapshot");
      }
   }

   private GeometryUndoPayload(FriendlyByteBuf buffer) {
      this(buffer.readVarLong(), buffer.readVarLong(), OperationCallbackScope.STREAM_CODEC.decode(buffer),
         buffer.readUUID(), buffer.readVec3(), buffer.readVec3());
   }

   private void write(FriendlyByteBuf buffer) {
      buffer.writeVarLong(requestId);
      buffer.writeVarLong(revision);
      OperationCallbackScope.STREAM_CODEC.encode(buffer, callbackScope);
      buffer.writeUUID(draftId);
      buffer.writeVec3(eye);
      buffer.writeVec3(view);
   }

   private static boolean finite(Vec3 value) {
      return Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z);
   }

   @Override
   public Type<GeometryUndoPayload> type() {
      return TYPE;
   }
}
