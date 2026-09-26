package io.github.fastformer.fastplace.recovery;

import java.nio.file.Path;

/** Keeps journal fixtures outside the production API. */
public final class RecoveryJournalTestAccess {
   private RecoveryJournalTestAccess() {}

   public static void resetWriteGate() {
      PersistentRecoveryJournal.resetWriteGateForTest();
   }

   public static PersistentRecoveryJournal open(Path directory) {
      return new PersistentRecoveryJournal(directory);
   }
}
