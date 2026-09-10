package io.github.fastformer.network.payload.settings;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Changes one registered placement effect without adding a packet per effect. */
public record PlacementEffectSettingPayload(ResourceLocation effectId, boolean enabled)
   implements CustomPacketPayload {
   public static final Type<PlacementEffectSettingPayload> TYPE = new Type<>(
      ResourceLocation.fromNamespaceAndPath("fastformer", "placement_effect_setting")
   );
   public static final StreamCodec<FriendlyByteBuf, PlacementEffectSettingPayload> STREAM_CODEC =
      CustomPacketPayload.codec(PlacementEffectSettingPayload::write, PlacementEffectSettingPayload::new);

   public PlacementEffectSettingPayload {
      if (effectId == null) {
         throw new IllegalArgumentException("effectId is required");
      }
   }

   private PlacementEffectSettingPayload(FriendlyByteBuf buffer) {
      this(ResourceLocation.parse(buffer.readUtf(128)), buffer.readBoolean());
   }

   private void write(FriendlyByteBuf buffer) {
      buffer.writeUtf(effectId.toString(), 128);
      buffer.writeBoolean(enabled);
   }

   @Override
   public Type<PlacementEffectSettingPayload> type() {
      return TYPE;
   }
}
