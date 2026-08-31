package io.github.fastformer.network.payload.settings;

import io.github.fastformer.fastplace.FaceRasterizationMode;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record FaceRasterizationSettingPayload(FaceRasterizationMode mode) implements CustomPacketPayload {
   public static final Type<FaceRasterizationSettingPayload> TYPE = new Type<>(
      ResourceLocation.fromNamespaceAndPath("fastformer", "face_rasterization_setting")
   );
   public static final StreamCodec<FriendlyByteBuf, FaceRasterizationSettingPayload> STREAM_CODEC =
      CustomPacketPayload.codec(FaceRasterizationSettingPayload::write, FaceRasterizationSettingPayload::new);

   public FaceRasterizationSettingPayload {
      mode = mode == null ? FaceRasterizationMode.POINT_SWEEP : mode;
   }

   private FaceRasterizationSettingPayload(FriendlyByteBuf buffer) {
      this(buffer.readEnum(FaceRasterizationMode.class));
   }

   private void write(FriendlyByteBuf buffer) {
      buffer.writeEnum(this.mode);
   }

   @Override
   public Type<FaceRasterizationSettingPayload> type() {
      return TYPE;
   }
}
