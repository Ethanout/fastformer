package io.github.fastformer.protocol.c2s.settings;

import io.github.fastformer.protocol.settings.SettingRequest;
import io.github.fastformer.protocol.settings.SettingsKeys;

import java.util.Objects;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Current settings protocol. There are no aliases for the former per-setting packets. */
public record SettingPayload(SettingRequest<?> request) implements CustomPacketPayload {
   public static final Type<SettingPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("fastformer", "setting"));
   public static final StreamCodec<FriendlyByteBuf, SettingPayload> STREAM_CODEC = StreamCodec.of(
      (buffer, payload) -> SettingsKeys.CATALOG.encode(buffer, payload.request()),
      buffer -> new SettingPayload(SettingsKeys.CATALOG.decode(buffer)));

   public SettingPayload { Objects.requireNonNull(request, "request"); }
   @Override public Type<SettingPayload> type() { return TYPE; }
}
