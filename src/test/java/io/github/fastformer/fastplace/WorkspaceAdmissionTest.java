package io.github.fastformer.fastplace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fastformer.fastplace.world.WorkspaceSubmissionLedger;
import io.github.fastformer.network.payload.operation.OperationSubmissionOutcome;
import org.junit.jupiter.api.Test;

/**
 * Tests the answer that one workspace admission produces.
 *
 * <p>These are the rules that decide whether a result packet goes out and what it reports.
 * A boolean could not carry them, and the flattened version answered a replay with a
 * retryable failure, which then overwrote an applied entry in the ledger.</p>
 */
class WorkspaceAdmissionTest {

   // ---- a queued task reports nothing ----

   @Test
   void aQueuedAdmissionSendsNoResult() {
      WorkspaceAdmission admission = WorkspaceAdmission.newQueued();

      assertTrue(admission.isQueued());
      // The task reports its own result when it settles. A packet here would race it.
      assertFalse(admission.sendsResult());
      assertNull(admission.deliveredOutcome());
   }

   // ---- a replay reports what the ledger holds ----

   @Test
   void aReplayOfRunningWorkSendsNoFailure() {
      // This is the defect. Running work has no finished result, and a client that reads a
      // result packet for it treats the packet as a failure and sends the same work again.
      WorkspaceAdmission admission = WorkspaceAdmission.replayed(OperationSubmissionOutcome.IN_PROGRESS);

      assertFalse(admission.sendsResult());
      assertNull(admission.deliveredOutcome());
      assertFalse(admission.retryable());
   }

   @Test
   void aReplayOfAppliedWorkReportsApplied() {
      WorkspaceAdmission admission = WorkspaceAdmission.replayed(OperationSubmissionOutcome.APPLIED);

      assertTrue(admission.sendsResult());
      assertEquals(OperationSubmissionOutcome.APPLIED, admission.deliveredOutcome());
      assertFalse(admission.retryable());
   }

   @Test
   void aReplayOfARetryableFailureReportsThatFailure() {
      WorkspaceAdmission admission =
         WorkspaceAdmission.replayed(OperationSubmissionOutcome.FAILED_RETRYABLE);

      assertTrue(admission.sendsResult());
      assertEquals(OperationSubmissionOutcome.FAILED_RETRYABLE, admission.deliveredOutcome());
      assertTrue(admission.retryable());
   }

   @Test
   void aReplayOfANonRetryableFailureReportsThatFailure() {
      WorkspaceAdmission admission =
         WorkspaceAdmission.replayed(OperationSubmissionOutcome.FAILED_NONRETRYABLE);

      assertTrue(admission.sendsResult());
      assertEquals(OperationSubmissionOutcome.FAILED_NONRETRYABLE, admission.deliveredOutcome());
      assertFalse(admission.retryable());
   }

   @Test
   void aReplayOfARecoveryHandoffReportsThatState() {
      WorkspaceAdmission admission =
         WorkspaceAdmission.replayed(OperationSubmissionOutcome.RECOVERY_REQUIRED);

      assertTrue(admission.sendsResult());
      assertEquals(OperationSubmissionOutcome.RECOVERY_REQUIRED, admission.deliveredOutcome());
      // The journal owns the outcome, so the player must not send the same work again.
      assertFalse(admission.retryable());
   }

   @Test
   void aReplayOfAnUnknownStateSendsNothing() {
      // The ledger never records UNKNOWN, and a client that reads it as a failure would
      // resend work whose outcome is genuinely unknown.
      WorkspaceAdmission admission = WorkspaceAdmission.replayed(OperationSubmissionOutcome.UNKNOWN);

      assertFalse(admission.sendsResult());
      assertNull(admission.deliveredOutcome());
   }

   // ---- a refused admission reports one retryable failure ----

   @Test
   void aRefusedAdmissionReportsOneRetryableFailure() {
      WorkspaceAdmission admission = WorkspaceAdmission.rejected();

      assertFalse(admission.isQueued());
      assertTrue(admission.sendsResult());
      assertEquals(OperationSubmissionOutcome.FAILED_RETRYABLE, admission.deliveredOutcome());
      assertTrue(admission.retryable());
   }

   // ---- the request scope has exactly one owner ----

   @Test
   void aQueuedAdmissionLeavesTheRequestScopeBehind() {
      // A queued task reports its own result later, and that packet clears the scope.
      assertTrue(WorkspaceAdmission.newQueued().keepsRequestScope());
   }

   @Test
   void aReplayOfRunningWorkKeepsTheScopeOfTheLiveTask() {
      WorkspaceAdmission admission = WorkspaceAdmission.replayed(OperationSubmissionOutcome.IN_PROGRESS);

      // The scope map is keyed by owner and transfer, so this replay shares one entry with
      // the request that queued the task. The live task removes that entry when it reports
      // its result, and it needs the entry until then.
      assertFalse(admission.sendsResult());
      assertTrue(admission.keepsRequestScope());
   }

   @Test
   void aDeliveredReplayClosesTheRequestScope() {
      // Replay scope is not "send xor keep". UNKNOWN sends nothing and drops the
      // scope. Open work keeps the scope and sends nothing. A finished result
      // sends a packet and closes the request that carried it.
      for (OperationSubmissionOutcome outcome : OperationSubmissionOutcome.values()) {
         WorkspaceAdmission admission = WorkspaceAdmission.replayed(outcome);
         switch (outcome) {
            case UNKNOWN -> {
               assertFalse(admission.sendsResult(), outcome.name());
               assertFalse(admission.keepsRequestScope(), outcome.name());
            }
            case IN_PROGRESS, IN_FLIGHT -> {
               assertFalse(admission.sendsResult(), outcome.name());
               assertTrue(admission.keepsRequestScope(), outcome.name());
            }
            case APPLIED, FAILED_RETRYABLE, FAILED_NONRETRYABLE, RECOVERY_REQUIRED -> {
               assertTrue(admission.sendsResult(), outcome.name());
               assertFalse(admission.keepsRequestScope(), outcome.name());
            }
         }
      }
   }

   @Test
   void aRefusedAdmissionClosesTheRequestScope() {
      assertFalse(WorkspaceAdmission.rejected().keepsRequestScope());
   }

   // ---- the type keeps its own invariants ----

   @Test
   void aReplayNeedsTheRecordedState() {
      assertThrows(IllegalArgumentException.class, () -> new WorkspaceAdmission(
         WorkspaceAdmission.Kind.REPLAYED, null
      ));
   }

   @Test
   void onlyAReplayCarriesARecordedState() {
      assertThrows(IllegalArgumentException.class, () -> new WorkspaceAdmission(
         WorkspaceAdmission.Kind.QUEUED, OperationSubmissionOutcome.APPLIED
      ));
      assertThrows(IllegalArgumentException.class, () -> new WorkspaceAdmission(
         WorkspaceAdmission.Kind.REJECTED, OperationSubmissionOutcome.APPLIED
      ));
      assertThrows(IllegalArgumentException.class, () -> new WorkspaceAdmission(null, null));
   }

   // ---- the state mapping and the delivery rule agree ----

   @Test
   void onlyASettledStateIsDeliverable() {
      for (OperationSubmissionOutcome outcome : OperationSubmissionOutcome.values()) {
         boolean expected = outcome == OperationSubmissionOutcome.APPLIED
            || outcome == OperationSubmissionOutcome.FAILED_RETRYABLE
            || outcome == OperationSubmissionOutcome.FAILED_NONRETRYABLE
            || outcome == OperationSubmissionOutcome.RECOVERY_REQUIRED;
         assertEquals(expected, outcome.deliverable(), outcome.name());
      }
   }

   @Test
   void everyReportableStateDecidesWhetherItIsDelivered() {
      // Every state that a query may answer must also decide, one way or the other, whether
      // an admission replay delivers it. Only running work is reportable but not delivered.
      for (OperationSubmissionOutcome outcome : OperationSubmissionOutcome.values()) {
         if (!outcome.reportable()) {
            continue;
         }
         WorkspaceAdmission admission = WorkspaceAdmission.replayed(outcome);
         assertEquals(outcome.deliverable(), admission.sendsResult(), outcome.name());
      }
   }

   // ---- the flag mapping ----

   @Test
   void theReportFlagsMapToTheStates() {
      assertEquals(
         OperationSubmissionOutcome.APPLIED,
         WorkspaceSubmissionLedger.stateFor(true, false, false)
      );
      assertEquals(
         OperationSubmissionOutcome.APPLIED,
         WorkspaceSubmissionLedger.stateFor(true, true, true)
      );
      assertEquals(
         OperationSubmissionOutcome.RECOVERY_REQUIRED,
         WorkspaceSubmissionLedger.stateFor(false, true, true)
      );
      assertEquals(
         OperationSubmissionOutcome.FAILED_RETRYABLE,
         WorkspaceSubmissionLedger.stateFor(false, true, false)
      );
      assertEquals(
         OperationSubmissionOutcome.FAILED_NONRETRYABLE,
         WorkspaceSubmissionLedger.stateFor(false, false, false)
      );
   }

   @Test
   void aRecoveryHandoffOutranksARetryableFailure() {
      // A journal owns the outcome, so the client must not be told that a retry is safe.
      assertEquals(
         OperationSubmissionOutcome.RECOVERY_REQUIRED,
         WorkspaceSubmissionLedger.stateFor(false, true, true)
      );
   }
}
