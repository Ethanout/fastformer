package io.github.fastformer.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record GeometryRemovePointPayload(BlockPos point) implements CustomPacketPayload {
   public static final Type<GeometryRemovePointPayload> TYPE = new Type<>(
      ResourceLocation.fromNamespaceAndPath("fastformer", "geometry_remove_point")
   );
   public static final StreamCodec<FriendlyByteBuf, GeometryRemovePointPayload> STREAM_CODEC = CustomPacketPayload.codec(
      GeometryRemovePointPayload::write, GeometryRemovePointPayload::new
   );

   private GeometryRemovePointPayload(FriendlyByteBuf buffer) {
      this(buffer.readBlockPos());
   }

   private void write(FriendlyByteBuf buffer) {
      buffer.writeBlockPos(this.point);
   }

   @Override
   public Type<GeometryRemovePointPayload> type() {
      return TYPE;
   }
}
