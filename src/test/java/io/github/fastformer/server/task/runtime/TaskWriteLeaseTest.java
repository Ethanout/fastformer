package io.github.fastformer.server.task.runtime;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.fastplace.world.WorldOperationMetrics;
import io.github.fastformer.server.worldwrite.WorldWriteLeases;
import io.github.fastformer.server.recovery.WorldJournalPreparation;
import java.util.UUID;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

class TaskWriteLeaseTest {
   @Test
   void movingToAnotherDimensionReleasesThePreviousLease() {
      WorldWriteLeases server = new WorldWriteLeases();
      UUID owner = UUID.randomUUID();
      var lease = new TaskWriteLease();
      var metrics = new WorldOperationMetrics(UUID.randomUUID());
      try {
         assertTrue(lease.acquire(server, owner, Level.OVERWORLD, metrics));
         assertTrue(lease.acquire(server, owner, Level.OVERWORLD, metrics));
         assertTrue(lease.acquire(server, owner, Level.NETHER, metrics));
         assertFalse(server.busy(Level.OVERWORLD));
         assertTrue(server.heldBy(Level.NETHER, owner));
         lease.release();
         lease.release();
         assertFalse(server.busy(Level.NETHER));
      } finally {
         server.close();
      }
   }

   @Test
   void cancelledPredecessorCannotReleaseItsSuccessor() {
      WorldWriteLeases server = new WorldWriteLeases();
      UUID owner = UUID.randomUUID();
      var first = new TaskWriteLease();
      var successor = new TaskWriteLease();
      var metrics = new WorldOperationMetrics(UUID.randomUUID());
      try {
         assertTrue(first.acquire(server, owner, Level.OVERWORLD, metrics));
         assertTrue(successor.acquire(server, owner, Level.OVERWORLD, metrics));
         var journal = new WorldJournalPreparation(Runnable::run);
         first.releaseAfterCancellation(journal, null);
         first.release();
         assertTrue(server.heldBy(Level.OVERWORLD, owner));
         assertEquals("journal preparation cancelled", journal.failureReason());
         successor.release();
         assertFalse(server.busy(Level.OVERWORLD));
      } finally {
         server.close();
      }
   }

   @Test
   void cancellationWithoutAnOwnedLeaseStillCancelsJournalPreparation() {
      var journal = new WorldJournalPreparation(Runnable::run);
      new TaskWriteLease().releaseAfterCancellation(journal, null);
      assertEquals("journal preparation cancelled", journal.failureReason());
   }

   @Test
   void acquiringInAnotherRuntimeDoesNotReuseThePreviousToken() {
      try (var first = new WorldWriteLeases(); var second = new WorldWriteLeases()) {
         var lease = new TaskWriteLease();
         UUID owner = UUID.randomUUID();
         var metrics = new WorldOperationMetrics(UUID.randomUUID());
         assertTrue(lease.acquire(first, owner, Level.OVERWORLD, metrics));
         assertTrue(lease.acquire(second, owner, Level.OVERWORLD, metrics));
         assertFalse(first.busy(Level.OVERWORLD));
         assertTrue(second.heldBy(Level.OVERWORLD, owner));
         lease.release();
         assertFalse(second.busy(Level.OVERWORLD));
      }
   }
}
