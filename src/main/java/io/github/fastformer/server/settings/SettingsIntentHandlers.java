package io.github.fastformer.server.settings;

import io.github.fastformer.core.command.IntentBus;
import io.github.fastformer.core.command.IntentResult;
import io.github.fastformer.core.gate.Gate;
import io.github.fastformer.fastplace.placement.effect.PlacementEffectRegistry;
import io.github.fastformer.fastplace.settings.FastPlaceSettings;
import io.github.fastformer.fastplace.world.BlockActivityRules;
import io.github.fastformer.protocol.s2c.settings.FallingStatePayload;
import io.github.fastformer.server.sync.PreviewSync;
import io.github.fastformer.protocol.settings.SettingKey;
import io.github.fastformer.protocol.settings.SettingRequest;
import io.github.fastformer.protocol.settings.SettingsKeys;
import io.github.fastformer.common.history.CompressionSetting;
import io.github.fastformer.server.session.FastPlaceManager;
import java.util.Optional;
import java.util.function.BiConsumer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/** Owns setting mutation and feedback; wire declarations contain no server callbacks. */
public final class SettingsIntentHandlers {
   private static final SettingBindings<ServerPlayer> BINDINGS = SettingBindings.create(bindings -> {
      update(bindings, SettingsKeys.MIDDLE_CONFIRM, (player, value) -> FastPlaceSettings.load(player).setMiddleConfirmEnabled(player, value));
      update(bindings, SettingsKeys.PLACEMENT_CONFLICT, (player, value) -> FastPlaceSettings.load(player).setPlacementConflictMode(player, value));
      update(bindings, SettingsKeys.PLACEMENT_UPDATE, (player, value) -> FastPlaceSettings.load(player).setPlacementUpdateMode(player, value));
      update(bindings, SettingsKeys.EMPTY_HAND_WRENCH, (player, value) -> FastPlaceSettings.load(player).setEmptyHandWrench(player, value));
      update(bindings, SettingsKeys.VANILLA_BLOCK_HISTORY, (player, value) -> FastPlaceSettings.load(player).setVanillaBlockHistory(player, value));
      update(bindings, SettingsKeys.SKIP_REPEATED_CROP_STAGES, (player, value) -> {
         FastPlaceSettings.load(player).setSkipRepeatedCropStages(player, value);
         PreviewSync.syncInteractionUpdates(player);
      });
      update(bindings, SettingsKeys.WORLD_HISTORY_LIMIT, (player, value) -> FastPlaceSettings.load(player).setWorldUndoHistoryLimit(player, value));
      update(bindings, SettingsKeys.SESSION_HISTORY_LIMIT, (player, value) -> FastPlaceSettings.load(player).setSessionUndoHistoryLimit(player, value));
      compression(bindings, SettingsKeys.RECENT_AFTER, CompressionSetting.RECENT_AFTER);
      compression(bindings, SettingsKeys.RECENT_GROUP, CompressionSetting.RECENT_GROUP);
      compression(bindings, SettingsKeys.OLDER_AFTER, CompressionSetting.OLDER_AFTER);
      compression(bindings, SettingsKeys.OLDER_GROUP, CompressionSetting.OLDER_GROUP);
      compression(bindings, SettingsKeys.OLDEST_AFTER, CompressionSetting.OLDEST_AFTER);
      compression(bindings, SettingsKeys.OLDEST_GROUP, CompressionSetting.OLDEST_GROUP);
      bindings.register(SettingsKeys.PLACEMENT_EFFECT, (player, value) -> {
         if (value.isEmpty() || !PlacementEffectRegistry.contains(value.orElseThrow().effect())) return IntentResult.pass();
         var effect = value.orElseThrow();
         FastPlaceSettings.load(player).setPlacementEffectEnabled(player, effect.effect(), effect.enabled());
         FastPlaceManager.syncCurrentPreview(player);
         return IntentResult.success();
      });
      bindings.register(SettingsKeys.FALLING_DISABLED, SettingsIntentHandlers::falling);
   });

   private SettingsIntentHandlers() {}

   public static void register(IntentBus<ServerPlayer> bus) {
      bus.register(SettingRequest.class, Gate.require("settings", context -> true),
         (context, request) -> BINDINGS.apply(context.actor(), request));
   }

   private static <T> void update(SettingBindings<ServerPlayer> bindings, SettingKey<T> key, BiConsumer<ServerPlayer, T> setter) {
      write(bindings, key, (player, value) -> {
         setter.accept(player, value);
         FastPlaceManager.syncCurrentPreview(player);
      });
   }

   private static <T> void write(SettingBindings<ServerPlayer> bindings, SettingKey<T> key, BiConsumer<ServerPlayer, T> setter) {
      bindings.register(key, (player, value) -> {
         if (value.isEmpty()) return IntentResult.pass();
         setter.accept(player, value.orElseThrow());
         return IntentResult.success();
      });
   }

   private static void compression(SettingBindings<ServerPlayer> bindings, SettingKey<Integer> key, CompressionSetting setting) {
      write(bindings, key, (player, value) -> FastPlaceSettings.load(player).setTinkerHistoryMergeLimit(player, setting, value));
   }

   private static IntentResult falling(ServerPlayer player, Optional<Boolean> value) {
      boolean allowed = player.hasPermissions(2);
      if (value.isPresent()) {
         if (allowed) BlockActivityRules.get(player.getServer()).setFallingDisabled(value.orElseThrow());
         else player.displayClientMessage(Component.translatable("commands.generic.permission"), false);
      }
      if (player.connection.hasChannel(FallingStatePayload.TYPE)) {
         PacketDistributor.sendToPlayer(player, new FallingStatePayload(
            BlockActivityRules.get(player.getServer()).fallingDisabled(), allowed));
      }
      FastPlaceManager.syncCurrentPreview(player);
      return value.isPresent() && !allowed ? IntentResult.denied("permission") : IntentResult.success();
   }
}
