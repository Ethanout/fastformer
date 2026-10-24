package io.github.fastformer.network.payload.movement;

import io.github.fastformer.fastplace.settings.FlightSettings;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Requests a bounded distance; the server owns direction and collision checks. */
public record ForwardDashPayload(long requestId, int distance) implements CustomPacketPayload {
   public static final Type<ForwardDashPayload> TYPE = new Type<>(
      ResourceLocation.fromNamespaceAndPath("fastformer", "forward_dash"));
   public static final StreamCodec<FriendlyByteBuf, ForwardDashPayload> STREAM_CODEC = CustomPacketPayload.codec(
      (payload, buffer) -> { buffer.writeVarLong(payload.requestId()); buffer.writeVarInt(payload.distance()); },
      buffer -> new ForwardDashPayload(buffer.readVarLong(), buffer.readVarInt()));

   public ForwardDashPayload {
      if (requestId <= 0) throw new IllegalArgumentException("Dash request id must be positive");
      if (distance < 0 || distance > FlightSettings.MAX_DASH_DISTANCE) throw new IllegalArgumentException("Invalid dash distance");
   }

   @Override
   public Type<ForwardDashPayload> type() { return TYPE; }
}
