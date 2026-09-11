package io.github.fastformer.client.session;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;

/** Owns top-level client session transitions and keeps them out of event handlers. */
public final class ClientSessionManager {
   private static final ClientSessionManager INSTANCE = new ClientSessionManager();

   private final Map<SessionKey, ClientPlayerSession> playerSessions = new HashMap<>();
   private ClientPlayerSession current;
   private SessionKey currentKey;

   private ClientSessionManager() {
   }

   public static ClientSessionManager instance() {
      return INSTANCE;
   }

   /** Returns the most recently active player session, including across disconnects. */
   public ClientPlayerSession currentSession() {
      return current;
   }

   public ClientPlayerSession forCurrent(Minecraft minecraft) {
      observePlayer(minecraft);
      return current;
   }

   /** Returns the persistent session box for a player identity. */
   public ClientPlayerSession forPlayer(UUID playerId) {
      if (playerId == null) {
         throw new IllegalArgumentException("playerId must not be null");
      }
      return playerSessions.computeIfAbsent(new SessionKey("legacy", "", playerId), key -> new ClientPlayerSession(playerId));
   }

   /** Returns the session isolated to one connection, player, and dimension. */
   public ClientPlayerSession forScope(UUID playerId, String connection, String dimension) {
      if (playerId == null) throw new IllegalArgumentException("playerId must not be null");
      SessionKey key = new SessionKey(connection, dimension, playerId);
      return playerSessions.computeIfAbsent(key, ignored -> new ClientPlayerSession(playerId));
   }

   ClientPlayerSession activateScope(UUID playerId, String connection, String dimension) {
      SessionKey nextKey = new SessionKey(connection, dimension, playerId);
      if (currentKey != null && !currentKey.equals(nextKey)
         && currentKey.connection().equals(nextKey.connection())
         && currentKey.playerId().equals(nextKey.playerId()) && current != null) {
         current.endInteraction();
      }
      currentKey = nextKey;
      return current = playerSessions.computeIfAbsent(nextKey, ignored -> new ClientPlayerSession(playerId));
   }

   /** Records the current player identity without coupling the session box to LocalPlayer lifetime. */
   public void observePlayer(Minecraft minecraft) {
      if (minecraft == null || minecraft.player == null || minecraft.getConnection() == null) {
         return;
      }
      String connection = connectionIdentity(minecraft);
      String dimension = minecraft.level == null ? "" : minecraft.level.dimension().location().toString();
      activateScope(minecraft.player.getUUID(), connection, dimension);
   }

   private static String connectionIdentity(Minecraft minecraft) {
      return "connection:" + System.identityHashCode(minecraft.getConnection());
   }

   private record SessionKey(String connection, String dimension, UUID playerId) {
      private SessionKey {
         connection = connection == null ? "" : connection;
         dimension = dimension == null ? "" : dimension;
      }
   }
}
