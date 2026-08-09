package io.github.fastformer.network;

import io.github.fastformer.fastplace.GeometryMode;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record GeometrySelectModePayload(GeometryMode mode) implements CustomPacketPayload {
   public static final Type<GeometrySelectModePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("fastformer", "geometry_select_mode"));
   public static final StreamCodec<FriendlyByteBuf, GeometrySelectModePayload> STREAM_CODEC = CustomPacketPayload.codec(
      GeometrySelectModePayload::write, GeometrySelectModePayload::new
   );

   private GeometrySelectModePayload(FriendlyByteBuf buffer) {
      this(buffer.readEnum(GeometryMode.class));
   }

   private void write(FriendlyByteBuf buffer) {
      buffer.writeEnum(this.mode);
   }

   @Override
   public Type<GeometrySelectModePayload> type() {
      return TYPE;
   }
}
