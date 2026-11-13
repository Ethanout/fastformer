package io.github.fastformer.server.task.block;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fastformer.server.recovery.JournalPreparation;
import io.github.fastformer.server.recovery.WorldJournalPreparation;
import java.util.Optional;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;

class RecoveryJournalPreparationTest {
   @Test
   void rejectedSubmissionFailsWithoutLosingTheCancellationBoundary() {
      WorldJournalPreparation preparation = new WorldJournalPreparation(task -> {
         throw new java.util.concurrent.RejectedExecutionException("stopped worker");
      });
      assertEquals(JournalPreparation.FAILED, preparation.poll(Optional::empty));
      assertEquals("RejectedExecutionException", preparation.failureReason());
      assertTrue(preparation.completion().isDone());
      preparation.cancel();
      assertEquals(JournalPreparation.FAILED, preparation.poll(Optional::empty));
   }

   @Test
   void unboundPreparationReportsItsMissingContext() {
      WorldJournalPreparation preparation = new WorldJournalPreparation();
      assertEquals(JournalPreparation.FAILED, preparation.poll(Optional::empty));
      assertEquals("IllegalStateException", preparation.failureReason());
   }
   @Test
   void emptyJournalResultBecomesAnExplicitFailure() {
      WorldJournalPreparation preparation = new WorldJournalPreparation(Runnable::run);

      assertEquals(JournalPreparation.FAILED, preparation.poll(Optional::empty));
      assertTrue(preparation.started());
      assertEquals(JournalPreparation.FAILED, preparation.poll(Optional::empty));
      assertEquals("journal creation returned empty", preparation.failureReason());
   }

   @Test
   void asynchronousExceptionKeepsItsCauseName() {
      WorldJournalPreparation preparation = new WorldJournalPreparation(Runnable::run);

      assertEquals(JournalPreparation.FAILED, preparation.poll(() -> {
         throw new IllegalStateException("broken journal");
      }));
      assertEquals(JournalPreparation.FAILED, preparation.poll(Optional::empty));
      assertEquals("IllegalStateException", preparation.failureReason());
   }

   @Test
   void asynchronousOutOfMemoryKeepsItsCauseName() {
      WorldJournalPreparation preparation = new WorldJournalPreparation(Runnable::run);

      assertEquals(JournalPreparation.FAILED, preparation.poll(() -> {
         throw new OutOfMemoryError("broken journal allocation");
      }));
      assertEquals(JournalPreparation.FAILED, preparation.poll(Optional::empty));
      assertEquals("OutOfMemoryError", preparation.failureReason());
   }

   @Test
   void genuinelyAsynchronousJournalStillRemainsPending() {
      var queued = new java.util.ArrayDeque<Runnable>();
      Executor executor = queued::addLast;
      WorldJournalPreparation preparation = new WorldJournalPreparation(executor);

      assertEquals(JournalPreparation.PENDING, preparation.poll(Optional::empty));
      assertEquals(1, queued.size());
      queued.removeFirst().run();
      assertEquals(JournalPreparation.FAILED, preparation.poll(Optional::empty));
   }

}
