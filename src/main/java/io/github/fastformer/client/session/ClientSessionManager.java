package io.github.fastformer.client.session;

import io.github.fastformer.client.render.FastPlaceClientPreview;
import io.github.fastformer.client.placement.QuickReplaceMode;
import io.github.fastformer.client.session.tree.ClientSessionInspection;
import io.github.fastformer.client.session.tree.SessionSignal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;

/** Owns top-level client session transitions and keeps them out of event handlers. */
public final class ClientSessionManager {
   private static final ClientSessionManager INSTANCE = new ClientSessionManager();

   private final Map<UUID, ClientPlayerSession> playerSessions = new HashMap<>();
   private final ClientSessionLifecycle lifecycle = new ClientSessionLifecycle();
   private ClientPlayerSession current;

   private ClientSessionManager() {
   }

   public static ClientSessionManager instance() {
      return INSTANCE;
   }

   public ClientSessionState state() {
      return current == null ? ClientSessionState.EMPTY : current.state();
   }

   /**
    * Returns whether a connection or client-world replacement is waiting for
    * its first authoritative preview snapshot.
    */
   public boolean isAwaitingAuthoritativeSnapshot() {
      return lifecycle.awaitingAuthoritativeSnapshot();
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

   public ClientSessionInspection inspect(Minecraft minecraft) {
      return sessionFor(minecraft).inspect();
   }

   public int signal(Minecraft minecraft, SessionSignal signal) {
      return sessionFor(minecraft).signal(signal);
   }

   public ClientSessionSnapshot snapshot(Minecraft minecraft) {
      return new ClientSessionSnapshot(
         FastPlaceClientPreview.active(),
         FastPlaceClientPreview.geometryActive() || FastPlaceClientPreview.operationActive(),
         QuickReplaceMode.active() && minecraft != null && minecraft.player != null
      );
   }

   public ClientSessionState refresh(Minecraft minecraft) {
      if (minecraft == null || minecraft.player == null || minecraft.getConnection() == null) {
         return current == null ? ClientSessionState.EMPTY : current.state();
      }
      current = forPlayer(minecraft.player.getUUID());
      Object connection = minecraft.getConnection();
      Object level = minecraft.level;
      Object player = minecraft.player;
      // Runtime identity changes are tracked separately from state matching.
      // The player-owned box remains intact until an active snapshot arrives.
      lifecycle.observe(connection, level, player, current.state());
      if (lifecycle.awaitingAuthoritativeSnapshot()) {
         return current.state();
      }
      current.refresh(snapshot(minecraft));
      return current.state();
   }

   /**
    * Gates an incoming server snapshot at a connection or world boundary.
    * Inactive packets are provisional until an active packet confirms the
    * retained player-owned workflow.
    */
   public boolean acceptAuthoritativeSnapshot(Minecraft minecraft, boolean active) {
      if (minecraft == null || minecraft.player == null || minecraft.getConnection() == null) {
         return true;
      }
      current = forPlayer(minecraft.player.getUUID());
      lifecycle.observe(minecraft.getConnection(), minecraft.level, minecraft.player, current.state());
      if (!lifecycle.awaitingAuthoritativeSnapshot()) {
         return true;
      }
      if (!active) {
         return false;
      }
      lifecycle.clearAwaiting();
      return true;
   }

   /** Explicitly ends the current workflow while retaining its player-owned box. */
   public void clearCurrent() {
      if (current == null) {
         return;
      }
      current.clear();
      lifecycle.clearAwaiting();
   }

   /**
    * Marks a connection boundary without changing the player's session box.
    * The next connection must receive an active preview before the
    * retained state is reconciled with the new server connection.
    */
   public void markDisconnected() {
      lifecycle.markDisconnected(
         current == null ? ClientSessionState.EMPTY : current.state()
      );
   }

   private ClientPlayerSession sessionFor(Minecraft minecraft) {
      if (minecraft == null || minecraft.player == null) {
         if (current == null) {
            current = new ClientPlayerSession(new UUID(0L, 0L));
         }
         return current;
      }
      return forPlayer(minecraft.player.getUUID());
   }
}
