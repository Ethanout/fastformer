package io.github.fastformer.server.lifecycle;

import io.github.fastformer.server.history.HistorySystem;
import io.github.fastformer.server.network.upload.UploadSystem;

import io.github.fastformer.server.history.HistoryService;
import io.github.fastformer.server.history.HistoryRecoveryReconciler;
import io.github.fastformer.server.recovery.RecoverySystem;
import io.github.fastformer.server.settings.FastPlaceSettings;
import io.github.fastformer.server.feedback.PlayerMessages;
import io.github.fastformer.server.sync.PreviewSync;
import io.github.fastformer.server.session.QuickShapeSystem;
import io.github.fastformer.server.session.SessionSystem;
import io.github.fastformer.server.lifecycle.ServerBackgroundTasks;
import io.github.fastformer.server.player.PlayerStateRegistry;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent.Clone;
import net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerChangedDimensionEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

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
         io.github.fastformer.server.player.PlayerBlockReach.copy(oldPlayer, newPlayer);
         SessionSystem.detachPlayer(oldPlayer);
         PlayerStateRegistry.detach(oldPlayer);
         PlayerStateRegistry.attach(newPlayer);
         PreviewSync.syncInteractionUpdates(newPlayer);
         // Rebind the retained UUID-owned workflow to the replacement instance.
         SessionSystem.of(newPlayer).syncCurrentPreview(newPlayer);
      }
   }

   private static void onPlayerChangedDimension(PlayerChangedDimensionEvent event) {
      if (event.getEntity() instanceof ServerPlayer player) {
         // A dimension change is an environment change, not a user quit. The
         // selection and every world task stay alive: the task keeps its save,
         // its original dimension and its operation id. Only the binding to the
         // old environment ends, so old coordinates are never used in the new
         // dimension.
         PlayerStateRegistry.dimensionChanged(player);
         SessionSystem.of(player).handleDimensionChange(player, event.getFrom(), event.getTo());
      }
   }

   private static void onPlayerLogin(PlayerLoggedInEvent event) {
      if (!(event.getEntity() instanceof ServerPlayer player)) {
         return;
      }
      PlayerStateRegistry.attach(player);
      PreviewSync.beginClientSession(player);
      io.github.fastformer.server.player.PlayerBlockReach.initialize(player);
      PreviewSync.syncSettings(player);
      // Reattach UUID-owned workflows after a reconnect or player replacement.
      SessionSystem.of(player).syncCurrentPreview(player);
      HistorySystem.of(player).trimToSetting(player, FastPlaceSettings.load(player).worldUndoHistoryLimit());
      SessionSystem.of(player.getServer()).feedback().attach(player);
      if (!RecoverySystem.of(player.getServer()).writeGate().writesAllowed()) {
         PlayerMessages.chat(player, PlayerMessages.text("fastformer.message.recovery_journal_blocked"));
      }
   }

   private static void onPlayerLogout(PlayerLoggedOutEvent event) {
      if (event.getEntity() instanceof ServerPlayer player) {
         SessionSystem.detachPlayer(player);
         PlayerStateRegistry.detach(player);
      }
   }

   private static void onServerStarted(ServerStartedEvent event) {
      ServerBackgroundTasks.start(event.getServer());
      RecoverySystem.start(event.getServer());
      PlayerStateRegistry.start(event.getServer());
      SessionSystem.start(event.getServer());
      HistorySystem.start(event.getServer());
      UploadSystem.start(event.getServer());
      HistorySystem.of(event.getServer()).clear();
      RecoverySystem.of(event.getServer()).recoverAll(new HistoryRecoveryReconciler(io.github.fastformer.server.history.HistorySystem.of(event.getServer())));
      HistorySystem.of(event.getServer()).resumeDiskCleanup(event.getServer());
   }

   private static void onServerStopping(ServerStoppingEvent event) {
      PlayerStateRegistry.discardPlayers(event.getServer());
      for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
         SessionSystem.of(player).discardPlayer(player);
      }
      SessionSystem.stop(event.getServer());
      RecoverySystem.of(event.getServer()).awaitIoIdle();
      HistorySystem.of(event.getServer()).awaitDiskWritesOnShutdown(event.getServer());
      HistorySystem.of(event.getServer()).clear();
      UploadSystem.of(event.getServer()).close();
      RecoverySystem.of(event.getServer()).writeLeases().close();
   }

   private static void onServerStopped(ServerStoppedEvent event) {
      SessionSystem.stop(event.getServer());
      HistorySystem.of(event.getServer()).clear();
      UploadSystem.stop(event.getServer());
      RecoverySystem.stop(event.getServer());
      ServerBackgroundTasks.stop(event.getServer());
      PlayerStateRegistry.stop(event.getServer());
      HistorySystem.stop(event.getServer());
   }

   private static void onLevelSave(LevelEvent.Save event) {
      if (event.getLevel() instanceof ServerLevel level) {
         RecoverySystem.find(level.getServer()).ifPresent(recovery -> recovery.onLevelSaved(level));
      }
   }
}
