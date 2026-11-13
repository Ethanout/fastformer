package io.github.fastformer.server.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fastformer.fastplace.world.memory.MemoryReservation;
import io.github.fastformer.fastplace.world.memory.WorldOperationMemory;
import io.github.fastformer.server.worldwrite.WorldWriteLeases;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorldJournalPreparationCancellationTest {
   @TempDir
   Path directory;

   @Test
   void cancellationBeforePollingDoesNotScheduleIo() {
      ArrayDeque<Runnable> queue = new ArrayDeque<>();
      WorldJournalPreparation preparation = new WorldJournalPreparation(queue::addLast);
      preparation.cancel();

      assertEquals(JournalPreparation.FAILED, preparation.poll(Optional::empty));
      assertEquals("journal preparation cancelled", preparation.failureReason());
      assertTrue(queue.isEmpty());
   }

   @Test
   void lateCompletionAfterResetDeletesOnlyTheCancelledJournal() throws Exception {
      ArrayDeque<Runnable> queue = new ArrayDeque<>();
      WorldJournalPreparation preparation = new WorldJournalPreparation(queue::addLast);
      Path oldFile = Files.createDirectory(directory.resolve("old"));
      Path newFile = Files.createDirectory(directory.resolve("new"));
      PersistentRecoveryJournal oldJournal = new PersistentRecoveryJournal(oldFile);
      PersistentRecoveryJournal newJournal = new PersistentRecoveryJournal(newFile);
      assertEquals(JournalPreparation.PENDING, preparation.poll(() -> Optional.of(oldJournal)));
      Runnable oldWork = queue.removeFirst();
      preparation.cancel();
      preparation.reset();
      assertEquals(JournalPreparation.PENDING, preparation.poll(() -> Optional.of(newJournal)));
      queue.removeFirst().run();
      assertEquals(JournalPreparation.READY, preparation.poll(Optional::empty));

      oldWork.run();

      assertFalse(Files.exists(oldFile));
      assertTrue(Files.exists(newFile));
      assertSame(newJournal, preparation.journal());
   }

   @Test
   void pollAppendWritesAfterTheFirstJournalIsReady() throws Exception {
      ArrayDeque<Runnable> queue = new ArrayDeque<>();
      WorldJournalPreparation preparation = new WorldJournalPreparation(queue::addLast);
      Path file = Files.createDirectory(directory.resolve("segmented"));
      PersistentRecoveryJournal journal = new PersistentRecoveryJournal(file);
      assertEquals(JournalPreparation.PENDING, preparation.poll(() -> Optional.of(journal)));
      queue.removeFirst().run();
      assertEquals(JournalPreparation.READY, preparation.poll(Optional::empty));

      java.util.concurrent.atomic.AtomicBoolean appended = new java.util.concurrent.atomic.AtomicBoolean();
      assertEquals(JournalPreparation.PENDING, preparation.pollAppend(() -> {
         appended.set(true);
         return true;
      }));
      queue.removeFirst().run();
      assertEquals(JournalPreparation.READY, preparation.pollAppend(() -> false));
      assertEquals(true, appended.get());
   }

   @Test
   void reservationRemainsHeldUntilPendingJournalCreationCompletes() {
      long baseline = MemoryReservation.reservedBytes();
      MemoryReservation reservation = WorldOperationMemory.reserveGeneration(1L, 0L).orElseThrow();
      long reserved = MemoryReservation.reservedBytes();
      ArrayDeque<Runnable> queue = new ArrayDeque<>();
      WorldJournalPreparation preparation = new WorldJournalPreparation(queue::addLast);
      assertEquals(JournalPreparation.PENDING, preparation.poll(Optional::empty));

      try {
         CompletableFuture<Void> recoveryReady = preparation.completion();
         preparation.cancel();
         preparation.releaseWhenIdle(reservation);

         assertFalse(recoveryReady.isDone());
         assertEquals(reserved, MemoryReservation.reservedBytes());
         queue.removeFirst().run();
         assertTrue(recoveryReady.isDone());
         assertEquals(baseline, MemoryReservation.reservedBytes());
      } finally {
         reservation.close();
      }
   }

   @Test
   void reservationRemainsHeldUntilPendingJournalAppendCompletes() throws Exception {
      long baseline = MemoryReservation.reservedBytes();
      MemoryReservation reservation = WorldOperationMemory.reserveGeneration(1L, 0L).orElseThrow();
      long reserved = MemoryReservation.reservedBytes();
      ArrayDeque<Runnable> queue = new ArrayDeque<>();
      WorldJournalPreparation preparation = new WorldJournalPreparation(queue::addLast);
      Path file = Files.createDirectory(directory.resolve("pending-append"));
      PersistentRecoveryJournal journal = new PersistentRecoveryJournal(file);
      assertEquals(JournalPreparation.PENDING, preparation.poll(() -> Optional.of(journal)));
      queue.removeFirst().run();
      assertEquals(JournalPreparation.READY, preparation.poll(Optional::empty));
      assertEquals(JournalPreparation.PENDING, preparation.pollAppend(() -> true));

      try {
         CompletableFuture<Void> recoveryReady = preparation.completion();
         preparation.cancel();
         preparation.releaseWhenIdle(reservation);

         assertFalse(recoveryReady.isDone());
         assertEquals(reserved, MemoryReservation.reservedBytes());
         queue.removeFirst().run();
         assertTrue(recoveryReady.isDone());
         assertEquals(baseline, MemoryReservation.reservedBytes());
      } finally {
         reservation.close();
      }
   }

   @Test
   void reservationRemainsHeldUntilPublicationCompletesWithoutCancellingIt() {
      long baseline = MemoryReservation.reservedBytes();
      MemoryReservation reservation = WorldOperationMemory.reserveGeneration(1L, 0L).orElseThrow();
      long reserved = MemoryReservation.reservedBytes();
      CompletableFuture<Void> publication = new CompletableFuture<>();
      WorldJournalPreparation preparation = new WorldJournalPreparation(Runnable::run);

      try {
         preparation.releaseWhenIdle(reservation, publication);

         assertEquals(reserved, MemoryReservation.reservedBytes());
         assertFalse(publication.isCancelled());
         publication.complete(null);
         assertEquals(baseline, MemoryReservation.reservedBytes());
      } finally {
         reservation.close();
      }
   }

   @Test
   void cancellationPreservesAnAlreadyHandedOffJournalForRecovery() throws Exception {
      Path file = Files.createDirectory(directory.resolve("owned"));
      PersistentRecoveryJournal journal = new PersistentRecoveryJournal(file);
      WorldJournalPreparation preparation = new WorldJournalPreparation(Runnable::run);
      assertEquals(JournalPreparation.READY, preparation.poll(() -> Optional.of(journal)));

      preparation.cancel();

      assertEquals(JournalPreparation.FAILED, preparation.poll(Optional::empty));
      assertTrue(Files.exists(file));
      assertSame(journal, preparation.journal());
   }

   @Test
   void cancelledAppendFinishesBeforeJournalDeletionAndLeaseRelease() throws Exception {
      WorldWriteLeases server = new WorldWriteLeases();
      UUID owner = UUID.randomUUID();
      var lease = server.acquire(Level.OVERWORLD, owner);
      var queue = new ArrayDeque<Runnable>();
      var preparation = new WorldJournalPreparation(queue::addLast);
      Path file = Files.createDirectory(directory.resolve("cancelled-append"));
      var journal = new PersistentRecoveryJournal(file);
      preparation.poll(() -> Optional.of(journal));
      queue.removeFirst().run();
      assertEquals(JournalPreparation.READY, preparation.poll(Optional::empty));
      preparation.pollAppend(() -> {
         assertTrue(Files.exists(file), "cleanup deleted the directory while append still used it");
         assertTrue(lease.isCurrent(), "cleanup released the lease during append");
         return true;
      });
      try {
         preparation.releaseAfterCancellation(lease);
         assertTrue(Files.exists(file));
         assertTrue(lease.isCurrent());
         queue.removeFirst().run();
         assertFalse(Files.exists(file));
         assertFalse(lease.isCurrent());
      } finally {
         server.close();
      }
   }

   @Test
   void failedCancelledAppendCleanupCannotDeleteNewPreparationOrReleaseSuccessor() throws Exception {
      WorldWriteLeases server = new WorldWriteLeases();
      UUID owner = UUID.randomUUID();
      var lease = server.acquire(Level.OVERWORLD, owner);
      var queue = new ArrayDeque<Runnable>();
      var preparation = new WorldJournalPreparation(queue::addLast);
      Path oldFile = Files.createDirectory(directory.resolve("old-append"));
      Path newFile = Files.createDirectory(directory.resolve("replacement-append"));
      var oldJournal = new PersistentRecoveryJournal(oldFile);
      var newJournal = new PersistentRecoveryJournal(newFile);
      preparation.poll(() -> Optional.of(oldJournal));
      queue.removeFirst().run();
      preparation.poll(Optional::empty);
      preparation.pollAppend(() -> { throw new IllegalStateException("injected append failure"); });
      Runnable append = queue.removeFirst();
      preparation.releaseAfterCancellation(lease);
      preparation.reset();
      preparation.poll(() -> Optional.of(newJournal));
      queue.removeFirst().run();
      preparation.poll(Optional::empty);
      var successor = server.takeOver(Level.OVERWORLD, owner);
      try {
         append.run();
         assertFalse(Files.exists(oldFile));
         assertTrue(Files.exists(newFile));
         assertSame(newJournal, preparation.journal());
         assertTrue(successor.isCurrent());
      } finally {
         server.close();
      }
   }
}
