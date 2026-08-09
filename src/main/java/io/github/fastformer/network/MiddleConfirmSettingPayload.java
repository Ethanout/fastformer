package io.github.fastformer.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record MiddleConfirmSettingPayload(boolean enabled) implements CustomPacketPayload {
   public static final Type<MiddleConfirmSettingPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("fastformer", "middle_confirm_setting"));
   public static final StreamCodec<FriendlyByteBuf, MiddleConfirmSettingPayload> STREAM_CODEC = CustomPacketPayload.codec(
      MiddleConfirmSettingPayload::write, MiddleConfirmSettingPayload::new
   );

   private MiddleConfirmSettingPayload(FriendlyByteBuf buffer) {
      this(buffer.readBoolean());
   }

   private void write(FriendlyByteBuf buffer) {
      buffer.writeBoolean(this.enabled);
   }

   @Override
   public Type<MiddleConfirmSettingPayload> type() {
      return TYPE;
   }
}
