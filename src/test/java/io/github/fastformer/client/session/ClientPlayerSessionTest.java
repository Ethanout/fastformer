package io.github.fastformer.client.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.github.fastformer.client.session.tree.ClientSessionInspection;
import io.github.fastformer.client.session.tree.SessionSignal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class ClientPlayerSessionTest {
   @Test
   void inspectionReportsTheActiveSessionNode() {
      ClientPlayerSession session = new ClientPlayerSession(UUID.randomUUID());

      assertEquals(ClientSessionState.EMPTY, session.state());
      assertEquals(new ClientSessionInspection(java.util.List.of("player", "empty")), session.inspect());
   }

   @Test
   void activeNodeOwnsSignalHandlers() {
      ClientPlayerSession session = new ClientPlayerSession(UUID.randomUUID());
      AtomicInteger calls = new AtomicInteger();
      session.root().child("empty").signals().register("confirm", ignored -> calls.incrementAndGet());

      assertEquals(1, session.signal(SessionSignal.named("confirm")));
      assertEquals(1, calls.get());
   }

   @Test
   void changingModesChangesInspectionButKeepsThePlayerTree() {
      ClientPlayerSession session = new ClientPlayerSession(UUID.randomUUID());
      ClientSessionSnapshot quickShape = new ClientSessionSnapshot(true, false, false);

      session.refresh(quickShape);

      assertEquals(ClientSessionState.QUICK_SHAPE, session.state());
      assertEquals(new ClientSessionInspection(java.util.List.of("player", "quick_shape")), session.inspect());
      assertEquals(4, session.root().children().size());
   }

   @Test
   void currentStateCellStoresObjectsAcrossInspectionChanges() {
      ClientPlayerSession session = new ClientPlayerSession(UUID.randomUUID());
      Object selection = new Object();
      session.put("selection", selection);

      session.refresh(new ClientSessionSnapshot(true, false, false));

      assertEquals(null, session.value("selection", Object.class));
      assertEquals(selection, session.root().child("empty").value("selection", Object.class));
      session.currentNode().put("shape", selection);
      session.refresh(new ClientSessionSnapshot(false, false, false));
      session.refresh(new ClientSessionSnapshot(true, false, false));
      assertEquals(selection, session.value("shape", Object.class));
   }

   @Test
   void managerReusesTheSameSessionBoxForAPlayerIdentity() {
      UUID playerId = UUID.randomUUID();
      ClientSessionManager manager = ClientSessionManager.instance();

      ClientPlayerSession first = manager.forPlayer(playerId);
      ClientPlayerSession second = manager.forPlayer(playerId);

      assertEquals(first, second);
   }

   @Test
   void connectionBoundaryDoesNotClearTheActivePlayerSession() {
      UUID playerId = UUID.randomUUID();
      ClientSessionManager manager = ClientSessionManager.instance();
      ClientPlayerSession session = manager.forPlayer(playerId);
      session.refresh(new ClientSessionSnapshot(true, false, false));

      manager.markDisconnected();

      assertEquals(ClientSessionState.QUICK_SHAPE, session.state());
      assertEquals(session, manager.forPlayer(playerId));
      assertEquals(new ClientSessionInspection(List.of("player", "quick_shape")), session.inspect());
   }

   @Test
   void playerOwnedWorkspaceAndSelectionSurviveStateTransitions() {
      ClientPlayerSession session = new ClientPlayerSession(UUID.randomUUID());
      var workspace = session.operationWorkspace();
      var selection = session.selectionSession();
      selection.addDraftPoint(new BlockPos(3, 4, 5));

      session.refresh(new ClientSessionSnapshot(true, false, false));
      session.refresh(new ClientSessionSnapshot(false, false, false));

      assertSame(workspace, session.operationWorkspace());
      assertSame(selection, session.selectionSession());
      assertEquals(List.of(new BlockPos(3, 4, 5)), session.selectionSession().draftPoints());
   }

   @Test
   void explicitClearEndsTheWorkflowButKeepsThePlayerBox() {
      ClientPlayerSession session = new ClientPlayerSession(UUID.randomUUID());
      Object value = new Object();
      session.root().put("retained", value);
      session.refresh(new ClientSessionSnapshot(true, false, false));

      session.clear();

      assertEquals(ClientSessionState.EMPTY, session.state());
      assertEquals(value, session.root().value("retained", Object.class));
   }
}
