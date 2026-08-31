package io.github.fastformer.network.payload.placement;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Performs the quick-shape gesture as the same right-click then Enter sequence used by normal input. */
public record QuickShapePayload() implements CustomPacketPayload {
   public static final QuickShapePayload INSTANCE = new QuickShapePayload();
   public static final Type<QuickShapePayload> TYPE = new Type<>(
      ResourceLocation.fromNamespaceAndPath("fastformer", "quick_shape")
   );
   public static final StreamCodec<FriendlyByteBuf, QuickShapePayload> STREAM_CODEC = StreamCodec.unit(INSTANCE);

   @Override
   public Type<QuickShapePayload> type() {
      return TYPE;
   }
}
