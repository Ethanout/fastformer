package io.github.fastformer.server.settings;

import io.github.fastformer.core.command.IntentBus;
import io.github.fastformer.core.command.IntentResult;
import io.github.fastformer.core.gate.Gate;
import io.github.fastformer.server.settings.FastPlaceSettings;
import io.github.fastformer.server.feedback.PlayerMessages;
import io.github.fastformer.server.worldwrite.effect.BlockActivityRules;
import io.github.fastformer.server.sync.PreviewSync;
import io.github.fastformer.protocol.s2c.settings.OpenSettingsPayload;
import io.github.fastformer.server.session.SessionSystem;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/** Owns local player settings, screen data assembly and their existing feedback. */
public final class PlayerSettingsIntentHandlers {
   private PlayerSettingsIntentHandlers() {}

   public static void register(IntentBus<ServerPlayer> bus) {
      bus.register(PlayerSettingsIntents.Fill.class, Gate.require("settings", context -> true), (context, intent) -> {
         var player = context.actor();
         var settings = FastPlaceSettings.load(player);
         intent.value().ifPresent(value -> {
            settings.setFillMode(player, value);
            SessionSystem.of(player).syncCurrentPreview(player);
         });
         PlayerMessages.chat(player, PlayerMessages.text("fastformer.message.fill_mode_status", PlayerMessages.text(settings.fillMode())));
         return IntentResult.success();
      });
      bus.register(PlayerSettingsIntents.MaxPlacement.class, Gate.require("settings", context -> true), (context, intent) -> {
         var player = context.actor();
         var settings = FastPlaceSettings.load(player);
         intent.value().ifPresent(value -> settings.setMaxPlacement(player, value));
         PlayerMessages.chat(player, PlayerMessages.text(intent.value().isPresent()
            ? "fastformer.message.max_placement" : "fastformer.message.max_placement_status", settings.maxPlacement()));
         return IntentResult.success();
      });
      bus.register(PlayerSettingsIntents.ForcePlacement.class, Gate.require("settings", context -> true), (context, intent) -> {
         var player = context.actor();
         FastPlaceSettings.load(player).setForcePlacement(player, intent.enabled());
         PreviewSync.syncInteractionUpdates(player);
         return IntentResult.success();
      });
      bus.register(PlayerSettingsIntents.Toggle.class, Gate.require("settings", context -> true), (context, intent) -> {
         var player = context.actor();
         var settings = FastPlaceSettings.load(player);
         settings.toggleEnabled(player);
         if (settings.enabled()) SessionSystem.of(player).cancelEditing(player);
         else SessionSystem.of(player).stopUnavailable(player);
         PreviewSync.syncSettings(player);
         PlayerMessages.chat(player, PlayerMessages.text(settings.enabled() ? "fastformer.message.enabled" : "fastformer.message.disabled"));
         return IntentResult.success();
      });
      bus.register(PlayerSettingsIntents.Status.class, Gate.require("settings", context -> true), (context, intent) -> {
         var player = context.actor();
         var settings = FastPlaceSettings.load(player);
         PlayerMessages.chat(player, PlayerMessages.text("fastformer.message.status",
            PlayerMessages.text(settings.enabled() ? "fastformer.message.enabled" : "fastformer.message.disabled"),
            PlayerMessages.text(settings.fillMode()), settings.maxPlacement()));
         return IntentResult.success();
      });
      bus.register(PlayerSettingsIntents.OpenScreen.class, Gate.require("settings", context -> true), (context, intent) -> openScreen(context.actor()));
   }

   private static IntentResult openScreen(ServerPlayer player) {
      if (!player.connection.hasChannel(OpenSettingsPayload.TYPE)) {
         PlayerMessages.chat(player, PlayerMessages.text("fastformer.message.settings_client_unavailable"));
         return IntentResult.pass();
      }
      var settings = FastPlaceSettings.load(player);
      PacketDistributor.sendToPlayer(player, new OpenSettingsPayload(
         settings.middleConfirmEnabled(), settings.placementConflictMode(), settings.placementUpdateMode(),
         settings.enabledPlacementEffects().stream().sorted().toList(), settings.emptyHandWrench(),
         BlockActivityRules.get(player.getServer()).fallingDisabled(), settings.worldUndoHistoryLimit(),
         settings.sessionUndoHistoryLimit(), settings.tinkerHistoryMergeLimits(),
         settings.skipRepeatedCropStages(), settings.vanillaBlockHistory()));
      return IntentResult.success();
   }
}
