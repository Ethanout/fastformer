package io.github.fastformer.server.task.runtime;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.fastplace.world.WorldOperationMetrics;
import io.github.fastformer.fastplace.world.WorldWriteCoordinator;
import io.github.fastformer.server.recovery.WorldJournalPreparation;
import java.util.UUID;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

class TaskWriteLeaseTest {
   @Test
   void movingToAnotherDimensionReleasesThePreviousLease() {
      Object server = new Object();
      UUID owner = UUID.randomUUID();
      var lease = new TaskWriteLease();
      var metrics = new WorldOperationMetrics(UUID.randomUUID());
      try {
         assertTrue(lease.acquire(server, owner, Level.OVERWORLD, metrics));
         assertTrue(lease.acquire(server, owner, Level.OVERWORLD, metrics));
         assertTrue(lease.acquire(server, owner, Level.NETHER, metrics));
         assertFalse(WorldWriteCoordinator.busy(server, Level.OVERWORLD));
         assertTrue(WorldWriteCoordinator.heldBy(server, Level.NETHER, owner));
         lease.release();
         lease.release();
         assertFalse(WorldWriteCoordinator.busy(server, Level.NETHER));
      } finally {
         WorldWriteCoordinator.clear(server);
      }
   }

   @Test
   void cancelledPredecessorCannotReleaseItsSuccessor() {
      Object server = new Object();
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
         assertTrue(WorldWriteCoordinator.heldBy(server, Level.OVERWORLD, owner));
         assertEquals("journal preparation cancelled", journal.failureReason());
         successor.release();
         assertFalse(WorldWriteCoordinator.busy(server, Level.OVERWORLD));
      } finally {
         WorldWriteCoordinator.clear(server);
      }
   }

   @Test
   void cancellationWithoutAnOwnedLeaseStillCancelsJournalPreparation() {
      var journal = new WorldJournalPreparation(Runnable::run);
      new TaskWriteLease().releaseAfterCancellation(journal, null);
      assertEquals("journal preparation cancelled", journal.failureReason());
   }
}
