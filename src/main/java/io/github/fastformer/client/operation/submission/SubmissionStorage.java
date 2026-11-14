package io.github.fastformer.client.operation.submission;

import io.github.fastformer.client.session.draft.OperationDraftIdentity;
import io.github.fastformer.client.session.submission.OperationDraftSettlement;
import io.github.fastformer.client.session.submission.OperationSubmissionOrigin;
import io.github.fastformer.common.workspace.submission.OperationWorkspacePlan;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Durable preparation returns a send command only after the draft and receipt are saved. */
public interface SubmissionStorage extends SubmissionReceiptAccess {
   boolean prepareSubmission(OperationDraftIdentity identity, OperationSubmissionOrigin origin, UUID transferId,
      OperationWorkspacePlan plan, BooleanSupplier owned, Consumer<Runnable> complete);
   OperationDraftSettlement.Result clearDraftCopies(UUID transferId);
}
