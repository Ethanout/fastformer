package io.github.fastformer.client.input.state;

import static org.junit.jupiter.api.Assertions.*;
import io.github.fastformer.fastplace.settings.ReachThresholds;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class IdleSessionStateTest {
   @Test
   void qOutsideFarRequiresEnteringAndLeavingFarWithoutReachingClose() {
      var machine = new ClientInputStateMachine();
      machine.onEvent(new ClientSemanticEvent.SuspendIdle(false));
      observeDistance(machine, 50, 20);
      assertEquals(ClientInputStateMachine.State.IDLE_WAITING_FOR_FAR_ENTRY, machine.state());
      observeDistance(machine, 10, 20);
      assertEquals(ClientInputStateMachine.State.IDLE_WAITING_FOR_FAR_EXIT, machine.state());
      observeDistance(machine, 21, 20);
      assertEquals(ClientInputStateMachine.State.IDLE, machine.state());
   }

   @Test
   void qInsideFarUsesTheCurrentReachAndIncludesTheBoundary() {
      var machine = new ClientInputStateMachine();
      machine.onEvent(new ClientSemanticEvent.SuspendIdle(true));
      observeDistance(machine, 30, 30);
      assertTrue(machine.state().idleSuspended());
      observeDistance(machine, 30.01, 30);
      assertEquals(ClientInputStateMachine.State.IDLE, machine.state());
      machine.onEvent(new ClientSemanticEvent.SuspendIdle(false));
      observeDistance(machine, 50, 30);
      assertEquals(ClientInputStateMachine.State.IDLE_WAITING_FOR_FAR_ENTRY, machine.state());
   }

   @Test
   void idleSnapshotsCannotResumeASuspendedSession() {
      var machine = new ClientInputStateMachine();
      machine.onEvent(new ClientSemanticEvent.SuspendIdle(false));
      machine.observe(ClientInputStateMachine.State.IDLE);
      assertTrue(machine.state().idleSuspended());
      assertFalse(machine.startPlacement(1));
      assertFalse(machine.submit(2));
      assertFalse(machine.cancel());
      assertEquals(0L, machine.beginGesture(1));
      assertEquals(ClientInputStateMachine.Dispatch.VANILLA_ONLY, machine.dispatch(ClientInputStateMachine.InputKind.POINTER));
      assertEquals(ClientInputStateMachine.Dispatch.BLOCKED, machine.dispatch(ClientInputStateMachine.InputKind.PASTE_WORKSPACE));
   }

   @Test
   void anActiveSessionReplacesTheIdleStateAndCannotBeSuspended() {
      var machine = new ClientInputStateMachine();
      machine.onEvent(new ClientSemanticEvent.SuspendIdle(false));
      machine.observe(ClientInputStateMachine.State.SELECTING);
      assertFalse(machine.state().idleSuspended());
      assertInstanceOf(InteractionTransition.Rejected.class, machine.onEvent(new ClientSemanticEvent.SuspendIdle(true)));
      observeDistance(machine, 100, 20);
      assertEquals(ClientInputStateMachine.State.SELECTING, machine.state());
   }

   @Test
   void lifecycleResetAndTransitionsPublishOneAcceptedStateChange() {
      var changes = new ArrayList<ClientInputStateMachine.State>();
      var machine = new ClientInputStateMachine((oldState, newState) -> changes.add(newState));
      machine.onEvent(new ClientSemanticEvent.SuspendIdle(false));
      machine.observe(ClientInputStateMachine.State.IDLE);
      observeDistance(machine, 50, 20);
      assertEquals(1, changes.size());
      observeDistance(machine, 10, 20);
      observeDistance(machine, 10, 20);
      assertEquals(2, changes.size());
      machine.reset();
      assertEquals(ClientInputStateMachine.State.IDLE, changes.getLast());
      assertFalse(machine.state().idleSuspended());
   }

   private static void observeDistance(ClientInputStateMachine machine, double distance, double far) {
      machine.onEvent(new ClientSemanticEvent.ObserveReach(ReachThresholds.DEFAULT.vanillaAt(true, distance, far)));
   }
}
