package io.github.fastformer.client.session;

import static org.junit.jupiter.api.Assertions.assertSame;

import io.github.fastformer.client.operation.model.ClientSelectionPart;
import io.github.fastformer.client.operation.model.WorkspaceTransform;
import io.github.fastformer.fastplace.selection.OperationSelectionMode;
import io.github.fastformer.network.payload.operation.OperationCallbackScope;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class ClientPlayerSessionTest {
   @Test
   void compatibilityWorkspaceAccessUsesTheSelectionOwner() {
      var first = new ClientPlayerSession(UUID.randomUUID());
      var second = new ClientPlayerSession(UUID.randomUUID());
      assertSame(first.selectionSession().workspace(), first.operationWorkspace());
      org.junit.jupiter.api.Assertions.assertNotSame(first.operationWorkspace(), second.operationWorkspace());
      first.operationWorkspace().addParts(java.util.List.of(
         ClientSelectionPart.empty(ClientSelectionPart.Source.CLIPBOARD)));
      org.junit.jupiter.api.Assertions.assertEquals(1, first.selectionSession().workspace().size());
      org.junit.jupiter.api.Assertions.assertTrue(second.selectionSession().workspace().isEmpty());
   }

   @Test
   void localSubmissionIgnoresAnUnrelatedServerSelection() {
      var session = new ClientPlayerSession(UUID.randomUUID());
      session.operationWorkspace().addParts(java.util.List.of(
         ClientSelectionPart.empty(ClientSelectionPart.Source.CLIPBOARD)));
      UUID transferId = UUID.randomUUID();

      ClientOperationDraft draft = session.buildSubmittedDraft(
         identity(BlockPos.ZERO), OperationSubmissionOrigin.LOCAL_ONLY, transferId);

      org.junit.jupiter.api.Assertions.assertNotNull(draft);
      org.junit.jupiter.api.Assertions.assertEquals(OperationSubmissionOrigin.LOCAL_ONLY, draft.origin());
      org.junit.jupiter.api.Assertions.assertNull(draft.identity());
      org.junit.jupiter.api.Assertions.assertTrue(draft.belongsToSubmission(transferId));
   }

   @Test
   void detachingAnEnvironmentClearsDraftFocusWorkspaceAndLock() {
      var session = new ClientPlayerSession(UUID.randomUUID());
      var workspace = session.operationWorkspace();
      var selection = session.selectionSession();
      workspace.addParts(java.util.List.of(
         io.github.fastformer.client.operation.model.ClientSelectionPart.empty(
            io.github.fastformer.client.operation.model.ClientSelectionPart.Source.WORLD)));
      workspace.setLocked(true);
      selection.addDraftPoint(new net.minecraft.core.BlockPos(80, 64, 80));
      selection.setAltHeld(true);

      session.detachEnvironment();

      org.junit.jupiter.api.Assertions.assertTrue(workspace.isEmpty());
      org.junit.jupiter.api.Assertions.assertFalse(workspace.locked());
      org.junit.jupiter.api.Assertions.assertFalse(workspace.editing());
      org.junit.jupiter.api.Assertions.assertFalse(selection.hasDraft());
      org.junit.jupiter.api.Assertions.assertFalse(selection.altHeld());
      org.junit.jupiter.api.Assertions.assertEquals(
         io.github.fastformer.client.operation.selection.ClientSelectionState.UNFOCUSED, selection.state());
      session.detachEnvironment();
      org.junit.jupiter.api.Assertions.assertTrue(workspace.isEmpty());
   }

   @Test
   void detachingAnEnvironmentKeepsTheDraftStagedForThatEnvironment() {
      ClientPlayerSession session = new ClientPlayerSession(UUID.randomUUID());
      var workspace = session.operationWorkspace();
      var selection = session.selectionSession();
      OperationDraftIdentity identity = identity(BlockPos.ZERO);
      workspace.addParts(java.util.List.of(ClientSelectionPart.empty(ClientSelectionPart.Source.WORLD)));
      selection.addDraftPoint(new BlockPos(7, 8, 9));
      session.suspendOperationDraft(identity);

      // Live state created after the suspension must not survive the detach.
      workspace.addParts(java.util.List.of(ClientSelectionPart.empty(ClientSelectionPart.Source.WORLD)));
      selection.addDraftPoint(new BlockPos(1, 1, 1));
      workspace.setLocked(true);

      session.detachEnvironment();

      org.junit.jupiter.api.Assertions.assertTrue(workspace.isEmpty());
      org.junit.jupiter.api.Assertions.assertFalse(workspace.locked());
      org.junit.jupiter.api.Assertions.assertFalse(selection.hasDraft());
      org.junit.jupiter.api.Assertions.assertTrue(session.suspendedOperationDraft());
      org.junit.jupiter.api.Assertions.assertTrue(session.restoreSuspendedOperationDraft(identity));
      org.junit.jupiter.api.Assertions.assertEquals(1, workspace.size());
   }

   @Test
   void switchingDimensionKeepsTheDraftStagedForTheLeftScope() {
      UUID playerId = UUID.randomUUID();
      ClientSessionManager manager = ClientSessionManager.instance();
      ClientPlayerSession overworld = manager.activateScope(playerId, "server-scope", "minecraft:overworld");
      overworld.operationWorkspace().addParts(java.util.List.of(
         ClientSelectionPart.empty(ClientSelectionPart.Source.WORLD)));
      overworld.selectionSession().addDraftPoint(new BlockPos(3, 4, 5));
      OperationDraftIdentity identity = identity(BlockPos.ZERO);
      overworld.suspendOperationDraft(identity);

      ClientPlayerSession nether = manager.activateScope(playerId, "server-scope", "minecraft:the_nether");

      org.junit.jupiter.api.Assertions.assertNotSame(overworld, nether);
      org.junit.jupiter.api.Assertions.assertSame(nether, manager.currentSession());
      org.junit.jupiter.api.Assertions.assertFalse(manager.currentDraftPending());
      org.junit.jupiter.api.Assertions.assertTrue(overworld.suspendedOperationDraft());
      org.junit.jupiter.api.Assertions.assertFalse(overworld.selectionSession().hasDraft());
      org.junit.jupiter.api.Assertions.assertTrue(overworld.operationWorkspace().isEmpty());
      org.junit.jupiter.api.Assertions.assertTrue(overworld.restoreSuspendedOperationDraft(identity));
      org.junit.jupiter.api.Assertions.assertEquals(1, overworld.operationWorkspace().size());
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
      manager.activateScope(playerId, "server-a", "minecraft:overworld").selectionSession()
         .addDraftPoint(new net.minecraft.core.BlockPos(1, 2, 3));
      ClientPlayerSession overworld = manager.forScope(playerId, "server-a", "minecraft:overworld");
      ClientPlayerSession nether = manager.activateScope(playerId, "server-a", "minecraft:the_nether");
      ClientPlayerSession otherServer = manager.forScope(playerId, "server-b", "minecraft:overworld");

      org.junit.jupiter.api.Assertions.assertFalse(overworld.selectionSession().hasDraft());
      org.junit.jupiter.api.Assertions.assertNotSame(overworld, nether);
      org.junit.jupiter.api.Assertions.assertNotSame(overworld, otherServer);
      org.junit.jupiter.api.Assertions.assertNotSame(nether, otherServer);
   }

   @Test
   void operationCallbackScopeRejectsOldConnectionsAndWorlds() {
      UUID playerId = UUID.randomUUID();
      UUID sessionId = UUID.randomUUID();
      Object currentConnection = new Object();
      OperationCallbackScope callbackScope = new OperationCallbackScope(
         playerId, ResourceLocation.withDefaultNamespace("overworld"), sessionId
      );

      org.junit.jupiter.api.Assertions.assertTrue(ClientSessionManager.matchesOperationCallbackScope(
         callbackScope, playerId, ResourceLocation.withDefaultNamespace("overworld"), sessionId, currentConnection, currentConnection
      ));
      org.junit.jupiter.api.Assertions.assertFalse(ClientSessionManager.matchesOperationCallbackScope(
         callbackScope, playerId, ResourceLocation.withDefaultNamespace("the_nether"), sessionId, currentConnection, currentConnection
      ));
      org.junit.jupiter.api.Assertions.assertFalse(ClientSessionManager.matchesOperationCallbackScope(
         callbackScope, playerId, ResourceLocation.withDefaultNamespace("overworld"), sessionId, currentConnection, new Object()
      ));
      org.junit.jupiter.api.Assertions.assertFalse(ClientSessionManager.matchesOperationCallbackScope(
         callbackScope, UUID.randomUUID(), ResourceLocation.withDefaultNamespace("overworld"), sessionId, currentConnection, currentConnection
      ));
      org.junit.jupiter.api.Assertions.assertFalse(ClientSessionManager.matchesOperationCallbackScope(
         callbackScope, playerId, ResourceLocation.withDefaultNamespace("overworld"), UUID.randomUUID(), currentConnection, currentConnection
      ));
   }

   @Test
   void playerSessionOwnsStableWorkspaceAndSelectionFields() {
      ClientPlayerSession session = new ClientPlayerSession(UUID.randomUUID());
      var workspace = session.operationWorkspace();
      var selection = session.selectionSession();

      assertSame(workspace, session.operationWorkspace());
      assertSame(selection, session.selectionSession());
   }

   @Test
   void reconnectRestoresOnlyDurableDraftDataForTheSameServerSelection() {
      ClientPlayerSession session = new ClientPlayerSession(UUID.randomUUID());
      var workspace = session.operationWorkspace();
      var selection = session.selectionSession();
      workspace.addParts(java.util.List.of(ClientSelectionPart.empty(ClientSelectionPart.Source.WORLD)));
      workspace.beginEdit();
      ClientSelectionPart translated = workspace.part(1).orElseThrow()
         .withTransform(WorkspaceTransform.IDENTITY.withTranslation(new Vec3(4.0, 2.0, -3.0)));
      workspace.updatePart(translated);
      workspace.finishEdit();
      workspace.setLocked(true);
      selection.setSelectionMode(OperationSelectionMode.CUBOID);
      selection.addDraftPoint(new BlockPos(1, 2, 3));
      selection.addDraftPoint(new BlockPos(4, 5, 6));
      selection.expandDraftTo(new BlockPos(-2, 8, 1));
      selection.setAltHeld(true);
      OperationDraftIdentity identity = identity(new BlockPos(1, 2, 3));

      session.suspendOperationDraft(identity);

      org.junit.jupiter.api.Assertions.assertTrue(session.suspendedOperationDraft());
      org.junit.jupiter.api.Assertions.assertTrue(workspace.isEmpty());
      org.junit.jupiter.api.Assertions.assertFalse(selection.hasDraft());
      org.junit.jupiter.api.Assertions.assertFalse(selection.altHeld());

      org.junit.jupiter.api.Assertions.assertTrue(session.restoreSuspendedOperationDraft(identity));
      org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of(translated), workspace.parts());
      org.junit.jupiter.api.Assertions.assertEquals(java.util.Set.of(1), workspace.selectedIds());
      org.junit.jupiter.api.Assertions.assertEquals(1, workspace.activeId());
      org.junit.jupiter.api.Assertions.assertFalse(workspace.locked());
      org.junit.jupiter.api.Assertions.assertFalse(workspace.editing());
      org.junit.jupiter.api.Assertions.assertEquals(0, workspace.undoSize());
      org.junit.jupiter.api.Assertions.assertEquals(
         java.util.List.of(new BlockPos(1, 2, 3), new BlockPos(4, 5, 6)), selection.draftPoints());
      org.junit.jupiter.api.Assertions.assertEquals(new BlockPos(-2, 2, 1), selection.draftMinPoint());
      org.junit.jupiter.api.Assertions.assertEquals(new BlockPos(4, 8, 6), selection.draftMaxPoint());
      org.junit.jupiter.api.Assertions.assertFalse(selection.altHeld());
   }

   @Test
   void mismatchedServerSelectionDiscardsTheSuspendedDraft() {
      ClientPlayerSession session = new ClientPlayerSession(UUID.randomUUID());
      session.operationWorkspace().addParts(java.util.List.of(
         ClientSelectionPart.empty(ClientSelectionPart.Source.WORLD)));
      OperationDraftIdentity original = identity(BlockPos.ZERO);

      session.suspendOperationDraft(original);

      org.junit.jupiter.api.Assertions.assertFalse(
         session.restoreSuspendedOperationDraft(identity(new BlockPos(9, 0, 0))));
      org.junit.jupiter.api.Assertions.assertFalse(session.suspendedOperationDraft());
      org.junit.jupiter.api.Assertions.assertTrue(session.operationWorkspace().isEmpty());
   }

   @Test
   void disconnectDuringAnEditKeepsTheLastCompletedTransform() {
      ClientPlayerSession session = new ClientPlayerSession(UUID.randomUUID());
      var workspace = session.operationWorkspace();
      workspace.addParts(java.util.List.of(ClientSelectionPart.empty(ClientSelectionPart.Source.WORLD)));
      ClientSelectionPart baseline = workspace.part(1).orElseThrow();
      workspace.beginEdit();
      workspace.updatePart(baseline.withTranslation(new BlockPos(20, 0, 0)));

      OperationDraftIdentity identity = identity(BlockPos.ZERO);
      session.suspendOperationDraft(identity);
      session.restoreSuspendedOperationDraft(identity);

      org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of(baseline), workspace.parts());
   }

   @Test
   void repeatedWorldEndDoesNotDiscardTheFirstSuspendedDraft() {
      ClientPlayerSession session = new ClientPlayerSession(UUID.randomUUID());
      session.operationWorkspace().addParts(java.util.List.of(
         ClientSelectionPart.empty(ClientSelectionPart.Source.WORLD)));
      OperationDraftIdentity identity = identity(BlockPos.ZERO);

      session.suspendOperationDraft(identity);
      session.suspendOperationDraft(null);

      org.junit.jupiter.api.Assertions.assertTrue(session.suspendedOperationDraft());
      org.junit.jupiter.api.Assertions.assertTrue(session.restoreSuspendedOperationDraft(identity));
      org.junit.jupiter.api.Assertions.assertEquals(1, session.operationWorkspace().size());
   }

   private static OperationDraftIdentity identity(BlockPos firstPoint) {
      return new OperationDraftIdentity(
         OperationSelectionMode.CUBOID,
         java.util.List.of(firstPoint, firstPoint.offset(1, 1, 1)),
         0,
         BlockPos.ZERO,
         BlockPos.ZERO,
         0.0,
         firstPoint,
         firstPoint.offset(1, 1, 1)
      );
   }
}
