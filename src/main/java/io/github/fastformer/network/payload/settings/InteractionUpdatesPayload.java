package io.github.fastformer.network.payload.settings;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record InteractionUpdatesPayload(boolean suppressNeighbors, boolean wrenchEnabled, boolean forcePlacement) implements CustomPacketPayload {
   public static final Type<InteractionUpdatesPayload> TYPE = new Type<>(
      ResourceLocation.fromNamespaceAndPath("fastformer", "interaction_updates"));
   public static final StreamCodec<FriendlyByteBuf, InteractionUpdatesPayload> STREAM_CODEC = StreamCodec.of(
      (buffer, value) -> { buffer.writeBoolean(value.suppressNeighbors()); buffer.writeBoolean(value.wrenchEnabled()); buffer.writeBoolean(value.forcePlacement()); },
      buffer -> new InteractionUpdatesPayload(buffer.readBoolean(), buffer.readBoolean(), buffer.readBoolean()));

   @Override public Type<InteractionUpdatesPayload> type() { return TYPE; }
}
