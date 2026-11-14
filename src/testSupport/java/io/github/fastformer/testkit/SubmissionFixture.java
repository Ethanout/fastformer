package io.github.fastformer.testkit;

import io.github.fastformer.client.operation.submission.SubmissionEnvironment;
import io.github.fastformer.client.operation.submission.SubmissionStorage;
import io.github.fastformer.client.session.draft.OperationDraftIdentity;
import io.github.fastformer.client.session.receipt.OperationSubmissionReceipt;
import io.github.fastformer.client.session.submission.OperationDraftSettlement;
import io.github.fastformer.client.session.submission.OperationSubmissionOrigin;
import io.github.fastformer.workspace.submission.OperationSubmissionOutcome;
import io.github.fastformer.workspace.submission.OperationWorkspacePlan;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** In-memory ports exercising the production submission lifecycle without a client connection. */
public final class SubmissionFixture implements SubmissionStorage, SubmissionEnvironment {
   public record Preparation(UUID transferId, BooleanSupplier owned, Consumer<Runnable> complete) {}
   public final List<Preparation> prepared = new ArrayList<>();
   public final List<UUID> aborted = new ArrayList<>();
   public final List<UUID> acknowledged = new ArrayList<>();
   public final List<UUID> expired = new ArrayList<>();
   public final List<OperationSubmissionOutcome> outcomes = new ArrayList<>();
   public boolean current = true;
   public boolean available = true;
   public boolean supported = true;
   public boolean admitted = true;

   public boolean available() { return available; }
   public boolean supported() { return supported; }
   public BooleanSupplier freezeOwnership() { return () -> current; }
   public boolean begin(UUID transferId) { return true; }
   public void abort(UUID transferId) { aborted.add(transferId); }
   public void acknowledge(UUID transferId) { acknowledged.add(transferId); }
   public void expire(UUID transferId) { expired.add(transferId); }
   public boolean prepareSubmission(OperationDraftIdentity identity, OperationSubmissionOrigin origin, UUID transferId,
      OperationWorkspacePlan plan, BooleanSupplier owned, Consumer<Runnable> complete) {
      if (!admitted) return false;
      prepared.add(new Preparation(transferId, owned, complete));
      return true;
   }
   public List<OperationSubmissionReceipt> openSubmissionReceipts() { return List.of(); }
   public Optional<OperationSubmissionReceipt> submissionReceipt(UUID transferId) { return Optional.empty(); }
   public boolean updateSubmissionReceipt(UUID transferId, OperationSubmissionOutcome outcome, long now) {
      outcomes.add(outcome);
      return true;
   }
   public OperationDraftSettlement.Result clearDraftCopies(UUID transferId) {
      return new OperationDraftSettlement.Result(OperationDraftSettlement.Ownership.ABSENT, OperationDraftSettlement.Ownership.ABSENT);
   }
   public OperationDraftSettlement.Applied settleAppliedSubmission(UUID transferId) {
      return new OperationDraftSettlement.Applied(clearDraftCopies(transferId), true);
   }
}
