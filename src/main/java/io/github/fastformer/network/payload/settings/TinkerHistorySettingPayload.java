package io.github.fastformer.network.payload.settings;

import io.github.fastformer.fastplace.history.TinkerHistoryMergeLimits.Setting;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record TinkerHistorySettingPayload(Setting setting, int value) implements CustomPacketPayload {
   public static final Type<TinkerHistorySettingPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("fastformer", "tinker_history_setting"));
   public static final StreamCodec<FriendlyByteBuf, TinkerHistorySettingPayload> STREAM_CODEC = CustomPacketPayload.codec(
      TinkerHistorySettingPayload::write, TinkerHistorySettingPayload::new);

   public TinkerHistorySettingPayload { java.util.Objects.requireNonNull(setting); }
   private TinkerHistorySettingPayload(FriendlyByteBuf buffer) { this(buffer.readEnum(Setting.class), buffer.readVarInt()); }
   private void write(FriendlyByteBuf buffer) { buffer.writeEnum(setting); buffer.writeVarInt(value); }
   @Override public Type<TinkerHistorySettingPayload> type() { return TYPE; }
}
