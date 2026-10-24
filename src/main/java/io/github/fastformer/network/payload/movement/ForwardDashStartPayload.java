package io.github.fastformer.network.payload.movement;

import java.util.Objects;
import io.github.fastformer.fastplace.settings.FlightSettings;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/** Authorizes a smooth dash to a server-checked endpoint, without teleporting the camera. */
public record ForwardDashStartPayload(long requestId, ResourceLocation dimension, int entityId,
   Vec3 origin, Vec3 destination) implements CustomPacketPayload {
   public static final Type<ForwardDashStartPayload> TYPE = new Type<>(
      ResourceLocation.fromNamespaceAndPath("fastformer", "forward_dash_start"));
   public static final StreamCodec<FriendlyByteBuf, ForwardDashStartPayload> STREAM_CODEC = CustomPacketPayload.codec(
      ForwardDashStartPayload::write, ForwardDashStartPayload::read);

   public ForwardDashStartPayload {
      Objects.requireNonNull(dimension);
      Objects.requireNonNull(origin);
      Objects.requireNonNull(destination);
      if (requestId <= 0 || !finite(origin) || !finite(destination)
         || origin.distanceToSqr(destination) > (double)FlightSettings.MAX_DASH_DISTANCE * FlightSettings.MAX_DASH_DISTANCE + 0.001) {
         throw new IllegalArgumentException("Invalid dash path");
      }
   }

   private void write(FriendlyByteBuf buffer) {
      buffer.writeVarLong(requestId);
      buffer.writeResourceLocation(dimension);
      buffer.writeVarInt(entityId);
      buffer.writeVec3(origin);
      buffer.writeVec3(destination);
   }

   private static ForwardDashStartPayload read(FriendlyByteBuf buffer) {
      return new ForwardDashStartPayload(buffer.readVarLong(), buffer.readResourceLocation(), buffer.readVarInt(),
         buffer.readVec3(), buffer.readVec3());
   }

   private static boolean finite(Vec3 point) {
      return Double.isFinite(point.x) && Double.isFinite(point.y) && Double.isFinite(point.z);
   }

   @Override
   public Type<ForwardDashStartPayload> type() { return TYPE; }
}
