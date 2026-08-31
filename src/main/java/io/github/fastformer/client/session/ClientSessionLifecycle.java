package io.github.fastformer.client.session;

import java.util.Objects;

/** Tracks client runtime boundaries without owning or clearing player state. */
final class ClientSessionLifecycle {
   private static final RuntimeIdentity DISCONNECTED = new RuntimeIdentity(null, null, null);

   private RuntimeIdentity identity = DISCONNECTED;
   private boolean awaitingAuthoritativeSnapshot;

   void observe(Object connection, Object level, Object player, ClientSessionState state) {
      RuntimeIdentity next = new RuntimeIdentity(connection, level, player);
      if (identity.sameInstances(next)) {
         return;
      }
      if (!awaitingAuthoritativeSnapshot) {
         awaitingAuthoritativeSnapshot = state != ClientSessionState.EMPTY;
      }
      identity = next;
   }

   /** Returns true only for an inactive snapshot that must not clear retained state. */
   boolean shouldHold(ClientSessionSnapshot snapshot) {
      Objects.requireNonNull(snapshot, "snapshot");
      if (!awaitingAuthoritativeSnapshot) {
         return false;
      }
      if (!snapshot.anyActive()) {
         return true;
      }
      awaitingAuthoritativeSnapshot = false;
      return false;
   }

   void markDisconnected(ClientSessionState state) {
      identity = DISCONNECTED;
      awaitingAuthoritativeSnapshot = state != ClientSessionState.EMPTY;
   }

   boolean awaitingAuthoritativeSnapshot() {
      return awaitingAuthoritativeSnapshot;
   }

   void clearAwaiting() {
      awaitingAuthoritativeSnapshot = false;
   }

   private record RuntimeIdentity(Object connection, Object level, Object player) {
      private boolean sameInstances(RuntimeIdentity other) {
         return connection == other.connection && level == other.level && player == other.player;
      }
   }
}
