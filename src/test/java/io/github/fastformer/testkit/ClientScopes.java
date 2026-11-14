package io.github.fastformer.testkit;

import io.github.fastformer.client.session.scope.ClientScope;
import io.github.fastformer.client.session.scope.ClientScopeId;
import java.util.UUID;

/** Explicit world identity for tests that do not attach a Minecraft client. */
public final class ClientScopes {
   private ClientScopes() {}

   public static ClientScope create(UUID playerId) {
      return new ClientScope(new ClientScopeId("test-server", "minecraft:overworld", playerId));
   }
}
