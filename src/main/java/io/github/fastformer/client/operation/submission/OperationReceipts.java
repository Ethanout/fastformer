package io.github.fastformer.client.operation.submission;

import io.github.fastformer.protocol.c2s.workspace.OperationWorkspaceReceiptQueryPayload;
import io.github.fastformer.protocol.s2c.workspace.OperationWorkspaceReceiptPayload;
import io.github.fastformer.workspace.submission.OperationSubmissionOutcome;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import net.minecraft.resources.ResourceLocation;

/** Queries and settles receipts without submitting work or changing editable geometry. */
public final class OperationReceipts {
   private final Supplier<SubmissionReceiptAccess> storage;
   private final Supplier<ResourceLocation> dimension;
   private final Consumer<OperationWorkspaceReceiptQueryPayload> send;
   private final Consumer<String> notify;
   private final LongSupplier nowMillis;
   private final Consumer<RuntimeException> queryFailed;

   public OperationReceipts(Supplier<SubmissionReceiptAccess> storage, Supplier<ResourceLocation> dimension,
      Consumer<OperationWorkspaceReceiptQueryPayload> send, Consumer<String> notify, LongSupplier nowMillis,
      Consumer<RuntimeException> queryFailed) {
      this.storage = Objects.requireNonNull(storage, "storage");
      this.dimension = Objects.requireNonNull(dimension, "dimension");
      this.send = Objects.requireNonNull(send, "send");
      this.notify = Objects.requireNonNull(notify, "notify");
      this.nowMillis = Objects.requireNonNull(nowMillis, "nowMillis");
      this.queryFailed = Objects.requireNonNull(queryFailed, "queryFailed");
   }

   public void query() {
      var world = dimension.get();
      if (world == null) return;
      var transfers = storage.get().openSubmissionReceipts().stream()
         .filter(receipt -> receipt.outcome().open() && receipt.dimension().equals(world))
         .map(receipt -> receipt.transferId()).toList();
      try {
         int limit = OperationWorkspaceReceiptQueryPayload.MAX_TRANSFER_IDS;
         for (int start = 0; start < transfers.size(); start += limit) {
            send.accept(new OperationWorkspaceReceiptQueryPayload(world,
               List.copyOf(transfers.subList(start, Math.min(start + limit, transfers.size())))));
         }
      } catch (RuntimeException exception) {
         queryFailed.accept(exception);
      }
   }

   public void apply(OperationWorkspaceReceiptPayload payload) {
      if (payload == null || !payload.dimension().equals(dimension.get())) return;
      var receipts = storage.get();
      for (var entry : payload.entries()) {
         var recorded = receipts.submissionReceipt(entry.transferId()).orElse(null);
         if (recorded == null || !recorded.dimension().equals(payload.dimension())) continue;
         if (recorded.outcome() == OperationSubmissionOutcome.APPLIED) {
            if (recorded.needsCleanup()) applied(receipts, entry);
            continue;
         }
         if (entry.outcome() == recorded.outcome()) continue;
         if (entry.outcome() == OperationSubmissionOutcome.APPLIED) {
            applied(receipts, entry);
            continue;
         }
         receipts.updateSubmissionReceipt(entry.transferId(), entry.outcome(), nowMillis.getAsLong());
         var key = switch (entry.outcome()) {
            case FAILED_RETRYABLE -> "fastformer.message.operation_submit_failed_retry";
            case FAILED_NONRETRYABLE -> "fastformer.message.operation_submit_failed_final";
            case RECOVERY_REQUIRED -> "fastformer.message.operation_submit_recovery";
            case UNKNOWN -> "fastformer.message.operation_submit_unverified";
            default -> null;
         };
         if (key != null) notify.accept(key);
      }
   }

   private void applied(SubmissionReceiptAccess receipts, OperationWorkspaceReceiptPayload.Entry entry) {
      var settled = receipts.settleAppliedSubmission(entry.transferId());
      notify.accept(settled.confirmed() ? "fastformer.message.operation_submit_success"
         : "fastformer.message.operation_submit_cleanup_pending");
   }
}
