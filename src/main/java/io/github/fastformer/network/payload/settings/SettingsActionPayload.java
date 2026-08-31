package io.github.fastformer.network.payload.settings;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record SettingsActionPayload(Action action) implements CustomPacketPayload {
   public static final Type<SettingsActionPayload> TYPE = new Type<>(
      ResourceLocation.fromNamespaceAndPath("fastformer", "settings_action")
   );
   public static final StreamCodec<FriendlyByteBuf, SettingsActionPayload> STREAM_CODEC = CustomPacketPayload.codec(
      SettingsActionPayload::write, SettingsActionPayload::new
   );

   public SettingsActionPayload {
      action = action == null ? Action.CYCLE_RAYCAST_PLACEMENT : action;
   }

   private SettingsActionPayload(FriendlyByteBuf buffer) {
      this(buffer.readEnum(Action.class));
   }

   private void write(FriendlyByteBuf buffer) {
      buffer.writeEnum(this.action);
   }

   @Override
   public Type<SettingsActionPayload> type() {
      return TYPE;
   }

   public enum Action {
      CYCLE_RAYCAST_PLACEMENT,
      CYCLE_PLACEMENT_CONFLICT,
      CYCLE_PLACEMENT_UPDATE,
      TOGGLE_SMART_WOOD_FRAME,
      TOGGLE_EMPTY_HAND_WRENCH,
      TOGGLE_GLOBAL_FREEZE,
      DECREASE_WORLD_HISTORY,
      INCREASE_WORLD_HISTORY,
      DECREASE_SESSION_HISTORY,
      INCREASE_SESSION_HISTORY
   }
}
