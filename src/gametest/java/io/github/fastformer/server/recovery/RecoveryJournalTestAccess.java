package io.github.fastformer.server.recovery;

public final class RecoveryJournalTestAccess {
   private RecoveryJournalTestAccess() {}

   public static void resetWriteGate() {
      PersistentRecoveryJournal.resetWriteGateForTest();
   }
}
