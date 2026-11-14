package io.github.fastformer.client.session.scope;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import io.github.fastformer.client.operation.selection.ClientSelectionState;
import io.github.fastformer.protocol.s2c.operation.OperationPreviewPayload;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class ClientScopeRepositoryTest {
   @Test
   void operationsOwnTheirSelectionPreviewAndFailureState() {
      var repository = new ClientScopeRepository();
      UUID player = UUID.randomUUID();
      var a = repository.resolve(new ClientScopeId("a", "minecraft:overworld", player));
      var b = repository.resolve(new ClientScopeId("b", "minecraft:overworld", player));

      assertNotSame(a.operations(), b.operations());
      assertSame(a.workspace(), a.operations().workspace());
      assertSame(a.selectionSession().gestures(), a.operations().selectionGestures());
      assertTrue(a.operations().handleCreateClick(1, BlockPos.ZERO));
      assertTrue(a.operations().selectionDraftActive());
      assertFalse(b.operations().selectionDraftActive());
      assertTrue(a.operations().synchronize(OperationPreviewPayload.inactive(80)));
      assertTrue(b.operations().synchronize(OperationPreviewPayload.inactive(2)));
      assertFalse(a.operations().synchronize(OperationPreviewPayload.inactive(80)));
      assertFalse(a.operations().submitWorkspace(null));
      assertEquals("fallback", b.operations().lastOperationFailureKey("fallback"));
      assertEquals("fastformer.message.operation_submit_invalid", a.operations().lastOperationFailureKey("fallback"));
      assertNotSame(a.operations().sourceMask(), b.operations().sourceMask());
   }

   @Test
   void changingServersCancelsTransientOperationsAndRetainsTheOldDraft() {
      var repository = new ClientScopeRepository();
      UUID player = UUID.randomUUID();
      var aId = new ClientScopeId("a", "minecraft:overworld", player);
      var a = repository.activate(aId);
      assertTrue(a.operations().handleCreateClick(1, BlockPos.ZERO));
      a.operations().setAltMode(true);
      assertFalse(a.operations().submitWorkspace(null));

      var b = repository.activate(new ClientScopeId("b", "minecraft:overworld", player));
      assertSame(b.operations(), repository.operations());
      assertEquals(ClientSelectionState.POINTING, a.operations().interactionState());
      assertEquals("fallback", a.operations().lastOperationFailureKey("fallback"));
      assertTrue(a.operations().selectionDraftActive());
      assertFalse(b.operations().selectionDraftActive());
      assertSame(a.operations(), repository.activate(aId).operations());
      assertTrue(a.operations().selectionDraftActive());
   }

   @Test
   void firstActivationAcceptsItsInitialPreviewWithoutAFabricatedReconnect() {
      var repository = new ClientScopeRepository();
      var a = repository.activate(new ClientScopeId("a", "minecraft:overworld", UUID.randomUUID()));
      assertSame(a.operations(), repository.operations());
      assertTrue(a.operations().synchronize(OperationPreviewPayload.inactive(1)));
      assertFalse(a.operations().reconnectRestorePending());
   }

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
