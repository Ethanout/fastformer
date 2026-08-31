package io.github.fastformer.network.payload.geometry;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.BlockPos;

public record CycleStageModePayload(boolean hasCandidate, BlockPos candidate) implements CustomPacketPayload {
   public static final Type<CycleStageModePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("fastformer", "cycle_stage_mode"));
   public static final CycleStageModePayload INSTANCE = new CycleStageModePayload(false, BlockPos.ZERO);
   public static final StreamCodec<FriendlyByteBuf, CycleStageModePayload> STREAM_CODEC = CustomPacketPayload.codec(
      CycleStageModePayload::write, CycleStageModePayload::new
   );

   private CycleStageModePayload(FriendlyByteBuf buffer) {
      this(buffer.readBoolean(), buffer.readBlockPos());
   }

   private void write(FriendlyByteBuf buffer) {
      buffer.writeBoolean(this.hasCandidate);
      buffer.writeBlockPos(this.candidate);
   }

   public Type<CycleStageModePayload> type() {
      return TYPE;
   }
}
