package io.github.fastformer.network.payload.settings;

import io.github.fastformer.fastplace.settings.ReachThresholds;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record ReachSettingsPayload(ReachThresholds thresholds) implements CustomPacketPayload {
   public static final Type<ReachSettingsPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("fastformer", "reach_settings"));
   public static final StreamCodec<FriendlyByteBuf, ReachSettingsPayload> STREAM_CODEC = StreamCodec.of(
      (buffer, value) -> { buffer.writeVarInt(value.thresholds.close()); buffer.writeVarInt(value.thresholds.far()); },
      buffer -> new ReachSettingsPayload(new ReachThresholds(buffer.readVarInt(), buffer.readVarInt())));

   @Override public Type<ReachSettingsPayload> type() { return TYPE; }
}
