package io.github.fastformer.fastplace.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.ArrayDeque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class WorldTaskContextTest {
   @Test
   void journalCompletionQueuesServiceWithoutWaitingForAnotherTick() {
      ArrayDeque<Runnable> mainThread = new ArrayDeque<>();
      CompletableFuture<Void> journal = new CompletableFuture<>();
      AtomicInteger services = new AtomicInteger();
      WorldTaskContext.resumeAfter(journal, mainThread::addLast, () -> true, services::incrementAndGet);

      assertEquals(0, mainThread.size());
      journal.complete(null);
      assertEquals(0, services.get());
      mainThread.removeFirst().run();
      assertEquals(1, services.get());
      assertEquals(0, mainThread.size());
   }

   @Test
   void cancelledOrAlreadyServicedJournalDoesNotResume() {
      ArrayDeque<Runnable> mainThread = new ArrayDeque<>();
      CompletableFuture<Void> journal = new CompletableFuture<>();
      AtomicBoolean waiting = new AtomicBoolean(true);
      AtomicInteger services = new AtomicInteger();
      WorldTaskContext.resumeAfter(journal, mainThread::addLast, waiting::get, services::incrementAndGet);

      journal.complete(null);
      waiting.set(false);
      mainThread.removeFirst().run();

      assertEquals(0, services.get());
   }

   @Test
   void journalFailureAlsoWakesItsOwnerForRecovery() {
      ArrayDeque<Runnable> mainThread = new ArrayDeque<>();
      CompletableFuture<Void> journal = new CompletableFuture<>();
      AtomicInteger services = new AtomicInteger();
      WorldTaskContext.resumeAfter(journal, mainThread::addLast, () -> true, services::incrementAndGet);

      journal.completeExceptionally(new IllegalStateException("disk failure"));
      mainThread.removeFirst().run();

      assertEquals(1, services.get());
   }
}
