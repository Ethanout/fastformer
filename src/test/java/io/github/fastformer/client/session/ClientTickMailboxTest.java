package io.github.fastformer.client.session;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ClientTickMailboxTest {
   @Test
   void postingDoesNotRunConsumerAndTickPreservesOrder() {
      var discarded = new ArrayList<String>();
      var received = new ArrayList<String>();
      var mailbox = new ClientTickMailbox<String>(discarded::add);
      mailbox.post("confirm");
      mailbox.post("cancel");
      assertTrue(received.isEmpty());
      mailbox.drain(received::add);
      assertEquals(List.of("confirm", "cancel"), received);
      assertTrue(discarded.isEmpty());
      assertEquals(0, mailbox.pendingCount());
   }

   @Test
   void eventsPostedDuringDispatchWaitForNextTick() {
      var mailbox = new ClientTickMailbox<String>(ignored -> fail("Unexpected discard"));
      var received = new ArrayList<String>();
      mailbox.post("first");
      mailbox.drain(event -> {
         received.add(event);
         mailbox.post("next");
      });
      assertEquals(List.of("first"), received);
      mailbox.drain(received::add);
      assertEquals(List.of("first", "next"), received);
   }

   @Test
   void environmentChangeDiscardsRemainingBatchAndPendingEventsExactlyOnce() {
      var discarded = new ArrayList<String>();
      var received = new ArrayList<String>();
      var mailbox = new ClientTickMailbox<String>(discarded::add);
      mailbox.post("detach");
      mailbox.post("old-result");
      mailbox.drain(event -> {
         received.add(event);
         mailbox.post("old-pending");
         mailbox.invalidate();
         mailbox.post("new-pending");
      });
      assertEquals(List.of("detach"), received);
      assertEquals(List.of("old-pending", "old-result"), discarded);
      mailbox.drain(received::add);
      assertEquals(List.of("detach", "new-pending"), received);
   }

   @Test
   void lateBackgroundResultCannotBorrowTheNewEnvironment() {
      var discarded = new ArrayList<String>();
      var mailbox = new ClientTickMailbox<String>(discarded::add);
      long owner = mailbox.epoch();
      mailbox.invalidate();
      assertFalse(mailbox.post(owner, "late"));
      assertEquals(List.of("late"), discarded);
      assertEquals(0, mailbox.pendingCount());
      assertTrue(mailbox.post(mailbox.epoch(), "current"));
   }

   @Test
   void recursiveDispatchFailsWithoutConsumingTheNextBatch() {
      var mailbox = new ClientTickMailbox<String>(ignored -> { });
      mailbox.post("first");
      mailbox.drain(event -> {
         mailbox.post("second");
         assertThrows(IllegalStateException.class, () -> mailbox.drain(ignored -> fail()));
      });
      var received = new ArrayList<String>();
      mailbox.drain(received::add);
      assertEquals(List.of("second"), received);
   }

   @Test
   void handlerFailureDiscardsUnprocessedEventsWithoutReplayingTheFailedOne() {
      var discarded = new ArrayList<String>();
      var mailbox = new ClientTickMailbox<String>(discarded::add);
      mailbox.post("failed");
      mailbox.post("unprocessed");
      assertThrows(IllegalStateException.class, () -> mailbox.drain(event -> {
         throw new IllegalStateException("handler failed");
      }));
      assertEquals(List.of("unprocessed"), discarded);
      mailbox.post("retry");
      var received = new ArrayList<String>();
      mailbox.drain(received::add);
      assertEquals(List.of("retry"), received);
   }

   @Test
   void cleanupFailurePreservesTheDispatchFailure() {
      var mailbox = new ClientTickMailbox<String>(event -> {
         throw new IllegalArgumentException("cleanup");
      });
      mailbox.post("failed");
      mailbox.post("unprocessed");
      var original = new IllegalStateException("dispatch");
      var thrown = assertThrows(IllegalStateException.class, () -> mailbox.drain(event -> {
         throw original;
      }));
      assertSame(original, thrown);
      assertEquals("cleanup", thrown.getSuppressed()[0].getMessage());
      assertDoesNotThrow(() -> mailbox.drain(event -> fail("Failed batch must not replay")));
   }

   @Test
   void cleanupFailureDoesNotSkipOtherResources() {
      var discarded = new ArrayList<String>();
      var mailbox = new ClientTickMailbox<String>(event -> {
         discarded.add(event);
         throw new IllegalStateException(event);
      });
      mailbox.post("first");
      mailbox.post("second");
      var error = assertThrows(IllegalStateException.class, mailbox::invalidate);
      assertEquals(List.of("first", "second"), discarded);
      assertEquals(1, error.getSuppressed().length);
      assertEquals(0, mailbox.pendingCount());
   }

   @Test
   void concurrentProducerCannotExtendAnActiveTickBatch() throws Exception {
      var mailbox = new ClientTickMailbox<Integer>(ignored -> fail("Unexpected discard"));
      mailbox.post(1);
      var received = new ArrayList<Integer>();
      var entered = new CountDownLatch(1);
      var posted = new CountDownLatch(1);
      try (var executor = Executors.newSingleThreadExecutor()) {
         var producer = executor.submit(() -> {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            mailbox.post(2);
            posted.countDown();
            return null;
         });
         mailbox.drain(event -> {
            received.add(event);
            entered.countDown();
            try {
               assertTrue(posted.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException exception) {
               Thread.currentThread().interrupt();
               throw new AssertionError(exception);
            }
         });
         producer.get(5, TimeUnit.SECONDS);
      }
      assertEquals(List.of(1), received);
      mailbox.drain(received::add);
      assertEquals(List.of(1, 2), received);
   }

   @Test
   void cleanupErrorDoesNotSkipRemainingResourcesOrMaskDispatchFailure() {
      var discarded = new ArrayList<String>();
      var cleanup = new AssertionError("cleanup");
      var mailbox = new ClientTickMailbox<String>(event -> {
         discarded.add(event);
         if (event.equals("broken")) throw cleanup;
      });
      mailbox.post("dispatch");
      mailbox.post("broken");
      mailbox.post("remaining");
      var dispatch = new IllegalStateException("dispatch");
      assertSame(dispatch, assertThrows(IllegalStateException.class,
         () -> mailbox.drain(event -> { throw dispatch; })));
      assertEquals(List.of("broken", "remaining"), discarded);
      assertArrayEquals(new Throwable[] {cleanup}, dispatch.getSuppressed());
      mailbox.post("next");
      var received = new ArrayList<String>();
      mailbox.drain(received::add);
      assertEquals(List.of("next"), received);
   }

   @Test
   void invalidationRethrowsCleanupErrorAfterReleasingRemainingEvents() {
      var discarded = new ArrayList<String>();
      var cleanup = new AssertionError("cleanup");
      var mailbox = new ClientTickMailbox<String>(event -> {
         discarded.add(event);
         if (event.equals("broken")) throw cleanup;
      });
      long previousEpoch = mailbox.epoch();
      mailbox.post("broken");
      mailbox.post("remaining");
      assertSame(cleanup, assertThrows(AssertionError.class, mailbox::invalidate));
      assertEquals(List.of("broken", "remaining"), discarded);
      assertNotEquals(previousEpoch, mailbox.epoch());
      assertEquals(0, mailbox.pendingCount());
   }
}
