package io.github.fastformer.network;

import io.github.fastformer.fastplace.FastPlaceActivity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record ActivityStatePayload(FastPlaceActivity activity) implements CustomPacketPayload {
   public static final Type<ActivityStatePayload> TYPE = new Type<>(
      ResourceLocation.fromNamespaceAndPath("fastformer", "activity_state")
   );
   public static final StreamCodec<FriendlyByteBuf, ActivityStatePayload> STREAM_CODEC = CustomPacketPayload.codec(
      ActivityStatePayload::write, ActivityStatePayload::new
   );

   public ActivityStatePayload {
      activity = activity == null ? FastPlaceActivity.NONE : activity;
   }

   private ActivityStatePayload(FriendlyByteBuf buffer) {
      this(buffer.readEnum(FastPlaceActivity.class));
   }

   private void write(FriendlyByteBuf buffer) {
      buffer.writeEnum(this.activity);
   }

   @Override
   public Type<ActivityStatePayload> type() {
      return TYPE;
   }
}
