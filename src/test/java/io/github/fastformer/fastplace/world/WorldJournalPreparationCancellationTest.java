package io.github.fastformer.fastplace.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Optional;
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
      Path oldFile = Files.createFile(directory.resolve("old.dat"));
      Path newFile = Files.createFile(directory.resolve("new.dat"));
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
   void cancellationPreservesAnAlreadyHandedOffJournalForRecovery() throws Exception {
      Path file = Files.createFile(directory.resolve("owned.dat"));
      PersistentRecoveryJournal journal = new PersistentRecoveryJournal(file);
      WorldJournalPreparation preparation = new WorldJournalPreparation(Runnable::run);
      assertEquals(JournalPreparation.READY, preparation.poll(() -> Optional.of(journal)));

      preparation.cancel();

      assertEquals(JournalPreparation.FAILED, preparation.poll(Optional::empty));
      assertTrue(Files.exists(file));
      assertSame(journal, preparation.journal());
   }
}
