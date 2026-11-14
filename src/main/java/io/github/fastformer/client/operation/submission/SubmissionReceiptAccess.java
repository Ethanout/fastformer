package io.github.fastformer.client.operation.submission;

import io.github.fastformer.client.session.receipt.OperationSubmissionReceipt;
import io.github.fastformer.client.session.submission.OperationDraftSettlement;
import io.github.fastformer.common.workspace.submission.OperationSubmissionOutcome;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Durable receipt operations required by the editor; storage and cleanup stay with the session owner. */
public interface SubmissionReceiptAccess {
   List<OperationSubmissionReceipt> openSubmissionReceipts();
   Optional<OperationSubmissionReceipt> submissionReceipt(UUID transferId);
   OperationDraftSettlement.Applied settleAppliedSubmission(UUID transferId);
   boolean updateSubmissionReceipt(UUID transferId, OperationSubmissionOutcome outcome, long nowMillis);
}
