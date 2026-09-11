package io.github.fastformer.client.session;

import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ClientPlayerSessionTest {
   @Test
   void endingInteractionClearsDraftFocusWorkspaceAndLock() {
      var session = new ClientPlayerSession(UUID.randomUUID());
      var workspace = session.operationWorkspace();
      var selection = session.selectionSession();
      workspace.addParts(java.util.List.of(
         io.github.fastformer.client.operation.model.ClientSelectionPart.empty(
            io.github.fastformer.client.operation.model.ClientSelectionPart.Source.WORLD)));
      workspace.setLocked(true);
      selection.addDraftPoint(new net.minecraft.core.BlockPos(80, 64, 80));
      selection.setAltHeld(true);
      selection.refresh(true, false, true);

      session.endInteraction();

      org.junit.jupiter.api.Assertions.assertTrue(workspace.isEmpty());
      org.junit.jupiter.api.Assertions.assertFalse(workspace.locked());
      org.junit.jupiter.api.Assertions.assertFalse(workspace.editing());
      org.junit.jupiter.api.Assertions.assertFalse(selection.hasDraft());
      org.junit.jupiter.api.Assertions.assertFalse(selection.altHeld());
      org.junit.jupiter.api.Assertions.assertEquals(
         io.github.fastformer.client.operation.selection.ClientSelectionState.UNFOCUSED, selection.state());
      session.endInteraction();
      org.junit.jupiter.api.Assertions.assertTrue(workspace.isEmpty());
   }

   @Test
   void managerReusesTheSameSessionBoxForAPlayerIdentity() {
      UUID playerId = UUID.randomUUID();
      ClientSessionManager manager = ClientSessionManager.instance();

      ClientPlayerSession first = manager.forPlayer(playerId);
      ClientPlayerSession second = manager.forPlayer(playerId);

      assertSame(first, second);
   }

   @Test
   void managerIsolatesSessionBoxesByConnectionAndDimension() {
      UUID playerId = UUID.randomUUID();

      ClientSessionManager manager = ClientSessionManager.instance();
      ClientPlayerSession overworld = manager.forScope(playerId, "server-a", "minecraft:overworld");
      ClientPlayerSession nether = manager.forScope(playerId, "server-a", "minecraft:the_nether");
      ClientPlayerSession otherServer = manager.forScope(playerId, "server-b", "minecraft:overworld");

      org.junit.jupiter.api.Assertions.assertNotSame(overworld, nether);
      org.junit.jupiter.api.Assertions.assertNotSame(overworld, otherServer);
      org.junit.jupiter.api.Assertions.assertNotSame(nether, otherServer);
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
