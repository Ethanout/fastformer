package io.github.fastformer.client.input;

import static org.junit.jupiter.api.Assertions.*;
import io.github.fastformer.client.quickshape.QuickShapeSubmissionIntentTest;
import io.github.fastformer.fastplace.placement.plan.PlacementGeometryPlan;
import io.github.fastformer.fastplace.quickshape.LineMode;
import java.util.ArrayDeque;
import org.junit.jupiter.api.Test;

class QuickShapeSubmissionSessionTest {
   @Test
   void sentQuickShapeRequestRetainsItsOriginUntilReceiptOrCancel() {
      var routing = new ClientInputStateMachine();
      routing.observe(ClientInputStateMachine.State.BUILDING);
      assertTrue(routing.submit(7));
      assertTrue(routing.ownsQuickShapeSubmission());
      routing.observe(ClientInputStateMachine.State.PLACING);
      assertTrue(routing.ownsQuickShapeSubmission());
      assertTrue(routing.cancel());
      assertFalse(routing.ownsQuickShapeSubmission());
      routing.reset();
      routing.observe(ClientInputStateMachine.State.ADJUSTING);
      assertTrue(routing.submit(8));
      assertFalse(routing.ownsQuickShapeSubmission());
   }

   @Test
   void acceptedCancelInvalidatesReadyResultAndSubmissionGate() {
      var session = new ClientInputSession();
      var snapshot = QuickShapeSubmissionIntentTest.snapshot(LineMode.AXIS);
      session.routing.observe(ClientInputStateMachine.State.BUILDING);
      assertTrue(session.routing.submit(7));
      session.quickShapeSubmission.begin(7, snapshot);
      session.quickShapeSubmission.calculate(new PlacementGeometryPlan(snapshot.points(), snapshot.data().modes(),
         false, snapshot.data().polygonVolumeShape(), 20, null), Runnable::run);
      assertTrue(session.blocksDraftLoad());
      for (var kind : new ClientInputStateMachine.InputKind[] {
         ClientInputStateMachine.InputKind.KEY, ClientInputStateMachine.InputKind.POINTER,
         ClientInputStateMachine.InputKind.SCROLL, ClientInputStateMachine.InputKind.SUBMIT
      }) assertEquals(ClientInputStateMachine.Dispatch.BLOCKED, session.routing.dispatch(kind));
      assertFalse(session.routing.submit(8));
      assertTrue(session.cancel());
      assertTrue(session.quickShapeSubmission.takeCompleted().isEmpty());
      assertFalse(session.routing.awaitsPlacementRequest(7));
   }

   @Test
   void resetDropsOldComputationAndCannotReopenItsRequest() {
      var session = new ClientInputSession();
      var snapshot = QuickShapeSubmissionIntentTest.snapshot(LineMode.AXIS);
      var executor = new ArrayDeque<Runnable>();
      session.routing.observe(ClientInputStateMachine.State.BUILDING);
      session.routing.submit(7);
      session.quickShapeSubmission.begin(7, snapshot);
      session.quickShapeSubmission.calculate(new PlacementGeometryPlan(snapshot.points(), snapshot.data().modes(),
         false, snapshot.data().polygonVolumeShape(), 20, null), executor::add);
      session.reset();
      executor.remove().run();
      assertFalse(session.quickShapeSubmission.active());
      assertFalse(session.routing.awaitsPlacementRequest(7));
      assertTrue(session.quickShapeSubmission.takeCompleted().isEmpty());
   }

   @Test
   void failureRequiresFreshEnterAndLateCompletionCannotReleaseNewRequest() {
      var session = new ClientInputSession();
      session.routing.observe(ClientInputStateMachine.State.BUILDING);
      session.routing.submit(7);
      session.postSubmissionCompleted(new ClientSemanticEvent.SubmissionCompleted(
         new ClientSemanticEvent.Submit.Placement(7), ClientInputStateMachine.SubmissionEvent.FAILED,
         ClientInputStateMachine.State.BUILDING));
      session.drainSubmissionEvents();
      assertEquals(ClientInputStateMachine.State.BUILDING, session.routing.state());
      assertFalse(session.routing.awaitsPlacementRequest(7));
      assertTrue(session.routing.submit(8));
      session.postSubmissionCompleted(new ClientSemanticEvent.SubmissionCompleted(
         new ClientSemanticEvent.Submit.Placement(7), ClientInputStateMachine.SubmissionEvent.SUCCEEDED,
         ClientInputStateMachine.State.IDLE));
      session.drainSubmissionEvents();
      assertTrue(session.routing.awaitsPlacementRequest(8));
   }
}
