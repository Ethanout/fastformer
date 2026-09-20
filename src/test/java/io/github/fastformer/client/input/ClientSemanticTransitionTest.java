package io.github.fastformer.client.input;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ClientSemanticTransitionTest {
   @Test
   void inspectingSubmitDoesNotAcquireTheRequestOrExitTheCurrentState() {
      ClientInputStateMachine machine = adjusting();
      long gesture = machine.beginGesture(1);
      long generation = machine.generation();
      var submit = new ClientSemanticEvent.Submit.Workspace(UUID.randomUUID());

      var decision = assertInstanceOf(InteractionTransition.Switch.class, machine.inspect(submit));
      assertEquals(ClientInputStateMachine.State.SUBMITTING, decision.target());
      assertEquals(InteractionTransition.Cause.SUBMIT, decision.cause());
      assertSame(submit, decision.submission());
      assertEquals(ClientInputStateMachine.State.ADJUSTING, machine.state());
      assertEquals(generation, machine.generation());
      assertTrue(machine.accepts(gesture));
      assertFalse(machine.completeSubmission(submit.transferId(), ClientInputStateMachine.SubmissionEvent.FAILED, null));

      assertEquals(decision, machine.onEvent(submit));
      assertEquals(generation + 1, machine.generation());
      assertFalse(machine.accepts(gesture));
      assertTrue(machine.completeSubmission(submit.transferId(), ClientInputStateMachine.SubmissionEvent.FAILED, null));
   }

   @Test
   void inspectingCompletionAndCancelRetainsTheOriginalRequestOwner() {
      ClientInputStateMachine machine = adjusting();
      var submit = new ClientSemanticEvent.Submit.Placement(14L);
      machine.onEvent(submit);
      long generation = machine.generation();
      var completion = new ClientSemanticEvent.SubmissionCompleted(
         submit, ClientInputStateMachine.SubmissionEvent.SUCCEEDED, ClientInputStateMachine.State.PLACING);

      assertInstanceOf(InteractionTransition.Switch.class, machine.inspect(completion));
      assertInstanceOf(InteractionTransition.Switch.class, machine.inspect(ClientSemanticEvent.Cancel.INSTANCE));
      assertEquals(ClientInputStateMachine.State.SUBMITTING, machine.state());
      assertEquals(generation, machine.generation());
      assertInstanceOf(InteractionTransition.Switch.class, machine.onEvent(completion));
      assertEquals(ClientInputStateMachine.State.PLACING, machine.state());
      assertEquals(InteractionTransition.Rejection.STALE_REQUEST, rejection(machine.onEvent(completion)));
   }

   @Test
   void eachEventUsesTheStateLeftByThePreviousEvent() {
      ClientInputStateMachine machine = adjusting();
      long generation = machine.generation();
      var submit = new ClientSemanticEvent.Submit.Placement(1L);
      assertInstanceOf(InteractionTransition.Switch.class, machine.onEvent(submit));
      assertInstanceOf(InteractionTransition.Switch.class, machine.onEvent(ClientSemanticEvent.Cancel.INSTANCE));
      assertEquals(InteractionTransition.Rejection.INPUT_BLOCKED, rejection(machine.onEvent(submit)));
      assertEquals(ClientInputStateMachine.State.CANCELLING, machine.state());
      assertEquals(generation + 2, machine.generation());
      assertSame(InteractionTransition.Stay.INSTANCE,
         machine.onEvent(new ClientSemanticEvent.Observe(ClientInputStateMachine.State.ADJUSTING)));
      assertEquals(generation + 2, machine.generation());
   }

   @Test
   void anInspectedDecisionCannotAuthorizeAnEventAfterCancellation() {
      ClientInputStateMachine machine = adjusting();
      var submit = new ClientSemanticEvent.Submit.Placement(1L);
      assertInstanceOf(InteractionTransition.Switch.class, machine.inspect(submit));
      machine.cancel();

      assertEquals(InteractionTransition.Rejection.INPUT_BLOCKED, rejection(machine.onEvent(submit)));
      assertEquals(ClientInputStateMachine.State.CANCELLING, machine.state());
   }

   @Test
   void invalidSubmissionPreservesGestureAndAllowsAValidRetry() {
      ClientInputStateMachine machine = adjusting();
      long gesture = machine.beginGesture(0);
      long generation = machine.generation();
      assertEquals(InteractionTransition.Rejection.INVALID_REQUEST,
         rejection(machine.onEvent(new ClientSemanticEvent.Submit.Placement(0L))));
      assertEquals(generation, machine.generation());
      assertTrue(machine.accepts(gesture));
      assertTrue(machine.submit(1L));
   }

   @Test
   void invalidCompletionFailsBeforeExitAndKeepsTheRequest() {
      ClientInputStateMachine machine = adjusting();
      assertTrue(machine.submit(1L));
      long generation = machine.generation();
      assertThrows(IllegalArgumentException.class, () -> machine.onEvent(new ClientSemanticEvent.SubmissionCompleted(
         new ClientSemanticEvent.Submit.Placement(1L),
         ClientInputStateMachine.SubmissionEvent.SUCCEEDED, ClientInputStateMachine.State.SUBMITTING)));
      assertEquals(generation, machine.generation());
      assertEquals(ClientInputStateMachine.State.SUBMITTING, machine.state());
      assertTrue(machine.completeSubmission(1L, ClientInputStateMachine.SubmissionEvent.FAILED, null));
   }

   @Test
   void observationDoesNotReenterButResetReleasesAnIdleGesture() {
      ClientInputStateMachine machine = new ClientInputStateMachine();
      long gesture = machine.beginGesture(0);
      long generation = machine.generation();
      assertSame(InteractionTransition.Stay.INSTANCE,
         machine.onEvent(new ClientSemanticEvent.Observe(ClientInputStateMachine.State.IDLE)));
      assertTrue(machine.accepts(gesture));
      var reset = assertInstanceOf(InteractionTransition.Switch.class,
         machine.onEvent(ClientSemanticEvent.Reset.INSTANCE));
      assertEquals(InteractionTransition.Cause.RESET, reset.cause());
      assertEquals(generation + 1, machine.generation());
      assertFalse(machine.accepts(gesture));
   }

   private static ClientInputStateMachine adjusting() {
      ClientInputStateMachine machine = new ClientInputStateMachine();
      machine.observe(ClientInputStateMachine.State.ADJUSTING);
      return machine;
   }

   private static InteractionTransition.Rejection rejection(InteractionTransition transition) {
      return assertInstanceOf(InteractionTransition.Rejected.class, transition).reason();
   }
}
