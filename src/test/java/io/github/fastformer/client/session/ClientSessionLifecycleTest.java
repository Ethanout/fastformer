package io.github.fastformer.client.session;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ClientSessionLifecycleTest {
   @Test
   void inactiveSnapshotDoesNotReplaceRetainedStateAtRuntimeBoundary() {
      ClientSessionLifecycle lifecycle = new ClientSessionLifecycle();
      lifecycle.observe(new Object(), new Object(), new Object(), ClientSessionState.QUICK_SHAPE);

      assertTrue(lifecycle.shouldHold(new ClientSessionSnapshot(false, false, false)));
      assertTrue(lifecycle.awaitingAuthoritativeSnapshot());
   }

   @Test
   void newerActiveSnapshotEndsTheBoundaryAndCanTakeOver() {
      ClientSessionLifecycle lifecycle = new ClientSessionLifecycle();
      lifecycle.observe(new Object(), new Object(), new Object(), ClientSessionState.QUICK_SHAPE);

      assertFalse(lifecycle.shouldHold(new ClientSessionSnapshot(true, false, false)));
      assertFalse(lifecycle.awaitingAuthoritativeSnapshot());
   }

   @Test
   void replacingTheRuntimeIdentityStartsANewBoundaryWithoutClearingState() {
      Object connection = new Object();
      Object level = new Object();
      Object player = new Object();
      ClientSessionLifecycle lifecycle = new ClientSessionLifecycle();
      lifecycle.observe(connection, level, player, ClientSessionState.SPECIAL_ITEM);
      lifecycle.shouldHold(new ClientSessionSnapshot(true, false, false));

      lifecycle.observe(connection, new Object(), new Object(), ClientSessionState.SPECIAL_ITEM);

      assertTrue(lifecycle.awaitingAuthoritativeSnapshot());
      assertTrue(lifecycle.shouldHold(new ClientSessionSnapshot(false, false, false)));
   }

   @Test
   void explicitDisconnectRetainsOnlyNonEmptyStateAsAwaiting() {
      ClientSessionLifecycle lifecycle = new ClientSessionLifecycle();
      lifecycle.markDisconnected(ClientSessionState.SPECIAL_SHAPE);

      assertTrue(lifecycle.awaitingAuthoritativeSnapshot());
      lifecycle.clearAwaiting();
      assertFalse(lifecycle.awaitingAuthoritativeSnapshot());
   }
}
