package io.github.fastformer.server.player;

import io.github.fastformer.FastFormer;
import io.github.fastformer.core.lifecycle.Scoped;
import io.github.fastformer.core.lifecycle.ScopedKey;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Server-thread registry. The only global index is the identity of running servers. */
public final class PlayerStateRegistry {
   private static final Logger LOGGER = LoggerFactory.getLogger(FastFormer.MOD_ID);
   private static final Map<MinecraftServer, PlayerStateRegistry> SERVERS = Collections.synchronizedMap(new IdentityHashMap<>());
   private final Map<UUID, Binding> players = new HashMap<>();
   private final Set<ServerPlayer> detached = Collections.newSetFromMap(new WeakHashMap<>());

   private PlayerStateRegistry() {}

   public static <T extends Scoped> ScopedKey<T> key(ResourceLocation id, Supplier<T> factory) {
      return ScopedKey.create(id.toString(), factory);
   }

   public static void start(MinecraftServer server) {
      requireThread(server);
      if (SERVERS.putIfAbsent(server, new PlayerStateRegistry()) != null) throw new IllegalStateException("player registry already started");
   }

   public static ServerPlayerState of(ServerPlayer player) {
      PlayerStateRegistry registry = running(player.getServer());
      if (registry.detached.contains(player)) throw new IllegalStateException("player instance has detached");
      Binding binding = registry.players.get(player.getUUID());
      if (binding != null && binding.player() == player) return binding.state();
      if (binding != null) throw new IllegalStateException("replacement player must attach through its lifecycle");
      var state = new ServerPlayerState();
      registry.players.put(player.getUUID(), new Binding(player, state));
      return state;
   }

   public static void attach(ServerPlayer player) {
      PlayerStateRegistry registry = running(player.getServer());
      Binding binding = registry.players.get(player.getUUID());
      if (binding != null && binding.player() != player) detach(binding.player());
      registry.detached.remove(player);
      of(player);
   }

   public static Optional<ServerPlayerState> find(ServerPlayer player) {
      Binding binding = running(player.getServer()).players.get(player.getUUID());
      return binding != null && binding.player() == player ? Optional.of(binding.state()) : Optional.empty();
   }

   /** Admission does not create a scope, and retired/replaced player instances stay rejected. */
   public static boolean accepts(ServerPlayer player) {
      if (player == null) return false;
      PlayerStateRegistry registry = running(player.getServer());
      Binding binding = registry.players.get(player.getUUID());
      return !registry.detached.contains(player) && (binding == null || binding.player() == player);
   }

   public static void detach(ServerPlayer player) {
      PlayerStateRegistry registry = running(player.getServer());
      registry.detached.add(player);
      var players = registry.players;
      Binding binding = players.get(player.getUUID());
      if (binding != null && binding.player() == player && players.remove(player.getUUID(), binding)) {
         notify(binding.state(), ServerPlayerState::detach, player.getUUID().toString());
      }
   }

   public static void dimensionChanged(ServerPlayer player) {
      find(player).ifPresent(state -> notify(state, ServerPlayerState::dimensionChanged, player.getUUID().toString()));
   }

   /** Shutdown invalidates admission before cleanup callbacks can run. */
   public static void stop(MinecraftServer server) {
      requireThread(server);
      PlayerStateRegistry registry = SERVERS.remove(server);
      if (registry == null) return;
      registry.discardPlayers();
   }

   public static void discardPlayers(MinecraftServer server) { running(server).discardPlayers(); }

   private void discardPlayers() {
      var states = new ArrayList<>(players.values());
      players.clear();
      states.forEach(binding -> {
         detached.add(binding.player());
         notify(binding.state(), ServerPlayerState::close, "server stop");
      });
   }

   private static PlayerStateRegistry running(MinecraftServer server) {
      requireThread(server);
      PlayerStateRegistry registry = SERVERS.get(server);
      if (registry == null) throw new IllegalStateException("player registry is not running");
      return registry;
   }

   private static void requireThread(MinecraftServer server) {
      if (server == null || !server.isSameThread()) throw new IllegalStateException("player state requires its server thread");
   }

   private static void notify(ServerPlayerState state, Consumer<ServerPlayerState> action, String owner) {
      try { action.accept(state); }
      catch (RuntimeException failure) { LOGGER.error("FastFormer player scope cleanup failed for {}", owner, failure); }
   }

   private record Binding(ServerPlayer player, ServerPlayerState state) {}
}
