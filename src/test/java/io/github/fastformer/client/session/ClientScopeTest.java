package io.github.fastformer.client.session;

import static org.junit.jupiter.api.Assertions.assertSame;

import io.github.fastformer.client.session.draft.ClientOperationDraft;
import io.github.fastformer.client.session.draft.OperationDraftIdentity;
import io.github.fastformer.client.session.scope.ClientScope;
import io.github.fastformer.client.session.scope.ClientScopeId;
import io.github.fastformer.client.session.submission.OperationSubmissionOrigin;
import io.github.fastformer.fastplace.selection.OperationSelectionMode;
import io.github.fastformer.protocol.callback.OperationCallbackScope;
import io.github.fastformer.testkit.ClientScopes;
import io.github.fastformer.workspace.model.ClientSelectionPart;
import io.github.fastformer.workspace.model.WorkspaceTransform;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class ClientScopeTest {
   @Test
   void scopeKeepsItsCompleteIdentityAcrossInputResetAndEnvironmentDetach() {
      var id = new ClientScopeId("server-a", "minecraft:the_nether", UUID.randomUUID());
      var scope = new ClientScope(id);
      scope.selectionSession().addDraftPoint(BlockPos.ZERO);
      scope.resetInput();
      scope.detachEnvironment();
      assertSame(id, scope.id());
      assertSame(scope.operations().selectionGestures(), scope.inputSession().selectionGestures());
   }

   @Test
   void scopeRequiresAnIdentityAndPlayer() {
      org.junit.jupiter.api.Assertions.assertThrows(NullPointerException.class, () -> new ClientScope(null));
      org.junit.jupiter.api.Assertions.assertThrows(NullPointerException.class,
         () -> new ClientScopeId("server-a", "minecraft:overworld", null));
   }

   @Test
   void managerCreatesStateWithTheRequestedIdentity() {
      var manager = new ClientSessionSystem();
      UUID player = UUID.randomUUID();
      var a = manager.forScope(new ClientScopeId("server-a", "minecraft:overworld", player));
      var b = manager.activateScope(player, "server-b", "minecraft:the_nether");
      org.junit.jupiter.api.Assertions.assertEquals(
         new ClientScopeId("server-a", "minecraft:overworld", player), a.id());
      org.junit.jupiter.api.Assertions.assertEquals(
         new ClientScopeId("server-b", "minecraft:the_nether", player), b.id());
      assertSame(b, manager.currentScope());
      org.junit.jupiter.api.Assertions.assertNotSame(a.inputSession(), b.inputSession());
      org.junit.jupiter.api.Assertions.assertNotSame(a.workspace(), b.workspace());
   }

   @Test void clientSelectionOwnershipSurvivesInputResetAndReturnToOrigin() {
      var session = ClientScopes.create(UUID.randomUUID());
      session.selectionSession().retain();
      var original = ClientSelectionPart.empty(ClientSelectionPart.Source.WORLD);
      var returned = original.withTranslation(new BlockPos(1, 0, 0)).withTranslation(BlockPos.ZERO);
      session.workspace().addParts(java.util.List.of(returned));
      session.resetInput();
      org.junit.jupiter.api.Assertions.assertTrue(session.selectionSession().clientOwned());
      var draft = session.buildSubmittedDraft(null, OperationSubmissionOrigin.CLIENT_SELECTION, UUID.randomUUID());
      org.junit.jupiter.api.Assertions.assertNotNull(draft);
      org.junit.jupiter.api.Assertions.assertFalse(draft.requiresServerIdentity());
      session.detachEnvironment();
      org.junit.jupiter.api.Assertions.assertFalse(session.selectionSession().clientOwned());
   }
   @Test
   void clientWorldSelectionCanSubmitAndRestoreWithoutServerIdentity() {
      var session = ClientScopes.create(UUID.randomUUID());
      session.workspace().addParts(java.util.List.of(ClientSelectionPart.empty(ClientSelectionPart.Source.WORLD)));
      var transfer = UUID.randomUUID();
      var draft = session.buildSubmittedDraft(null, OperationSubmissionOrigin.CLIENT_SELECTION, transfer);
      org.junit.jupiter.api.Assertions.assertNotNull(draft);
      org.junit.jupiter.api.Assertions.assertFalse(draft.requiresServerIdentity());
      org.junit.jupiter.api.Assertions.assertEquals(OperationSubmissionOrigin.CLIENT_SELECTION, draft.origin());
      session.suspendOperationDraft(null, OperationSubmissionOrigin.CLIENT_SELECTION, transfer);
      org.junit.jupiter.api.Assertions.assertTrue(session.restoreSuspendedOperationDraft(null));
      org.junit.jupiter.api.Assertions.assertTrue(session.selectionSession().retained());
      org.junit.jupiter.api.Assertions.assertEquals(ClientSelectionPart.Source.WORLD,
         session.workspace().parts().getFirst().source());
   }
   @Test
   void workspaceAndSelectionShareOneOwner() {
      var first = ClientScopes.create(UUID.randomUUID());
      var second = ClientScopes.create(UUID.randomUUID());
      assertSame(first.selectionSession().workspace(), first.workspace());
      org.junit.jupiter.api.Assertions.assertNotSame(first.workspace(), second.workspace());
      first.workspace().addParts(java.util.List.of(
         ClientSelectionPart.empty(ClientSelectionPart.Source.CLIPBOARD)));
      org.junit.jupiter.api.Assertions.assertEquals(1, first.selectionSession().workspace().size());
      org.junit.jupiter.api.Assertions.assertTrue(second.selectionSession().workspace().isEmpty());
   }

   @Test
   void localSubmissionIgnoresAnUnrelatedServerSelection() {
      var session = ClientScopes.create(UUID.randomUUID());
      session.workspace().addParts(java.util.List.of(
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
      var session = ClientScopes.create(UUID.randomUUID());
      var workspace = session.workspace();
      var selection = session.selectionSession();
      workspace.addParts(java.util.List.of(
         io.github.fastformer.workspace.model.ClientSelectionPart.empty(
            io.github.fastformer.workspace.model.ClientSelectionPart.Source.WORLD)));
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
      ClientScope session = ClientScopes.create(UUID.randomUUID());
      var workspace = session.workspace();
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
      ClientSessionSystem manager = new ClientSessionSystem();
      ClientScope overworld = manager.activateScope(playerId, "server-scope", "minecraft:overworld");
      overworld.workspace().addParts(java.util.List.of(
         ClientSelectionPart.empty(ClientSelectionPart.Source.WORLD)));
      overworld.selectionSession().addDraftPoint(new BlockPos(3, 4, 5));
      OperationDraftIdentity identity = identity(BlockPos.ZERO);
      overworld.suspendOperationDraft(identity);

      ClientScope nether = manager.activateScope(playerId, "server-scope", "minecraft:the_nether");

      org.junit.jupiter.api.Assertions.assertNotSame(overworld, nether);
      org.junit.jupiter.api.Assertions.assertSame(nether, manager.currentScope());
      org.junit.jupiter.api.Assertions.assertFalse(manager.currentDraftPending());
      org.junit.jupiter.api.Assertions.assertTrue(overworld.suspendedOperationDraft());
      org.junit.jupiter.api.Assertions.assertFalse(overworld.selectionSession().hasDraft());
      org.junit.jupiter.api.Assertions.assertTrue(overworld.workspace().isEmpty());
      org.junit.jupiter.api.Assertions.assertTrue(overworld.restoreSuspendedOperationDraft(identity));
      org.junit.jupiter.api.Assertions.assertEquals(1, overworld.workspace().size());
   }

   @Test
   void emptyInactiveScopesAreReclaimedButUnsavedDraftsStay() {
      UUID playerId = UUID.randomUUID();
      ClientSessionSystem manager = new ClientSessionSystem();
      var first = manager.activateScope(playerId, "reclaim-empty", "minecraft:overworld");
      manager.activateScope(playerId, "reclaim-empty", "minecraft:the_nether");
      var replacement = manager.activateScope(playerId, "reclaim-empty", "minecraft:overworld");
      org.junit.jupiter.api.Assertions.assertNotSame(first, replacement);
      replacement.selectionSession().addDraftPoint(new BlockPos(1, 2, 3));
      replacement.suspendOperationDraft(identity(BlockPos.ZERO));
      manager.activateScope(playerId, "reclaim-empty", "minecraft:the_nether");
      manager.reclaimInactiveScopes();
      assertSame(replacement, manager.activateScope(playerId, "reclaim-empty", "minecraft:overworld"));
      org.junit.jupiter.api.Assertions.assertTrue(replacement.suspendedOperationDraft());
   }

   @Test
   void managerReusesTheSameSessionBoxWithinAScope() {
      UUID playerId = UUID.randomUUID();
      ClientSessionSystem manager = new ClientSessionSystem();

      ClientScope first = manager.forScope(new ClientScopeId("session-reuse", "minecraft:overworld", playerId));
      ClientScope second = manager.forScope(new ClientScopeId("session-reuse", "minecraft:overworld", playerId));

      assertSame(first, second);
   }

   @Test
   void managerIsolatesSessionBoxesByConnectionAndDimension() {
      UUID playerId = UUID.randomUUID();

      ClientSessionSystem manager = new ClientSessionSystem();
      manager.activateScope(playerId, "server-a", "minecraft:overworld").selectionSession()
         .addDraftPoint(new net.minecraft.core.BlockPos(1, 2, 3));
      ClientScope overworld = manager.forScope(new ClientScopeId("server-a", "minecraft:overworld", playerId));
      ClientScope nether = manager.activateScope(playerId, "server-a", "minecraft:the_nether");
      ClientScope otherServer = manager.forScope(new ClientScopeId("server-b", "minecraft:overworld", playerId));

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

      org.junit.jupiter.api.Assertions.assertTrue(ClientSessionSystem.matchesOperationCallbackScope(
         callbackScope, playerId, ResourceLocation.withDefaultNamespace("overworld"), sessionId, currentConnection, currentConnection
      ));
      org.junit.jupiter.api.Assertions.assertFalse(ClientSessionSystem.matchesOperationCallbackScope(
         callbackScope, playerId, ResourceLocation.withDefaultNamespace("the_nether"), sessionId, currentConnection, currentConnection
      ));
      org.junit.jupiter.api.Assertions.assertFalse(ClientSessionSystem.matchesOperationCallbackScope(
         callbackScope, playerId, ResourceLocation.withDefaultNamespace("overworld"), sessionId, currentConnection, new Object()
      ));
      org.junit.jupiter.api.Assertions.assertFalse(ClientSessionSystem.matchesOperationCallbackScope(
         callbackScope, UUID.randomUUID(), ResourceLocation.withDefaultNamespace("overworld"), sessionId, currentConnection, currentConnection
      ));
      org.junit.jupiter.api.Assertions.assertFalse(ClientSessionSystem.matchesOperationCallbackScope(
         callbackScope, playerId, ResourceLocation.withDefaultNamespace("overworld"), UUID.randomUUID(), currentConnection, currentConnection
      ));
   }

   @Test
   void playerSessionOwnsStableWorkspaceAndSelectionFields() {
      ClientScope session = ClientScopes.create(UUID.randomUUID());
      var workspace = session.workspace();
      var selection = session.selectionSession();

      assertSame(workspace, session.workspace());
      assertSame(selection, session.selectionSession());
   }

   @Test
   void reconnectRestoresOnlyDurableDraftDataForTheSameServerSelection() {
      ClientScope session = ClientScopes.create(UUID.randomUUID());
      var workspace = session.workspace();
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
      ClientScope session = ClientScopes.create(UUID.randomUUID());
      session.workspace().addParts(java.util.List.of(
         ClientSelectionPart.empty(ClientSelectionPart.Source.WORLD)));
      OperationDraftIdentity original = identity(BlockPos.ZERO);

      session.suspendOperationDraft(original);

      org.junit.jupiter.api.Assertions.assertFalse(
         session.restoreSuspendedOperationDraft(identity(new BlockPos(9, 0, 0))));
      org.junit.jupiter.api.Assertions.assertFalse(session.suspendedOperationDraft());
      org.junit.jupiter.api.Assertions.assertTrue(session.workspace().isEmpty());
   }

   @Test
   void disconnectDuringAnEditKeepsTheLastCompletedTransform() {
      ClientScope session = ClientScopes.create(UUID.randomUUID());
      var workspace = session.workspace();
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
      ClientScope session = ClientScopes.create(UUID.randomUUID());
      session.workspace().addParts(java.util.List.of(
         ClientSelectionPart.empty(ClientSelectionPart.Source.WORLD)));
      OperationDraftIdentity identity = identity(BlockPos.ZERO);

      session.suspendOperationDraft(identity);
      session.suspendOperationDraft(null);

      org.junit.jupiter.api.Assertions.assertTrue(session.suspendedOperationDraft());
      org.junit.jupiter.api.Assertions.assertTrue(session.restoreSuspendedOperationDraft(identity));
      org.junit.jupiter.api.Assertions.assertEquals(1, session.workspace().size());
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
