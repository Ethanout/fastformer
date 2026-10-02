package io.github.fastformer.network.payload.settings;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record SettingsActionPayload(Action action, boolean fallingDisabled) implements CustomPacketPayload {
   public SettingsActionPayload(Action action) { this(action, false); }
   public static final Type<SettingsActionPayload> TYPE = new Type<>(
      ResourceLocation.fromNamespaceAndPath("fastformer", "settings_action")
   );
   public static final StreamCodec<FriendlyByteBuf, SettingsActionPayload> STREAM_CODEC = CustomPacketPayload.codec(
      SettingsActionPayload::write, SettingsActionPayload::new
   );

   public SettingsActionPayload {
      action = action == null ? Action.CYCLE_PLACEMENT_CONFLICT : action;
   }

   private SettingsActionPayload(FriendlyByteBuf buffer) {
      this(buffer.readEnum(Action.class), buffer.readBoolean());
   }

   private void write(FriendlyByteBuf buffer) {
      buffer.writeEnum(this.action);
      buffer.writeBoolean(this.fallingDisabled);
   }

   @Override
   public Type<SettingsActionPayload> type() {
      return TYPE;
   }

   public enum Action {
      CYCLE_PLACEMENT_CONFLICT,
      CYCLE_PLACEMENT_UPDATE,
      TOGGLE_EMPTY_HAND_WRENCH,
      TOGGLE_FALLING_DISABLED,
      DECREASE_WORLD_HISTORY,
      INCREASE_WORLD_HISTORY,
      DECREASE_SESSION_HISTORY,
      INCREASE_SESSION_HISTORY,
      QUERY_FALLING_DISABLED
   }
}
