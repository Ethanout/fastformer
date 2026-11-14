package io.github.fastformer.client.session.scope;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ClientScopeRepositoryTest {



   @Test
   void cleanupReplayRunsOncePerEntryAndRemainsIsolatedByScope() {
      var repository = new ClientScopeRepository();
      UUID player = UUID.randomUUID();
      var aId = new ClientScopeId("a", "minecraft:overworld", player);
      var a = repository.activate(aId);
      assertTrue(a.claimCleanupReplay());
      assertFalse(a.claimCleanupReplay());
      assertSame(a, repository.activate(aId));
      assertFalse(a.claimCleanupReplay());
      var b = repository.activate(new ClientScopeId("b", "minecraft:the_nether", player));
      assertTrue(b.claimCleanupReplay());
      repository.activate(aId);
      assertTrue(a.claimCleanupReplay());
      assertFalse(a.claimCleanupReplay());
      assertFalse(b.claimCleanupReplay());
   }

}
