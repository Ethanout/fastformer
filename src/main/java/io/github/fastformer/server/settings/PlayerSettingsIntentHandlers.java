package io.github.fastformer.server.settings;

import io.github.fastformer.core.command.IntentBus;
import io.github.fastformer.core.command.IntentResult;
import io.github.fastformer.core.gate.Gate;
import io.github.fastformer.fastplace.settings.FastPlaceSettings;
import io.github.fastformer.fastplace.text.FastPlaceMessages;
import io.github.fastformer.fastplace.world.BlockActivityRules;
import io.github.fastformer.network.FastPlaceNetwork;
import io.github.fastformer.protocol.s2c.settings.OpenSettingsPayload;
import io.github.fastformer.server.session.SessionSystem;
import io.github.fastformer.server.sync.PreviewSync;
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
         FastPlaceMessages.chat(player, FastPlaceMessages.text("fastformer.message.fill_mode_status", FastPlaceMessages.text(settings.fillMode())));
         return IntentResult.success();
      });
      bus.register(PlayerSettingsIntents.MaxPlacement.class, Gate.require("settings", context -> true), (context, intent) -> {
         var player = context.actor();
         var settings = FastPlaceSettings.load(player);
         intent.value().ifPresent(value -> settings.setMaxPlacement(player, value));
         FastPlaceMessages.chat(player, FastPlaceMessages.text(intent.value().isPresent()
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
         FastPlaceNetwork.syncSettings(player);
         FastPlaceMessages.chat(player, FastPlaceMessages.text(settings.enabled() ? "fastformer.message.enabled" : "fastformer.message.disabled"));
         return IntentResult.success();
      });
      bus.register(PlayerSettingsIntents.Status.class, Gate.require("settings", context -> true), (context, intent) -> {
         var player = context.actor();
         var settings = FastPlaceSettings.load(player);
         FastPlaceMessages.chat(player, FastPlaceMessages.text("fastformer.message.status",
            FastPlaceMessages.text(settings.enabled() ? "fastformer.message.enabled" : "fastformer.message.disabled"),
            FastPlaceMessages.text(settings.fillMode()), settings.maxPlacement()));
         return IntentResult.success();
      });
      bus.register(PlayerSettingsIntents.OpenScreen.class, Gate.require("settings", context -> true), (context, intent) -> openScreen(context.actor()));
   }

   private static IntentResult openScreen(ServerPlayer player) {
      if (!player.connection.hasChannel(OpenSettingsPayload.TYPE)) {
         FastPlaceMessages.chat(player, FastPlaceMessages.text("fastformer.message.settings_client_unavailable"));
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
