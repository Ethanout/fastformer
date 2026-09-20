package io.github.fastformer.client.input;

import io.github.fastformer.client.input.mouse.MouseDragReleaseSemantics;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class PointerReleaseMailboxTest {
   private static final MouseDragReleaseSemantics.Target TARGET = MouseDragReleaseSemantics.Target.GEOMETRY_CAPTURE;

   @Test
   void releaseKeepsPhysicalTimeAndRunsOnceAfterEarlierScroll() {
      var session = session();
      var release = release(session);
      var delivered = new ArrayList<Object>();
      session.postScroll(new ScrollInputSnapshot(1));
      session.postPointerRelease(release);
      session.postPointerRelease(release);
      assertTrue(session.routing.accepts(release.clickToken()));
      session.drainPhysicalEvents(() -> true, delivered::add, delivered::add,
         delivered::add, delivered::add, event -> event.dispatch(session, TARGET,
            () -> delivered.add(event.occurredAtNanos())));
      assertEquals(java.util.List.of(new ScrollInputSnapshot(1), 123L), delivered);
      assertEquals(0L, session.clickGestureToken);
   }

   @Test
   void replacementClickRejectsOldReleaseWithoutEndingNewGesture() {
      var session = session();
      var old = release(session);
      session.clickGestureToken = session.routing.beginGesture(1);
      assertFalse(old.dispatch(session, TARGET, () -> fail("Old release executed")));
      assertTrue(session.routing.accepts(session.clickGestureToken));
   }

   @Test
   void replacementPointerRejectsOldReleaseEvenWithSameClick() {
      var session = session();
      var old = release(session);
      session.pointerGestureToken++;
      assertFalse(old.dispatch(session, TARGET, () -> fail("Old release executed")));
      assertTrue(session.routing.accepts(session.clickGestureToken));
   }

   @Test
   void wrongButtonOrChangedTargetCannotFinishGesture() {
      var session = session();
      var wrongButton = new PointerReleaseSnapshot(0, 123L, session.clickGestureToken,
         session.pointerGestureToken, TARGET);
      assertFalse(wrongButton.dispatch(session, TARGET, () -> fail("Wrong button executed")));
      assertFalse(release(session).dispatch(session, MouseDragReleaseSemantics.Target.OPERATION_CAPTURE,
         () -> fail("Changed target executed")));
      assertTrue(session.routing.accepts(session.clickGestureToken));
   }

   @Test
   void productionButtonCheckKeepsMismatchedReleaseOnSynchronousPath() {
      var session = session();
      assertFalse(session.routing.accepts(0, session.clickGestureToken));
      assertTrue(session.routing.accepts(1, session.clickGestureToken));
   }

   @Test
   void cancelAheadOfReleaseDiscardsRemainingBatch() {
      var session = session();
      session.postScroll(new ScrollInputSnapshot(1));
      session.postPointerRelease(release(session));
      session.drainPhysicalEvents(() -> true, event -> fail(), event -> assertTrue(session.cancel()),
         event -> fail(), event -> fail(), event -> fail("Cancelled release delivered"));
   }

   @Test
   void lostContextDiscardsRelease() {
      var session = session();
      session.postPointerRelease(release(session));
      session.drainPhysicalEvents(() -> false, event -> fail(), event -> fail(),
         event -> fail(), event -> fail(), event -> fail("Invalid context delivered"));
      session.drainPhysicalEvents(() -> true, event -> fail(), event -> fail(),
         event -> fail(), event -> fail(), event -> fail("Discarded release replayed"));
   }

   private static ClientInputSession session() {
      var session = new ClientInputSession();
      session.routing.observe(ClientInputStateMachine.State.GEOMETRY);
      session.clickGestureToken = session.routing.beginGesture(1);
      session.pointerGestureToken = 10L;
      return session;
   }

   private static PointerReleaseSnapshot release(ClientInputSession session) {
      return new PointerReleaseSnapshot(1, 123L, session.clickGestureToken, session.pointerGestureToken, TARGET);
   }
}
