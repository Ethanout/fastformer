package io.github.fastformer.client.session.scope;

import java.util.Objects;
import java.util.UUID;

/** Persistent server identity, dimension, and player; not a live connection generation. */
public record ClientScopeId(String connection, String dimension, UUID playerId) {
   public ClientScopeId {
      connection = connection == null ? "" : connection;
      dimension = dimension == null ? "" : dimension;
      Objects.requireNonNull(playerId, "playerId");
   }
}
