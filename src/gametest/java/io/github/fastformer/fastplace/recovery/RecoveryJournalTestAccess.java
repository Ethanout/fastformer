package io.github.fastformer.fastplace.recovery;

public final class RecoveryJournalTestAccess {
   private RecoveryJournalTestAccess() {}

   public static void resetWriteGate() {
      PersistentRecoveryJournal.resetWriteGateForTest();
   }
}
