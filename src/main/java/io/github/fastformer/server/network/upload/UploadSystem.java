package io.github.fastformer.server.network.upload;

import io.github.fastformer.server.lifecycle.ServerBackgroundTasks;
import io.github.fastformer.server.session.SessionSystem;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Explicit server assembly; queries and cleanup cannot recreate stopped upload state. */
public final class UploadSystem {
   private static final Map<MinecraftServer, WorkspaceUploads> SERVERS = new IdentityHashMap<>();

   private UploadSystem() {}

   public static synchronized void start(MinecraftServer server) {
      requireThread(server);
      if (SERVERS.containsKey(server)) throw new IllegalStateException("Upload system already started");
      SERVERS.put(server, new WorkspaceUploads(server, ServerBackgroundTasks.serial(server, "workspace-decode", 2),
         SessionSystem.of(server).submissions()));
   }

   public static WorkspaceUploads of(ServerPlayer player) { return of(player.getServer()); }

   public static synchronized WorkspaceUploads of(MinecraftServer server) {
      requireThread(server);
      WorkspaceUploads uploads = SERVERS.get(server);
      if (uploads == null) throw new IllegalStateException("Upload system is not running");
      return uploads;
   }

   public static synchronized void forget(ServerPlayer player) {
      requireThread(player.getServer());
      WorkspaceUploads uploads = SERVERS.get(player.getServer());
      if (uploads != null) uploads.forget(player);
   }

   public static void stop(MinecraftServer server) {
      requireThread(server);
      WorkspaceUploads uploads;
      synchronized (UploadSystem.class) { uploads = SERVERS.remove(server); }
      if (uploads != null) uploads.close();
   }

   static void requireThread(MinecraftServer server) {
      if (server == null || !server.isSameThread()) throw new IllegalStateException("Upload system requires its server thread");
   }
}
