package io.github.fastformer.client.input;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.client.input.policy.CancelInputSemantics;
import io.github.fastformer.client.input.state.ClientInputStateMachine;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class QueuedCancellationTest {
   @Test
   void capturedCancelDoesNotChangeRoutingUntilItsTurnInTheQueue() {
      var session = new ClientInputSession();
      session.routing.observe(ClientInputStateMachine.State.BUILDING);
      var cancel = CancelInputSemantics.decide(1, 81, true, true, true, false, false, false);
      session.postKeyboard(new KeyboardInputSnapshot(257, 0, 1, 0, 10, false, false));
      session.postKeyboard(new KeyboardInputSnapshot(81, 0, 1, 0, 20, false, false).withCancellation(cancel));
      assertEquals(ClientInputStateMachine.State.BUILDING, session.routing.state());
      var dispatched = new ArrayList<Integer>();
      session.drainPhysicalEvents(() -> true, key -> {
         dispatched.add(key.key());
         if (key.cancellation() == null) {
            assertTrue(session.routing.submit(1));
         } else {
            assertTrue(session.routing.awaitsPlacementRequest(1));
            assertTrue(session.cancel());
         }
      }, e -> fail());
      assertEquals(List.of(257, 81), dispatched);
      assertEquals(ClientInputStateMachine.State.CANCELLING, session.routing.state());
   }

   @Test
   void escapeSnapshotPreservesCaptureDataWhenOtherKeyDataIsAttached() {
      var decision = CancelInputSemantics.decideEscape(true, true, true, false, true, false);
      var escape = new KeyboardInputSnapshot(256, 0, 1, 0, 42, false, false)
         .withCancellation(decision).withQuickShapeSubmission(null);
      assertEquals(256, escape.key());
      assertEquals(42, escape.occurredAtNanos());
      assertSame(decision, escape.cancellation());
   }
}
