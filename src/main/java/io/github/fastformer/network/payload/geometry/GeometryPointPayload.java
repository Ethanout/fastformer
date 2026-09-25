package io.github.fastformer.network.payload.geometry;

import io.github.fastformer.network.payload.operation.OperationCallbackScope;
import java.util.Objects;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/** Requests one authoritative remote geometry point input using the server raycast. */
public record GeometryPointPayload(long requestId, long revision, OperationCallbackScope callbackScope, Vec3 eye, Vec3 view)
   implements CustomPacketPayload {
   public static final Type<GeometryPointPayload> TYPE = new Type<>(
      ResourceLocation.fromNamespaceAndPath("fastformer", "geometry_point")
   );
   public static final StreamCodec<FriendlyByteBuf, GeometryPointPayload> STREAM_CODEC = CustomPacketPayload.codec(
      GeometryPointPayload::write, GeometryPointPayload::new
   );

   public GeometryPointPayload {
      Objects.requireNonNull(callbackScope);
      Objects.requireNonNull(eye);
      Objects.requireNonNull(view);
      if (requestId <= 0L || revision <= 0L || callbackScope.equals(OperationCallbackScope.unscoped())
         || !finite(eye) || !finite(view) || Math.abs(view.lengthSqr() - 1.0) > 0.001) {
         throw new IllegalArgumentException("Invalid geometry point snapshot");
      }
   }

   private GeometryPointPayload(FriendlyByteBuf buffer) {
      this(buffer.readVarLong(), buffer.readVarLong(), OperationCallbackScope.STREAM_CODEC.decode(buffer), buffer.readVec3(), buffer.readVec3());
   }

   private void write(FriendlyByteBuf buffer) {
      buffer.writeVarLong(requestId);
      buffer.writeVarLong(revision);
      OperationCallbackScope.STREAM_CODEC.encode(buffer, callbackScope);
      buffer.writeVec3(eye);
      buffer.writeVec3(view);
   }

   private static boolean finite(Vec3 value) {
      return Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z);
   }

   @Override
   public Type<GeometryPointPayload> type() {
      return TYPE;
   }
}
