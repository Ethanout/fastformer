package io.github.fastformer.fastplace.events;

import io.github.fastformer.fastplace.history.WorldHistoryManager;
import io.github.fastformer.fastplace.recovery.PersistentRecoveryJournal;
import io.github.fastformer.fastplace.settings.FastPlaceSettings;
import io.github.fastformer.fastplace.text.FastPlaceMessages;
import io.github.fastformer.fastplace.world.WorldTaskFeature;
import io.github.fastformer.fastplace.world.WorldWriteCoordinator;
import io.github.fastformer.network.FastPlaceNetwork;
import io.github.fastformer.network.sync.PlayerPreviewSync;
import io.github.fastformer.server.session.FastPlaceManager;
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
         newPlayer.getPersistentData().putBoolean("fastformerReachInitialized", oldPlayer.getPersistentData().getBoolean("fastformerReachInitialized"));
         newPlayer.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.BLOCK_INTERACTION_RANGE).setBaseValue(oldPlayer.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.BLOCK_INTERACTION_RANGE).getBaseValue());
         newPlayer.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.ENTITY_INTERACTION_RANGE).setBaseValue(oldPlayer.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.ENTITY_INTERACTION_RANGE).getBaseValue());
         PlayerPreviewSync.syncReachSettings(newPlayer);
         // Rebind the retained UUID-owned workflow to the replacement instance.
         FastPlaceManager.syncCurrentPreview(newPlayer);
      }
   }

   private static void onPlayerChangedDimension(PlayerChangedDimensionEvent event) {
      if (event.getEntity() instanceof ServerPlayer player) {
         // A dimension change is an environment change, not a user quit. The
         // selection and every world task stay alive: the task keeps its save,
         // its original dimension and its operation id. Only the binding to the
         // old environment ends, so old coordinates are never used in the new
         // dimension.
         FastPlaceManager.handleDimensionChange(player, event.getFrom(), event.getTo());
         PlayerPreviewSync.syncReachSettings(player);
      }
   }

   private static void onPlayerLogin(PlayerLoggedInEvent event) {
      if (!(event.getEntity() instanceof ServerPlayer player)) {
         return;
      }
      PlayerPreviewSync.beginClientSession(player);
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
         io.github.fastformer.server.input.ServerReachGate.remove(player);
         FastPlaceManager.detachPlayer(player);
      }
   }

   private static void onServerStarted(ServerStartedEvent event) {
      io.github.fastformer.server.input.ServerReachGate.clear();
      FastPlaceManager.clearServer();
      WorldHistoryManager.clearServer();
      WorldTaskFeature.clear();
      WorldWriteCoordinator.clearAll();
      if (PersistentRecoveryJournal.awaitIoIdle()) {
         PersistentRecoveryJournal.recoverAll(event.getServer());
      }
      WorldHistoryManager.resumeDiskCleanup(event.getServer());
   }

   private static void onServerStopping(ServerStoppingEvent event) {
      io.github.fastformer.server.input.ServerReachGate.clear();
      for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
         FastPlaceManager.remove(player);
      }
      FastPlaceManager.clearServer();
      PersistentRecoveryJournal.awaitIoIdle();
      WorldHistoryManager.awaitDiskWritesOnShutdown(event.getServer());
      WorldHistoryManager.clearServer();
      FastPlaceNetwork.clearServer();
      // The ledger belongs to one server instance. A different save must never answer
      // with a result that belongs to this one.
      io.github.fastformer.server.submission.WorkspaceSubmissionLedger.clearServer(event.getServer());
      WorldTaskFeature.clear();
      WorldWriteCoordinator.clear(event.getServer());
   }

   private static void onServerStopped(ServerStoppedEvent event) {
      FastPlaceManager.clearServer();
      WorldHistoryManager.clearServer();
      FastPlaceNetwork.clearServer();
      io.github.fastformer.server.submission.WorkspaceSubmissionLedger.clearServer(event.getServer());
      WorldTaskFeature.clear();
      WorldWriteCoordinator.clear(event.getServer());
   }

   private static void onLevelSave(LevelEvent.Save event) {
      if (event.getLevel() instanceof ServerLevel level) {
         PersistentRecoveryJournal.onLevelSaved(level);
      }
   }
}
