package io.github.fastformer.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type;
import net.minecraft.resources.ResourceLocation;

public record ModifierStatePayload(boolean down) implements CustomPacketPayload {
   public static final Type<ModifierStatePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("fastformer", "modifier_state"));
   public static final StreamCodec<FriendlyByteBuf, ModifierStatePayload> STREAM_CODEC = CustomPacketPayload.codec(
      ModifierStatePayload::write, ModifierStatePayload::new
   );

   private ModifierStatePayload(FriendlyByteBuf buffer) {
      this(buffer.readBoolean());
   }

   private void write(FriendlyByteBuf buffer) {
      buffer.writeBoolean(this.down);
   }

   @Override
   public Type<ModifierStatePayload> type() {
      return TYPE;
   }
}
