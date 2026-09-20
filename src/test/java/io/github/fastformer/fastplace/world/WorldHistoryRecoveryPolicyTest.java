package io.github.fastformer.fastplace.world;


import io.github.fastformer.fastplace.PlacementUpdateMode;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class WorldHistoryRecoveryPolicyTest {
   @Test
   void userHistoryUsesTheRequestedUpdateMode() {
      assertEquals(
         PlacementUpdateMode.NORMAL,
         HistoryRecoveryPolicy.effectiveUpdateMode(PlacementUpdateMode.NORMAL, false)
      );
      assertEquals(
         PlacementUpdateMode.CLIENT_ONLY,
         HistoryRecoveryPolicy.effectiveUpdateMode(PlacementUpdateMode.CLIENT_ONLY, false)
      );
   }

   @Test
   void recoveryAlwaysSuppressesNeighborUpdates() {
      assertEquals(
         PlacementUpdateMode.CLIENT_ONLY,
         HistoryRecoveryPolicy.effectiveUpdateMode(PlacementUpdateMode.NORMAL, true)
      );
      assertEquals(
         PlacementUpdateMode.CLIENT_ONLY,
         HistoryRecoveryPolicy.effectiveUpdateMode(null, false)
      );
   }

   @Test
   void recoveryRestoresOwnedCellsAndAcceptsAlreadyRestoredCells() {
      assertEquals(
         HistoryRecoveryPolicy.CellAction.RESTORE,
         HistoryRecoveryPolicy.cellAction(true, 1)
      );
      assertEquals(
         HistoryRecoveryPolicy.CellAction.ALREADY_RESTORED,
         HistoryRecoveryPolicy.cellAction(true, 2)
      );
   }

   @Test
   void recoveryPreservesExternalWritesInsteadOfStrandingTheWholeBatch() {
      assertEquals(
         HistoryRecoveryPolicy.CellAction.PRESERVE_EXTERNAL,
         HistoryRecoveryPolicy.cellAction(true, 0)
      );
   }

   @Test
   void userUndoStillRequiresAnAtomicConflictFreeBatch() {
      assertEquals(
         HistoryRecoveryPolicy.CellAction.FAIL_ATOMIC_BATCH,
         HistoryRecoveryPolicy.cellAction(false, 0)
      );
   }

   @Test
   void rollbackRestoresACompleteOperationTarget() {
      assertEquals(true, HistoryRecoveryPolicy.ownsPartialRollback(false, false, true));
   }

   @Test
   void rollbackRestoresAnApplyFailureThatStillMatchesItsCapturedFingerprint() {
      assertEquals(true, HistoryRecoveryPolicy.ownsPartialRollback(true, true, false));
   }

   @Test
   void rollbackPreservesAChangedCellThatMatchesNeitherOwnedState() {
      assertEquals(false, HistoryRecoveryPolicy.ownsPartialRollback(true, false, false));
      assertEquals(false, HistoryRecoveryPolicy.ownsPartialRollback(false, true, false));
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
      assertEquals(true, HistoryRecoveryPolicy.canFinishCancellation(true, false, false, false));
      assertEquals(false, HistoryRecoveryPolicy.canFinishCancellation(true, false, false, true));
      assertEquals(false, HistoryRecoveryPolicy.canFinishCancellation(true, true, false, false));
      assertEquals(false, HistoryRecoveryPolicy.canFinishCancellation(true, false, true, false));
   }

   @Test
   void retainedRecoveryAlwaysRetriesAutomatically() {
      assertEquals(
         HistoryRecoveryPolicy.RetentionAction.RETRY_AUTOMATICALLY,
         HistoryRecoveryPolicy.retentionAction(true, true)
      );
      assertEquals(
         HistoryRecoveryPolicy.RetentionAction.NONE,
         HistoryRecoveryPolicy.retentionAction(false, true)
      );
      assertEquals(
         HistoryRecoveryPolicy.RetentionAction.NONE,
         HistoryRecoveryPolicy.retentionAction(true, false)
      );
   }
}
