package io.github.fastformer.client.operation.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fastformer.fastplace.FastPlaceActivity;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorkspaceSubmissionTrackerTest {
   @Test
   void expiresWhenTheServerNeverStartsTheTask() {
      WorkspaceSubmissionTracker tracker = tracker();
      tracker.begin(UUID.randomUUID());

      assertFalse(tick(tracker, 599));
      assertTrue(tracker.tick());
   }

   @Test
   void activeServerTaskDisablesTheStartTimeout() {
      WorkspaceSubmissionTracker tracker = tracker();
      tracker.begin(UUID.randomUUID());
      tracker.observeActivity(FastPlaceActivity.OPERATION_TASK);

      assertFalse(tick(tracker, 1_200));
      assertEquals(WorkspaceSubmissionTracker.Phase.TASK_ACTIVE, tracker.phase());
   }

   @Test
   void taskExitStartsAResultGracePeriod() {
      WorkspaceSubmissionTracker tracker = tracker();
      tracker.begin(UUID.randomUUID());
      tracker.observeActivity(FastPlaceActivity.OPERATION_TASK);
      tracker.observeActivity(FastPlaceActivity.NONE);

      assertFalse(tick(tracker, 39));
      assertTrue(tracker.tick());
   }

   @Test
   void onlyTheCurrentTransferResultIsAccepted() {
      WorkspaceSubmissionTracker tracker = tracker();
      UUID current = UUID.randomUUID();
      tracker.begin(current);

      assertTrue(tracker.accepts(current));
      assertFalse(tracker.accepts(UUID.randomUUID()));
      tracker.clear();
      assertFalse(tracker.accepts(current));
   }

   @Test
   void resultMustBelongToTheCurrentInteractionOwner() {
      WorkspaceSubmissionTracker tracker = tracker();
      UUID transfer = UUID.randomUUID();
      UUID owner = UUID.randomUUID();
      tracker.begin(transfer, null, owner);

      assertTrue(tracker.accepts(transfer, owner));
      assertFalse(tracker.accepts(transfer, UUID.randomUUID()));
      assertTrue(tracker.pending(owner));
      assertFalse(tracker.pending(UUID.randomUUID()));
   }

   private static WorkspaceSubmissionTracker tracker() {
      return new WorkspaceSubmissionTracker(600, 40);
   }

   private static boolean tick(WorkspaceSubmissionTracker tracker, int count) {
      boolean expired = false;
      for (int tick = 0; tick < count; tick++) {
         expired |= tracker.tick();
      }
      return expired;
   }
}
