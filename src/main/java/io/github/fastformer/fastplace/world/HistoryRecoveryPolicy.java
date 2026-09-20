package io.github.fastformer.fastplace.world;

import io.github.fastformer.fastplace.PlacementUpdateMode;

/** Defines the non-mutating decisions used while a history task recovers world state. */
final class HistoryRecoveryPolicy {
   private HistoryRecoveryPolicy() {
   }

   static PlacementUpdateMode effectiveUpdateMode(
      PlacementUpdateMode requestedMode, boolean recovery
   ) {
      return recovery || requestedMode == null
         ? PlacementUpdateMode.CLIENT_ONLY
         : requestedMode;
   }

   static boolean ownsPartialRollback(
      boolean partialIndexMatches,
      boolean partialFingerprintMatches,
      boolean normalTargetMatches
   ) {
      return normalTargetMatches || (partialIndexMatches && partialFingerprintMatches);
   }

   static boolean canFinishCancellation(
      boolean cancelRequested,
      boolean rollingBack,
      boolean resolving,
      boolean failedApplyState
   ) {
      return cancelRequested && !rollingBack && !resolving && !failedApplyState;
   }

   static RetentionAction retentionAction(boolean retainRecovery, boolean hasBatch) {
      if (!retainRecovery || !hasBatch) {
         return RetentionAction.NONE;
      }
      return RetentionAction.RETRY_AUTOMATICALLY;
   }

   static CellAction cellAction(boolean recovery, int match) {
      if (match == 1) {
         return CellAction.RESTORE;
      }
      if (match == 2) {
         return CellAction.ALREADY_RESTORED;
      }
      return recovery ? CellAction.PRESERVE_EXTERNAL : CellAction.FAIL_ATOMIC_BATCH;
   }

   enum CellAction {
      RESTORE,
      ALREADY_RESTORED,
      PRESERVE_EXTERNAL,
      FAIL_ATOMIC_BATCH
   }

   enum RetentionAction {
      NONE,
      RETRY_AUTOMATICALLY
   }
}
