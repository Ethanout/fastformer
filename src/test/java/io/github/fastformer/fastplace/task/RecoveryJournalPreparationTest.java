package io.github.fastformer.fastplace.task;

import io.github.fastformer.fastplace.world.WorldJournalPreparation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fastformer.fastplace.world.JournalPreparation;
import java.util.Optional;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;

class RecoveryJournalPreparationTest {
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
