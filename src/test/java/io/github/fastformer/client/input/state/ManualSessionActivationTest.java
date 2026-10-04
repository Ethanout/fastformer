package io.github.fastformer.client.input.state;

import static org.junit.jupiter.api.Assertions.*;
import io.github.fastformer.client.input.policy.CancelInputSemantics;
import io.github.fastformer.fastplace.session.IdleSessionState;
import io.github.fastformer.fastplace.session.SessionActivationMode;
import org.junit.jupiter.api.Test;

class ManualSessionActivationTest {
   @Test void defaultClientPreferenceIsManual() {
      assertEquals(SessionActivationMode.MANUAL, io.github.fastformer.client.FastFormerClientConfig.activationMode());
   }

   @Test void inspectingASettingChangeDoesNotChangeThePreference() {
      var machine = new ClientInputStateMachine();
      assertInstanceOf(InteractionTransition.Switch.class, machine.inspect(new ClientSemanticEvent.ConfigureActivation(SessionActivationMode.MANUAL)));
      assertEquals(IdleSessionState.READY, machine.idleState());
   }
   private static ClientInputStateMachine manual() {
      return new ClientInputStateMachine(SessionActivationMode.MANUAL, (previous, next) -> { });
   }

   @Test void manualStartsInVanillaAndDistanceCannotActivateIt() {
      var machine = manual();
      assertEquals(ClientInputStateMachine.State.IDLE_MANUAL, machine.state());
      machine.onEvent(new ClientSemanticEvent.ObserveReach(true));
      machine.onEvent(new ClientSemanticEvent.ObserveReach(false));
      machine.observe(ClientInputStateMachine.State.IDLE);
      assertEquals(IdleSessionState.MANUAL_VANILLA, machine.idleState());
      assertFalse(machine.startPlacement(1));
   }

   @Test void qOnlyEntersWhenVanillaCannotDropAMainHandItem() {
      assertTrue(CancelInputSemantics.manualEntry(1, 81, ClientInputStateMachine.State.IDLE_MANUAL, true));
      assertFalse(CancelInputSemantics.manualEntry(1, 81, ClientInputStateMachine.State.IDLE_MANUAL, false));
      assertFalse(CancelInputSemantics.manualEntry(0, 81, ClientInputStateMachine.State.IDLE_MANUAL, true));
      assertFalse(CancelInputSemantics.manualEntry(2, 81, ClientInputStateMachine.State.IDLE_MANUAL, true));
      assertFalse(CancelInputSemantics.manualEntry(1, 81, ClientInputStateMachine.State.SELECTING, true));
   }

   @Test void manualEntryOwnsInputAtEveryDistanceAndQReturnsToVanilla() {
      var machine = manual();
      machine.onEvent(ClientSemanticEvent.EnterManual.INSTANCE);
      assertEquals(IdleSessionState.MANUAL_FF, machine.idleState());
      assertFalse(machine.idleState().vanillaOwnsInput(true));
      machine.onEvent(new ClientSemanticEvent.SuspendIdle(true));
      assertEquals(IdleSessionState.MANUAL_VANILLA, machine.idleState());
      machine.onEvent(new ClientSemanticEvent.ObserveReach(false));
      assertTrue(machine.state().idleSuspended());
   }

   @Test void modeChangeDoesNotDiscardAnActiveSession() {
      var machine = new ClientInputStateMachine();
      machine.observe(ClientInputStateMachine.State.BUILDING);
      machine.onEvent(new ClientSemanticEvent.ConfigureActivation(SessionActivationMode.MANUAL));
      assertEquals(ClientInputStateMachine.State.BUILDING, machine.state());
      assertEquals(IdleSessionState.MANUAL_FF, machine.idleState());
      assertInstanceOf(InteractionTransition.Rejected.class, machine.onEvent(ClientSemanticEvent.EnterManual.INSTANCE));
      machine.observe(ClientInputStateMachine.State.IDLE);
      assertEquals(IdleSessionState.MANUAL_FF, machine.idleState());
      machine.reset();
      assertEquals(IdleSessionState.MANUAL_VANILLA, machine.idleState());
   }

   @Test void switchingBackToAutomaticRestoresTheFarTransition() {
      var machine = manual();
      machine.onEvent(new ClientSemanticEvent.ConfigureActivation(SessionActivationMode.AUTOMATIC));
      assertEquals(ClientInputStateMachine.State.IDLE, machine.state());
      assertTrue(machine.idleState().vanillaOwnsInput(true));
      machine.onEvent(new ClientSemanticEvent.SuspendIdle(false));
      machine.onEvent(new ClientSemanticEvent.ObserveReach(true));
      machine.onEvent(new ClientSemanticEvent.ObserveReach(false));
      assertEquals(IdleSessionState.READY, machine.idleState());
   }
}
