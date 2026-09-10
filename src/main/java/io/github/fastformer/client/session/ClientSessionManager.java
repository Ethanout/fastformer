package io.github.fastformer.client.session;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;

/** Owns top-level client session transitions and keeps them out of event handlers. */
public final class ClientSessionManager {
   private static final ClientSessionManager INSTANCE = new ClientSessionManager();

   private final Map<UUID, ClientPlayerSession> playerSessions = new HashMap<>();
   private ClientPlayerSession current;

   private ClientSessionManager() {
   }

   public static ClientSessionManager instance() {
      return INSTANCE;
   }

   /** Returns the most recently active player session, including across disconnects. */
   public ClientPlayerSession currentSession() {
      return current;
   }

   /** Returns the persistent session box for a player identity. */
   public ClientPlayerSession forPlayer(UUID playerId) {
      if (playerId == null) {
         throw new IllegalArgumentException("playerId must not be null");
      }
      return playerSessions.computeIfAbsent(playerId, ClientPlayerSession::new);
   }

   /** Records the current player identity without coupling the session box to LocalPlayer lifetime. */
   public void observePlayer(Minecraft minecraft) {
      if (minecraft == null || minecraft.player == null || minecraft.getConnection() == null) {
         return;
      }
      current = forPlayer(minecraft.player.getUUID());
   }
}
