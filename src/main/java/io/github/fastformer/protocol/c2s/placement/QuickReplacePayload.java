package io.github.fastformer.protocol.c2s.placement;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** A sample or physical release of one held replacement stroke. */
public record QuickReplacePayload(long strokeId, boolean finished) implements CustomPacketPayload {
   public static final Type<QuickReplacePayload> TYPE = new Type<>(
      ResourceLocation.fromNamespaceAndPath("fastformer", "quick_replace")
   );
   public static final StreamCodec<FriendlyByteBuf, QuickReplacePayload> STREAM_CODEC = CustomPacketPayload.codec(
      QuickReplacePayload::write, QuickReplacePayload::new
   );

   private QuickReplacePayload(FriendlyByteBuf buffer) {
      this(buffer.readVarLong(), buffer.readBoolean());
   }

   private void write(FriendlyByteBuf buffer) {
      buffer.writeVarLong(strokeId);
      buffer.writeBoolean(finished);
   }

   @Override
   public Type<QuickReplacePayload> type() {
      return TYPE;
   }
}
