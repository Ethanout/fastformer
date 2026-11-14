package io.github.fastformer.client.input.state;

import io.github.fastformer.client.mode.ClientModes;
import io.github.fastformer.client.mode.ModeObservation;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class SmartFillInputStateTest {
   @Test void previewOwnsInputAndCancelReturnsThroughIdle() {
      var machine = new ClientInputStateMachine();
      machine.observe(ClientModes.registry().observe(new ModeObservation(
         false, false, false, false, false, false, false, false, true)));
      assertEquals(ClientInputStateMachine.State.FILL_PREVIEW, machine.state());
      assertEquals(ClientInputStateMachine.Dispatch.BLOCKED, machine.dispatch(ClientInputStateMachine.InputKind.POINTER));
      assertEquals(ClientInputStateMachine.Dispatch.BLOCKED, machine.dispatch(ClientInputStateMachine.InputKind.SCROLL));
      assertTrue(machine.cancel());
      machine.observe(ClientInputStateMachine.State.FILL_PREVIEW);
      assertEquals(ClientInputStateMachine.State.CANCELLING, machine.state());
      machine.observe(ClientInputStateMachine.State.IDLE);
      assertTrue(machine.routesToVanilla(ClientInputStateMachine.InputKind.INTERACTION));
   }
   @Test void worldTasksOwnInputAfterFillConfirmation() {
      assertEquals(ClientInputStateMachine.State.PLACING, ClientModes.registry().observe(new ModeObservation(
         false, true, false, false, false, false, false, false, true)));
      assertEquals(ClientInputStateMachine.State.RESTORING, ClientModes.registry().observe(new ModeObservation(
         true, true, false, false, false, false, false, false, true)));
   }
}
