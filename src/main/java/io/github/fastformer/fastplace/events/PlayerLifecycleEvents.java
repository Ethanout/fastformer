package io.github.fastformer.fastplace.events;

import io.github.fastformer.fastplace.FastPlaceManager;
import io.github.fastformer.fastplace.FastPlaceMessages;
import io.github.fastformer.fastplace.FastPlaceSettings;
import io.github.fastformer.fastplace.world.PersistentRecoveryJournal;
import io.github.fastformer.fastplace.world.WorldHistoryManager;
import io.github.fastformer.fastplace.world.WorldTaskFeature;
import io.github.fastformer.fastplace.world.WorldWriteCoordinator;
import io.github.fastformer.network.FastPlaceNetwork;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent.Clone;
import net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerChangedDimensionEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

/** Registers player and server lifecycle boundaries. */
public final class PlayerLifecycleEvents {
   private PlayerLifecycleEvents() {
   }

   public static void register() {
      NeoForge.EVENT_BUS.addListener(PlayerLifecycleEvents::onPlayerClone);
      NeoForge.EVENT_BUS.addListener(PlayerLifecycleEvents::onPlayerChangedDimension);
      NeoForge.EVENT_BUS.addListener(PlayerLifecycleEvents::onPlayerLogin);
      NeoForge.EVENT_BUS.addListener(PlayerLifecycleEvents::onPlayerLogout);
      NeoForge.EVENT_BUS.addListener(PlayerLifecycleEvents::onServerStarted);
      NeoForge.EVENT_BUS.addListener(PlayerLifecycleEvents::onServerStopping);
      NeoForge.EVENT_BUS.addListener(PlayerLifecycleEvents::onServerStopped);
      NeoForge.EVENT_BUS.addListener(PlayerLifecycleEvents::onLevelSave);
   }

   private static void onPlayerClone(Clone event) {
      if (event.getOriginal() instanceof ServerPlayer oldPlayer
         && event.getEntity() instanceof ServerPlayer newPlayer) {
         FastPlaceSettings.copy(oldPlayer, newPlayer);
         // Rebind the retained UUID-owned workflow to the replacement instance.
         FastPlaceManager.syncCurrentPreview(newPlayer);
      }
   }

   private static void onPlayerChangedDimension(PlayerChangedDimensionEvent event) {
      if (event.getEntity() instanceof ServerPlayer player) {
         // The player instance survives a dimension change, but the client
         // still needs a fresh authoritative snapshot for the retained UUID box.
         FastPlaceManager.syncCurrentPreview(player);
      }
   }

   private static void onPlayerLogin(PlayerLoggedInEvent event) {
      if (!(event.getEntity() instanceof ServerPlayer player)) {
         return;
      }
      FastPlaceNetwork.syncSettings(player);
      // Reattach UUID-owned workflows after a reconnect or player replacement.
      FastPlaceManager.syncCurrentPreview(player);
      WorldHistoryManager.trimToSetting(player, FastPlaceSettings.load(player).worldUndoHistoryLimit());
      WorldTaskFeature.attachPlayer(player);
      if (!PersistentRecoveryJournal.writesAllowed()) {
         FastPlaceMessages.chat(player, FastPlaceMessages.text("fastformer.message.recovery_journal_blocked"));
      }
   }

   private static void onPlayerLogout(PlayerLoggedOutEvent event) {
      if (event.getEntity() instanceof ServerPlayer player) {
         FastPlaceManager.detachPlayer(player);
      }
   }

   private static void onServerStarted(ServerStartedEvent event) {
      FastPlaceManager.clearServer();
      WorldHistoryManager.clearServer();
      WorldTaskFeature.clear();
      WorldWriteCoordinator.clearAll();
      if (PersistentRecoveryJournal.awaitIoIdle()) {
         PersistentRecoveryJournal.recoverAll(event.getServer());
      }
   }

   private static void onServerStopping(ServerStoppingEvent event) {
      for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
         FastPlaceManager.remove(player);
      }
      FastPlaceManager.clearServer();
      WorldHistoryManager.clearServer();
      PersistentRecoveryJournal.awaitIoIdle();
      FastPlaceNetwork.clearServer();
      WorldTaskFeature.clear();
      WorldWriteCoordinator.clear(event.getServer());
   }

   private static void onServerStopped(ServerStoppedEvent event) {
      FastPlaceManager.clearServer();
      WorldHistoryManager.clearServer();
      FastPlaceNetwork.clearServer();
      WorldTaskFeature.clear();
      WorldWriteCoordinator.clear(event.getServer());
   }

   private static void onLevelSave(LevelEvent.Save event) {
      if (event.getLevel() instanceof ServerLevel level) {
         PersistentRecoveryJournal.onLevelSaved(level);
      }
   }
}
