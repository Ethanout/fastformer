package io.github.fastformer.server.history;

import io.github.fastformer.server.history.entry.HistoryEntry;

import io.github.fastformer.server.history.entry.EntryCodecRegistry;

import io.github.fastformer.server.history.entry.HistoryEntryFormat;

import io.github.fastformer.server.gate.ServerOperationGates;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Server assembly owns service lifetimes; queries never create a service after shutdown. */
public final class HistorySystem {
   private static final Map<MinecraftServer, HistoryService> SERVERS = new IdentityHashMap<>();

   private HistorySystem() {}

   public static synchronized void start(MinecraftServer server) {
      requireThread(server);
      if (SERVERS.containsKey(server)) throw new IllegalStateException("History service already started");
      SERVERS.put(server, new HistoryService(ServerOperationGates.AVAILABLE, ServerOperationGates.WORLD_HISTORY,
         DiskHistoryRepository.open(server, new HistoryEntryFormat<>(EntryCodecRegistry.standard(), HistoryEntry.class))));
   }

   public static HistoryService of(ServerPlayer player) {
      return of(player.getServer());
   }

   public static synchronized HistoryService of(MinecraftServer server) {
      requireThread(server);
      HistoryService service = SERVERS.get(server);
      if (service == null) throw new IllegalStateException("History service is not running");
      return service;
   }

   public static void stop(MinecraftServer server) {
      requireThread(server);
      HistoryService service;
      synchronized (HistorySystem.class) { service = SERVERS.remove(server); }
      if (service != null) service.close();
   }

   private static void requireThread(MinecraftServer server) {
      if (server == null || !server.isSameThread()) throw new IllegalStateException("History service requires its server thread");
   }
}
