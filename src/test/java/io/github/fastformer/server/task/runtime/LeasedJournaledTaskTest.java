package io.github.fastformer.server.task.runtime;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.fastplace.world.WorldOperationCommit;
import io.github.fastformer.server.task.WorldTaskContext;
import io.github.fastformer.server.task.WorldTaskBudget;
import io.github.fastformer.server.task.TaskStep;
import net.minecraft.server.level.ServerLevel;
import io.github.fastformer.fastplace.world.memory.MemoryAdmission;
import io.github.fastformer.fastplace.world.memory.MemoryAdmissionStatus;
import io.github.fastformer.fastplace.world.memory.MemoryReservation;
import io.github.fastformer.fastplace.world.memory.WorldOperationMemory;
import io.github.fastformer.fastplace.world.snapshot.ReversibleBlockSnapshot;
import io.github.fastformer.server.recovery.JournalPreparation;
import io.github.fastformer.server.recovery.RecoveryJournalTestAccess;
import io.github.fastformer.server.recovery.WorldJournalPreparation;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LeasedJournaledTaskTest {
   @TempDir Path directory;

   @Test
   void constructionRejectsMissingJournalPreparation() {
      assertThrows(NullPointerException.class, () -> new Writer(null));
   }

   @Test
   void attachedResourceFailureDoesNotLeakTheWorkingSetReservation() {
      var task = new Writer(new WorldJournalPreparation(Runnable::run));
      try (MemoryReservation reservation = reserve()) {
         task.memoryReservation = reservation;
         task.failRelease = true;
         assertThrows(IllegalStateException.class, task::releaseMemoryReservation);
         assertEquals(0, reservation.bytes());
         assertNull(task.memoryReservation);
         task.releaseMemoryReservation();
         assertEquals(1, task.resourceReleases);
      }
   }

   @Test
   void budgetWaitsForBothAppendAndPublicationDespiteCleanupFailure() {
      var io = new ArrayDeque<Runnable>();
      var publication = new ArrayDeque<Runnable>();
      var preparation = new WorldJournalPreparation(io::addLast);
      var journal = RecoveryJournalTestAccess.open(directory);
      preparation.poll(() -> Optional.of(journal));
      io.removeFirst().run();
      assertEquals(JournalPreparation.READY, preparation.poll(Optional::empty));
      assertEquals(JournalPreparation.PENDING, preparation.pollAppend(() -> true));
      var task = new Writer(preparation);
      task.operationCommit = WorldOperationCommit.begin(Level.OVERWORLD, List.of(), Map.of(), null, publication::addLast);
      task.failRelease = true;
      try (MemoryReservation reservation = reserve()) {
         task.memoryReservation = reservation;
         assertThrows(IllegalStateException.class, task::releaseMemoryReservation);
         assertNull(task.memoryReservation);
         assertEquals(64, reservation.bytes());
         io.removeFirst().run();
         assertEquals(64, reservation.bytes());
         publication.removeFirst().run();
         assertEquals(0, reservation.bytes());
         task.releaseMemoryReservation();
         assertEquals(1, task.resourceReleases);
      } finally {
         journal.discardUnused();
      }
   }

   @Test
   void activityFailureStillCancelsPreparationAndRetainsRecoveryOwnership() {
      var preparation = new WorldJournalPreparation(Runnable::run);
      var task = new Writer(preparation);
      var before = snapshot();
      task.transaction().recordBefore(before);
      task.failStop = true;

      assertThrows(IllegalStateException.class, task::stopAndTransferRecovery);
      assertEquals("journal preparation cancelled", preparation.failureReason());
      assertTrue(task.hasWrites());
      assertFalse(task.stagingCleared);

      task.failStop = false;
      var recovery = task.stopAndTransferRecovery();
      assertTrue(recovery.ready());
      assertSame(before, recovery.before().getFirst());
      assertFalse(task.hasWrites());
      assertFalse(task.stagingCleared);
   }

   @Test
   void recoveryWaitsForPublicationAndLeavesStagingIntact() {
      var publication = new ArrayDeque<Runnable>();
      var task = new Writer(new WorldJournalPreparation(Runnable::run));
      var before = snapshot();
      task.transaction().recordBefore(before);
      task.operationCommit = WorldOperationCommit.begin(Level.OVERWORLD, task.transaction(), null, publication::addLast);

      var recovery = task.stopAndTransferRecovery();
      assertFalse(recovery.ready());
      assertFalse(task.hasWrites());
      assertFalse(task.stagingCleared);
      publication.removeFirst().run();
      assertTrue(recovery.ready());
      assertSame(before, recovery.before().getFirst());
      assertTrue(task.preparedBatch().isEmpty());
   }

   @Test
   void committedCleanupStillClearsTransactionWhenAttachedResourceThrows() {
      var task = new Writer(new WorldJournalPreparation(Runnable::run));
      task.transaction().recordBefore(snapshot());
      task.operationCommit = WorldOperationCommit.begin(Level.OVERWORLD, List.of(), Map.of(), null, Runnable::run);
      task.failRelease = true;

      assertThrows(IllegalStateException.class, task::releaseCommittedTransactionState);
      assertFalse(task.hasWrites());
      assertNull(task.operationCommit());
      assertTrue(task.stagingCleared);
      task.releaseCommittedTransactionState();
      assertEquals(1, task.resourceReleases);
   }

   private static MemoryReservation reserve() {
      return WorldOperationMemory.reserve(new MemoryAdmission(MemoryAdmissionStatus.ALLOWED, 64, Long.MAX_VALUE)).orElseThrow();
   }

   private static ReversibleBlockSnapshot snapshot() {
      return new ReversibleBlockSnapshot(BlockPos.ZERO, null, null, null);
   }

   private static final class Writer extends LeasedJournaledTask {
      private boolean failStop;
      private boolean failRelease;
      private boolean resourceAttached = true;
      private boolean stagingCleared;
      private int resourceReleases;

      private Writer(WorldJournalPreparation journal) { super(journal); }
      @Override public ResourceKey<Level> dimension() { return Level.OVERWORLD; }
      @Override protected TaskStep tickBody(WorldTaskContext context, ServerLevel level, WorldTaskBudget budget) { return TaskStep.ACTIVE; }

      @Override protected void stopTaskActivity() {
         if (failStop) throw new IllegalStateException("injected activity stop failure");
      }

      @Override protected void releaseAttachedResources() {
         if (!resourceAttached) return;
         resourceAttached = false;
         resourceReleases++;
         if (failRelease) throw new IllegalStateException("injected resource release failure");
      }

      @Override protected void clearCommittedStaging() { stagingCleared = true; }
   }
}
