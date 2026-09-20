package io.github.fastformer.client.input;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientRequestTrackerTest {
   @Test
   void oneRequestOwnsTheTrackerUntilItIsCleared() {
      ClientRequestTracker tracker = new ClientRequestTracker();
      UUID transferId = UUID.randomUUID();

      assertTrue(tracker.beginPlacement(41L));
      assertFalse(tracker.beginPlacement(42L));
      assertFalse(tracker.beginWorkspace(transferId));
      assertTrue(tracker.ownsPlacement(41L));

      tracker.clear();

      assertTrue(tracker.beginWorkspace(transferId));
      assertFalse(tracker.beginPlacement(42L));
      assertTrue(tracker.ownsWorkspace(transferId));
   }

   @Test
   void onlyTheMatchingRequestTokenIsOwned() {
      ClientRequestTracker tracker = new ClientRequestTracker();
      UUID transferId = UUID.randomUUID();

      assertFalse(tracker.beginPlacement(0L));
      assertFalse(tracker.beginPlacement(-1L));
      assertFalse(tracker.beginWorkspace(null));
      assertTrue(tracker.beginWorkspace(transferId));

      assertFalse(tracker.ownsWorkspace(UUID.randomUUID()));
      assertFalse(tracker.ownsWorkspace(null));
      assertFalse(tracker.ownsPlacement(1L));
      assertTrue(tracker.ownsWorkspace(transferId));
   }

   @Test
   void clearRemovesTheWorldSessionOwner() {
      ClientRequestTracker tracker = new ClientRequestTracker();
      UUID oldTransferId = UUID.randomUUID();
      UUID newTransferId = UUID.randomUUID();

      assertTrue(tracker.beginWorkspace(oldTransferId));
      tracker.clear();

      assertFalse(tracker.active());
      assertFalse(tracker.ownsWorkspace(oldTransferId));
      assertTrue(tracker.beginWorkspace(newTransferId));
      assertFalse(tracker.ownsWorkspace(oldTransferId));
      assertTrue(tracker.ownsWorkspace(newTransferId));
   }
}
