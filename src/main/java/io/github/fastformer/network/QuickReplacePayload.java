package io.github.fastformer.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Requests one authoritative replacement at the server-side crosshair hit. */
public record QuickReplacePayload() implements CustomPacketPayload {
   public static final QuickReplacePayload INSTANCE = new QuickReplacePayload();
   public static final Type<QuickReplacePayload> TYPE = new Type<>(
      ResourceLocation.fromNamespaceAndPath("fastformer", "quick_replace")
   );
   public static final StreamCodec<FriendlyByteBuf, QuickReplacePayload> STREAM_CODEC = CustomPacketPayload.codec(
      QuickReplacePayload::write, QuickReplacePayload::new
   );

   private QuickReplacePayload(FriendlyByteBuf ignored) {
      this();
   }

   private void write(FriendlyByteBuf ignored) {
   }

   @Override
   public Type<QuickReplacePayload> type() {
      return TYPE;
   }
}
