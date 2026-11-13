package io.github.fastformer.server.lifecycle;

import com.mojang.logging.LogUtils;
import io.github.fastformer.core.async.IoExecutors;
import java.time.Duration;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadPoolExecutor;
import net.minecraft.server.MinecraftServer;

/** Owns background workers for exactly one running server, including integrated servers. */
public final class ServerBackgroundTasks {
   private static final Map<MinecraftServer, IoExecutors> SERVERS = new IdentityHashMap<>();

   private ServerBackgroundTasks() {}

   public static synchronized void start(MinecraftServer server) {
      Objects.requireNonNull(server, "server");
      if (SERVERS.containsKey(server)) throw new IllegalStateException("Server workers already started");
      SERVERS.put(server, new IoExecutors("fastformer-server", message -> LogUtils.getLogger().error(message)));
   }

   public static synchronized ThreadPoolExecutor serial(MinecraftServer server, String name, int capacity) {
      IoExecutors workers = SERVERS.get(Objects.requireNonNull(server, "server"));
      if (workers == null) throw new IllegalStateException("Server workers are not running");
      return workers.serial(name, capacity);
   }

   /** Removes admission before waiting, so a late callback cannot recreate a stopped server's workers. */
   public static boolean stop(MinecraftServer server) {
      IoExecutors workers;
      synchronized (ServerBackgroundTasks.class) { workers = SERVERS.remove(server); }
      return workers == null || workers.shutdown(Duration.ofSeconds(30));
   }
}
