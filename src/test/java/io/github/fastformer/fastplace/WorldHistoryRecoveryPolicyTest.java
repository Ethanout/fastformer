package io.github.fastformer.fastplace;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class WorldHistoryRecoveryPolicyTest {
   @Test
   void recoveryRestoresOwnedCellsAndAcceptsAlreadyRestoredCells() {
      assertEquals(
         WorldHistoryManager.RecoveryCellAction.RESTORE,
         WorldHistoryManager.recoveryCellAction(true, 1)
      );
      assertEquals(
         WorldHistoryManager.RecoveryCellAction.ALREADY_RESTORED,
         WorldHistoryManager.recoveryCellAction(true, 2)
      );
   }

   @Test
   void recoveryPreservesExternalWritesInsteadOfStrandingTheWholeBatch() {
      assertEquals(
         WorldHistoryManager.RecoveryCellAction.PRESERVE_EXTERNAL,
         WorldHistoryManager.recoveryCellAction(true, 0)
      );
   }

   @Test
   void userUndoStillRequiresAnAtomicConflictFreeBatch() {
      assertEquals(
         WorldHistoryManager.RecoveryCellAction.FAIL_ATOMIC_BATCH,
         WorldHistoryManager.recoveryCellAction(false, 0)
      );
   }

   @Test
   void rollbackRestoresACompleteOperationTarget() {
      assertEquals(true, WorldHistoryManager.rollbackOwnsPartial(false, false, true));
   }

   @Test
   void rollbackRestoresAnApplyFailureThatStillMatchesItsCapturedFingerprint() {
      assertEquals(true, WorldHistoryManager.rollbackOwnsPartial(true, true, false));
   }

   @Test
   void rollbackPreservesAChangedCellThatMatchesNeitherOwnedState() {
      assertEquals(false, WorldHistoryManager.rollbackOwnsPartial(true, false, false));
      assertEquals(false, WorldHistoryManager.rollbackOwnsPartial(false, true, false));
   }

   @Test
   void resumedUncommittedRecoveryConsumesOneUndoStep() {
      assertEquals(0, WorldHistoryManager.remainingAfterUncommittedUndo(1));
      assertEquals(2, WorldHistoryManager.remainingAfterUncommittedUndo(3));
      assertEquals(
         WorldHistoryManager.MAX_LIMIT - 1,
         WorldHistoryManager.remainingAfterUncommittedUndo(Integer.MAX_VALUE)
      );
   }

   @Test
   void cancellationCannotDiscardJournalForAnUnidentifiedPartialApply() {
      assertEquals(true, WorldHistoryManager.cancellationCanFinishImmediately(true, false, false, false));
      assertEquals(false, WorldHistoryManager.cancellationCanFinishImmediately(true, false, false, true));
      assertEquals(false, WorldHistoryManager.cancellationCanFinishImmediately(true, true, false, false));
      assertEquals(false, WorldHistoryManager.cancellationCanFinishImmediately(true, false, true, false));
   }

   @Test
   void pausedRecoveryAutoResumesWhenItsOwnerIsOffline() {
      assertEquals(true, WorldHistoryManager.retainRecoveryForManualResume(true));
      assertEquals(false, WorldHistoryManager.retainRecoveryForManualResume(false));
   }
}
