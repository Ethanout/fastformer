package io.github.fastformer.client.input;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.client.input.state.ClientInputStateMachine;
import io.github.fastformer.client.input.state.PointerGestureState;
import io.github.fastformer.client.session.scope.ClientScope;
import io.github.fastformer.client.session.scope.ClientScopeId;
import io.github.fastformer.client.session.scope.ClientScopeRepository;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class ClientScopeInputBoundaryTest {
   @Test
   void scopesKeepSeparateOwnersAndReentryDoesNotRestoreSubmissionWait() {
      var manager = new ClientScopeRepository();
      UUID playerId = UUID.randomUUID();
      var overworld = activate(manager, playerId, "server", "overworld");
      var oldInput = overworld.inputSession();
      oldInput.routing.observe(ClientInputStateMachine.State.ADJUSTING);
      assertTrue(oldInput.routing.submit(UUID.randomUUID()));
      oldInput.pointerGesture.begin(PointerGestureState.Kind.OPERATION_POINT);
      assertSame(overworld, activate(manager, playerId, "server", "overworld"));
      assertEquals(ClientInputStateMachine.State.SUBMITTING, oldInput.routing.state());

      var nether = activate(manager, playerId, "server", "nether");
      assertNotSame(oldInput, nether.inputSession());
      assertEquals(ClientInputStateMachine.State.IDLE, oldInput.routing.state());
      assertEquals(PointerGestureState.Kind.NONE, oldInput.pointerGesture.kind());
      nether.inputSession().routing.observe(ClientInputStateMachine.State.BUILDING);
      manager.reclaimInactive();
      manager.reclaimInactive();
      var reentered = activate(manager, playerId, "server", "overworld");
      assertNotSame(overworld, reentered);
      assertEquals(ClientInputStateMachine.State.IDLE, reentered.inputSession().routing.state());
      assertEquals(ClientInputStateMachine.State.IDLE, oldInput.routing.state());
      assertEquals(ClientInputStateMachine.State.IDLE, nether.inputSession().routing.state());
   }

   @Test
   void changingPlayerOrServerClearsInputWithoutDiscardingLocalWork() {
      var manager = new ClientScopeRepository();
      UUID playerId = UUID.randomUUID();
      var first = activate(manager, playerId, "a", "overworld");
      first.selectionSession().addDraftPoint(BlockPos.ZERO);
      first.inputSession().routing.observe(ClientInputStateMachine.State.BUILDING);
      var second = activate(manager, UUID.randomUUID(), "a", "overworld");
      assertEquals(ClientInputStateMachine.State.IDLE, first.inputSession().routing.state());
      assertTrue(first.selectionSession().hasDraft());
      second.inputSession().modifier.press(100, true, true);
      var third = activate(manager, playerId, "b", "overworld");
      assertFalse(second.inputSession().modifier.held());
      assertNotSame(first.inputSession(), third.inputSession());
   }

   @Test
   void unboundInputAndSelectionShareOneOwnerBeforeFirstWorld() {
      var manager = new ClientScopeRepository();
      var input = manager.inputSession();
      var selection = manager.selectionSession();
      assertNull(manager.current());
      assertSame(manager.operations().selectionGestures(), input.selectionGestures());
      input.operationTransformCapture = new ClientInputSession.OperationTransformCapture(1L, 1L,
         io.github.fastformer.protocol.callback.OperationCallbackScope.unscoped());
      selection.addDraftPoint(BlockPos.ZERO);
      manager.clearUnboundInteraction();
      assertNull(input.operationTransformCapture);
      assertFalse(selection.hasDraft());
      assertSame(input, manager.inputSession());
      var player = activate(manager, UUID.randomUUID(), "server", "overworld");
      assertSame(player.inputSession(), manager.inputSession());
      assertSame(player.selectionSession(), manager.selectionSession());
      assertSame(manager.operations().selectionGestures(), manager.inputSession().selectionGestures());
   }

   private static ClientScope activate(ClientScopeRepository repository, UUID player, String server, String dimension) {
      return repository.activate(new ClientScopeId(server, dimension, player));
   }
}
