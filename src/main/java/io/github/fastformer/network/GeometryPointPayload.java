package io.github.fastformer.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Requests one authoritative remote geometry point input using the server raycast. */
public record GeometryPointPayload() implements CustomPacketPayload {
   public static final GeometryPointPayload INSTANCE = new GeometryPointPayload();
   public static final Type<GeometryPointPayload> TYPE = new Type<>(
      ResourceLocation.fromNamespaceAndPath("fastformer", "geometry_point")
   );
   public static final StreamCodec<FriendlyByteBuf, GeometryPointPayload> STREAM_CODEC = StreamCodec.unit(INSTANCE);

   @Override
   public Type<GeometryPointPayload> type() {
      return TYPE;
   }
}
