package io.github.fastformer.client.input;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SubmissionCompletedEventTest {
   @Test
   void malformedSuccessCannotEnterTheMailboxAndDiscardAValidCompletion() {
      var session = new ClientInputSession();
      session.routing.observe(ClientInputStateMachine.State.BUILDING);
      assertTrue(session.routing.submit(42L));
      var request = new ClientSemanticEvent.Submit.Placement(42L);
      assertThrows(IllegalArgumentException.class, () -> session.postSubmissionCompleted(
         new ClientSemanticEvent.SubmissionCompleted(request,
            ClientInputStateMachine.SubmissionEvent.SUCCEEDED, ClientInputStateMachine.State.SUBMITTING)));
      assertThrows(NullPointerException.class, () -> session.postSubmissionCompleted(
         new ClientSemanticEvent.SubmissionCompleted(request,
            ClientInputStateMachine.SubmissionEvent.SUCCEEDED, null)));
      session.postSubmissionCompleted(new ClientSemanticEvent.SubmissionCompleted(request,
         ClientInputStateMachine.SubmissionEvent.SUCCEEDED, ClientInputStateMachine.State.BUILDING));
      session.drainSubmissionEvents();
      assertEquals(ClientInputStateMachine.State.BUILDING, session.routing.state());
   }

   @Test
   void failureDoesNotRequireAnObservedState() {
      var session = new ClientInputSession();
      session.routing.observe(ClientInputStateMachine.State.ADJUSTING);
      assertTrue(session.routing.submit(42L));
      session.postSubmissionCompleted(new ClientSemanticEvent.SubmissionCompleted(
         new ClientSemanticEvent.Submit.Placement(42L), ClientInputStateMachine.SubmissionEvent.FAILED, null));
      session.drainSubmissionEvents();
      assertEquals(ClientInputStateMachine.State.ADJUSTING, session.routing.state());
   }
}
