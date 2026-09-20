package io.github.fastformer.client.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ClientInputSessionKeyboardMailboxTest {
   @Test
   void physicalContextLossPreservesSubmissionReceipts() {
      var session = new ClientInputSession();
      session.routing.observe(ClientInputStateMachine.State.BUILDING);
      assertTrue(session.routing.submit(42L));
      session.postSubmissionCompleted(new ClientSemanticEvent.SubmissionCompleted(
         new ClientSemanticEvent.Submit.Placement(42L),
         ClientInputStateMachine.SubmissionEvent.SUCCEEDED,
         ClientInputStateMachine.State.BUILDING
      ));
      session.postScroll(new ScrollInputSnapshot(1));

      var delivered = new java.util.ArrayList<Object>();
      session.drainPhysicalEvents(() -> false, delivered::add, delivered::add);
      assertTrue(delivered.isEmpty());
      session.drainSubmissionEvents();
      assertEquals(ClientInputStateMachine.State.BUILDING, session.routing.state());
   }

   @Test
   void acceptedCancelDiscardsInputQueuedBeforeTheCancel() {
      var session = new ClientInputSession();
      session.routing.observe(ClientInputStateMachine.State.BUILDING);
      session.postKeyboard(KeyboardInputSnapshot.capture(257, 1, 1, 0, 1L, false, false, false, false));
      session.postScroll(new ScrollInputSnapshot(1));
      assertTrue(session.cancel());
      var delivered = new java.util.ArrayList<Object>();
      session.drainPhysicalEvents(() -> true, delivered::add, delivered::add);
      assertTrue(delivered.isEmpty());
   }

   @Test
   void cancelDuringDispatchDiscardsTheRestOfTheBatch() {
      var session = new ClientInputSession();
      session.routing.observe(ClientInputStateMachine.State.BUILDING);
      session.postKeyboard(KeyboardInputSnapshot.capture(65, 1, 1, 0, 1L, false, false, false, false));
      session.postScroll(new ScrollInputSnapshot(1));
      var delivered = new java.util.ArrayList<Object>();
      session.drainPhysicalEvents(() -> true, event -> {
         delivered.add(event);
         assertTrue(session.cancel());
      }, delivered::add);
      assertEquals(1, delivered.size());
   }

   @Test
   void rejectedCancelPreservesQueuedInput() {
      var session = new ClientInputSession();
      session.postScroll(new ScrollInputSnapshot(1));
      assertFalse(session.cancel());
      var delivered = new java.util.ArrayList<Object>();
      session.drainPhysicalEvents(() -> true, delivered::add, delivered::add);
      assertEquals(1, delivered.size());
   }

   @Test
   void draftRestoreWaitsUntilQueuedInputFinishesDispatch() {
      var session = new ClientInputSession();
      assertFalse(session.blocksDraftLoad());
      session.postKeyboard(KeyboardInputSnapshot.capture(65, 1, 1, 0, 1L, false, false, false, false));
      session.postScroll(new ScrollInputSnapshot(-1));

      assertTrue(session.blocksDraftLoad());
      session.drainPhysicalEvents(
         () -> true,
         event -> assertTrue(session.blocksDraftLoad()),
         event -> assertTrue(session.blocksDraftLoad())
      );
      assertFalse(session.blocksDraftLoad());
   }

   @Test
   void resetReleasesTheQueuedInputDraftRestoreGuard() {
      var session = new ClientInputSession();
      session.postScroll(new ScrollInputSnapshot(1));
      assertTrue(session.blocksDraftLoad());
      session.reset();
      assertFalse(session.blocksDraftLoad());
   }

   @Test
   void keyboardEventsDrainInPostingOrder() {
      var session = new ClientInputSession();
      session.postKeyboard(KeyboardInputSnapshot.capture(65, 1, 1, 0, 1L, false, false, false, false));
      session.postKeyboard(KeyboardInputSnapshot.capture(66, 2, 1, 0, 2L, false, false, false, false));
      var keys = new java.util.ArrayList<Integer>();
      session.drainPhysicalEvents(() -> true, event -> keys.add(event.key()), event -> { });
      assertEquals(java.util.List.of(65, 66), keys);
   }

   @Test
   void resetDiscardsQueuedKeyboardEvents() {
      var session = new ClientInputSession();
      session.postKeyboard(KeyboardInputSnapshot.capture(65, 1, 1, 0, 1L, false, false, false, false));
      session.reset();
      var called = new boolean[1];
      session.drainPhysicalEvents(() -> true, event -> called[0] = true, event -> called[0] = true);
      assertTrue(!called[0]);
   }

   @Test
   void keyboardAndScrollEventsShareOneChronologicalQueue() {
      var session = new ClientInputSession();
      session.postKeyboard(KeyboardInputSnapshot.capture(65, 1, 1, 0, 1L, false, false, false, false));
      session.postScroll(new ScrollInputSnapshot(-1));
      session.postKeyboard(KeyboardInputSnapshot.capture(66, 2, 1, 0, 3L, false, false, false, false));
      var values = new java.util.ArrayList<String>();
      session.drainPhysicalEvents(
         () -> true,
         event -> values.add("key:" + event.key()),
         event -> values.add("scroll:" + event.direction())
      );
      assertEquals(java.util.List.of("key:65", "scroll:-1", "key:66"), values);
   }

   @Test
   void contextLossDuringDispatchDiscardsKeyboardAndScrollRemainder() {
      var session = new ClientInputSession();
      var active = new boolean[] {true};
      var delivered = new java.util.ArrayList<Object>();
      session.postKeyboard(KeyboardInputSnapshot.capture(65, 1, 1, 0, 1L, false, false, false, false));
      session.postScroll(new ScrollInputSnapshot(1));
      session.postKeyboard(KeyboardInputSnapshot.capture(66, 2, 1, 0, 2L, false, false, false, false));

      session.drainPhysicalEvents(() -> active[0], event -> {
         delivered.add(event);
         active[0] = false;
      }, delivered::add);

      assertEquals(1, delivered.size());
      assertFalse(session.blocksDraftLoad());
      session.drainPhysicalEvents(() -> true, delivered::add, delivered::add);
      assertEquals(1, delivered.size());
      session.postScroll(new ScrollInputSnapshot(-1));
      session.drainPhysicalEvents(() -> true, delivered::add, delivered::add);
      assertEquals(2, delivered.size());
   }
}
