package io.github.fastformer.client.session;

import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ClientPlayerSessionTest {
   @Test
   void managerReusesTheSameSessionBoxForAPlayerIdentity() {
      UUID playerId = UUID.randomUUID();
      ClientSessionManager manager = ClientSessionManager.instance();

      ClientPlayerSession first = manager.forPlayer(playerId);
      ClientPlayerSession second = manager.forPlayer(playerId);

      assertSame(first, second);
   }

   @Test
   void playerSessionOwnsStableWorkspaceAndSelectionFields() {
      ClientPlayerSession session = new ClientPlayerSession(UUID.randomUUID());
      var workspace = session.operationWorkspace();
      var selection = session.selectionSession();

      assertSame(workspace, session.operationWorkspace());
      assertSame(selection, session.selectionSession());
   }
}
