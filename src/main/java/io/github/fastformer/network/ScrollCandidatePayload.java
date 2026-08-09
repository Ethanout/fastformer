package io.github.fastformer.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type;
import net.minecraft.resources.ResourceLocation;

public record ScrollCandidatePayload(int steps) implements CustomPacketPayload {
   public static final Type<ScrollCandidatePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("fastformer", "scroll_candidate"));
   public static final StreamCodec<FriendlyByteBuf, ScrollCandidatePayload> STREAM_CODEC = CustomPacketPayload.codec(
      ScrollCandidatePayload::write, ScrollCandidatePayload::new
   );

   private ScrollCandidatePayload(FriendlyByteBuf buffer) {
      this(buffer.readVarInt());
   }

   private void write(FriendlyByteBuf buffer) {
      buffer.writeVarInt(this.steps);
   }

   public Type<ScrollCandidatePayload> type() {
      return TYPE;
   }
}
