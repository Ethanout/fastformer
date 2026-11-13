package io.github.fastformer.server.session;

import io.github.fastformer.core.lifecycle.Scope;
import io.github.fastformer.core.lifecycle.ScopedKey;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Owns durable workflows and task queues of one running server, including offline players. */
public final class SessionSystem implements AutoCloseable {
   private static final Logger LOGGER = LoggerFactory.getLogger(SessionSystem.class);
   private static final Map<MinecraftServer, SessionSystem> SERVERS = Collections.synchronizedMap(new IdentityHashMap<>());
   private final Scope states = new Scope();
   final QuickShapeSystem quickShape = states.get(ScopedKey.create("quick_shape", () -> new QuickShapeSystem(this)));
   final OperationSystem operation = states.get(ScopedKey.create("operation", () -> new OperationSystem(this)));
   final GeometrySystem geometry = states.get(ScopedKey.create("geometry", () -> new GeometrySystem(this)));
   private boolean closed;

   SessionSystem() {}

   public static void start(MinecraftServer server) {
      requireThread(server);
      if (SERVERS.putIfAbsent(server, new SessionSystem()) != null) throw new IllegalStateException("session system already started");
   }

   public static SessionSystem of(MinecraftServer server) {
      return find(server).orElseThrow(() -> new IllegalStateException("session system is not running"));
   }

   public static Optional<SessionSystem> find(MinecraftServer server) {
      requireThread(server);
      var runtime = SERVERS.get(server);
      return runtime == null || runtime.closed ? Optional.empty() : Optional.of(runtime);
   }

   /** Vanilla disconnects players after the stopping boundary; cleanup must never create a runtime. */
   public static void detachPlayer(ServerPlayer player) {
      find(player.getServer()).ifPresent(runtime -> runtime.quickShape.detachPlayer(player));
   }

   /** Remove admission before cleanup, so a late lookup cannot recreate the old server runtime. */
   public static void stop(MinecraftServer server) {
      requireThread(server);
      var runtime = SERVERS.remove(server);
      if (runtime != null) {
         try { runtime.close(); }
         catch (RuntimeException failure) { LOGGER.error("FastFormer session cleanup failed", failure); }
      }
   }

   boolean closed() { return closed; }

   @Override public void close() {
      closed = true;
      states.close();
   }

   private static void requireThread(MinecraftServer server) {
      if (server == null || !server.isSameThread()) throw new IllegalStateException("sessions require their server thread");
   }
}
