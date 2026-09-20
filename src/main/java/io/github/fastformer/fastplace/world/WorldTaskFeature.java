package io.github.fastformer.fastplace.world;

import com.mojang.logging.LogUtils;
import io.github.fastformer.fastplace.FastPlaceManager;
import io.github.fastformer.fastplace.FastPlaceMessages;
import io.github.fastformer.fastplace.OperationManager;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

/** Server/world-owned scheduler for every long-running world write task. */
public final class WorldTaskFeature {
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final int MAX_PENDING_CHAT = 4;
   private static final Map<UUID, Component> PENDING_ACTION_BAR = new HashMap<>();
   private static final Map<UUID, ArrayDeque<Component>> PENDING_CHAT = new HashMap<>();

   private WorldTaskFeature() {
   }

   public static void tick(MinecraftServer server) {
      // Recovery has priority. A writer that transfers itself to recovery is
      // removed from its manager before the next manager gets a chance to run.
      if (!tickSafely("history/recovery", () -> WorldHistoryManager.tickWorld(server))) {
         return;
      }
      // A closed gate stops new writes for the rest of the session. Placement and operation
      // then hand their queued tasks to recovery instead of ticking them: their tick path
      // waits for a lease first, so it would never reach the gate check inside the task.
      // The two handovers are independent, so one failure must not skip the other.
      if (PersistentRecoveryJournal.writesAllowed()) {
         if (!tickSafely("placement", () -> FastPlaceManager.tickWorld(server))) {
            return;
         }
      } else {
         tickSafely("placement-handover", () -> FastPlaceManager.handOverBlockedTasks(server));
      }
      if (PersistentRecoveryJournal.writesAllowed()) {
         tickSafely("operation", () -> OperationManager.tickWorld(server));
      } else {
         tickSafely("operation-handover", () -> OperationManager.handOverBlockedTasks(server));
      }
      // Finished submission results age out here. A record of work that still runs is
      // never removed, so a query always learns that the work is still in progress.
      tickSafely("submission-ledger", () -> WorkspaceSubmissionLedger.tick(server));
   }

   static boolean tickSafely(String kind, Runnable tick) {
      try {
         tick.run();
         return true;
      } catch (RuntimeException | OutOfMemoryError exception) {
         PersistentRecoveryJournal.blockNewWrites();
         LOGGER.error("FastFormer {} world task scheduler failed; journals and leases were retained", kind, exception);
         return false;
      }
   }

   static void deferActionBar(UUID owner, Component message) {
      if (owner != null && message != null) {
         PENDING_ACTION_BAR.put(owner, message);
      }
   }

   static void deferChat(UUID owner, Component message) {
      if (owner == null || message == null) {
         return;
      }
      ArrayDeque<Component> messages = PENDING_CHAT.computeIfAbsent(owner, ignored -> new ArrayDeque<>());
      while (messages.size() >= MAX_PENDING_CHAT) {
         messages.removeFirst();
      }
      messages.addLast(message);
   }

   public static void attachPlayer(ServerPlayer player) {
      UUID owner = player.getUUID();
      Component actionBar = PENDING_ACTION_BAR.remove(owner);
      if (actionBar != null) {
         FastPlaceMessages.actionBar(player, actionBar);
      }
      ArrayDeque<Component> messages = PENDING_CHAT.remove(owner);
      if (messages != null) {
         for (Component message : messages) {
            FastPlaceMessages.chat(player, message);
         }
      }
   }

   public static void clear() {
      PENDING_ACTION_BAR.clear();
      PENDING_CHAT.clear();
   }

   static int pendingChatCountForTest(UUID owner) {
      ArrayDeque<Component> messages = PENDING_CHAT.get(owner);
      return messages == null ? 0 : messages.size();
   }

   static Component pendingActionBarForTest(UUID owner) {
      return PENDING_ACTION_BAR.get(owner);
   }
}
