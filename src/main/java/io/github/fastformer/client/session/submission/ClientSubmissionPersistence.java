package io.github.fastformer.client.session.submission;

import io.github.fastformer.client.operation.clipboard.OperationClipboardStore;
import io.github.fastformer.client.operation.submission.SubmissionStorage;
import io.github.fastformer.client.session.draft.ClientDraftRepository.ReceiptVerdict;
import io.github.fastformer.client.session.draft.ClientOperationDraftCodec;
import io.github.fastformer.client.session.draft.OperationDraftIdentity;
import io.github.fastformer.client.session.receipt.DeferredReceiptSaves;
import io.github.fastformer.client.session.receipt.OperationSubmissionReceipt;
import io.github.fastformer.client.session.receipt.OperationSubmissionReceiptCodec;
import io.github.fastformer.client.session.receipt.OperationSubmissionReceiptStore;
import io.github.fastformer.client.session.receipt.ReceiptReadState;
import io.github.fastformer.client.session.scope.ClientScope;
import io.github.fastformer.client.session.storage.ClientStorageQueue;
import io.github.fastformer.common.workspace.submission.OperationSubmissionOutcome;
import io.github.fastformer.common.workspace.submission.OperationWorkspacePlan;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import net.minecraft.resources.ResourceLocation;

/** Owns durable submission preparation and receipt/draft settlement across client scopes. */
public final class ClientSubmissionPersistence implements SubmissionStorage {
   @FunctionalInterface
   public interface Encoder {
      Runnable prepare(OperationWorkspacePlan plan, UUID transferId) throws IOException;
   }

   private final ClientStorageQueue queue;
   private final Supplier<ClientScope> current;
   private final Encoder encoder;
   private final Runnable changed;
   private final BiConsumer<String, Exception> failed;
   private final LongSupplier now;
   private final DeferredReceiptSaves deferred;

   public ClientSubmissionPersistence(ClientStorageQueue queue, Supplier<ClientScope> current, Encoder encoder,
      Runnable changed, BiConsumer<String, Exception> failed, LongSupplier now, LongSupplier nanos) {
      this.queue = Objects.requireNonNull(queue);
      this.current = Objects.requireNonNull(current);
      this.encoder = Objects.requireNonNull(encoder);
      this.changed = Objects.requireNonNull(changed);
      this.failed = Objects.requireNonNull(failed);
      this.now = Objects.requireNonNull(now);
      this.deferred = new DeferredReceiptSaves(queue, nanos,
         (path, failure) -> failed.accept("fastformer.message.operation_receipt_save_failed", failure));
   }

   public void tick() { deferred.tick(); }
   public boolean idle() { return deferred.pendingCount() == 0; }

   /** Resolves once, reports a failed read once, and reopens only drafts awaiting a receipt. */
   public OperationSubmissionReceiptStore store(ClientScope owner) {
      var read = owner.receipts().read();
      if (read.failure() != null) {
         failed.accept(owner.receipts().readState() == ReceiptReadState.VERSION_INCOMPATIBLE
            ? "fastformer.message.operation_receipt_version_incompatible"
            : "fastformer.message.operation_receipt_load_failed", read.failure());
      } else if (read.newlyRead()) owner.drafts().rearmAfterReceipt();
      return read.store();
   }

   public ReceiptVerdict verdict(ClientScope owner, UUID transferId) {
      if (transferId == null) return ReceiptVerdict.NOT_APPLIED;
      if (owner == null) return ReceiptVerdict.UNKNOWN;
      var store = store(owner);
      if (store.readOnly()) return ReceiptVerdict.UNKNOWN;
      return store.find(transferId).map(receipt -> receipt.outcome().applied()
         ? ReceiptVerdict.APPLIED : ReceiptVerdict.NOT_APPLIED).orElse(ReceiptVerdict.NOT_APPLIED);
   }

   public boolean retryLoad() {
      var owner = current.get();
      if (owner == null || !owner.receipts().retry()) return false;
      owner.drafts().rearmAfterReceipt();
      return true;
   }

   /** Admission happens before receipt mutation or file reservations; both files precede the send. */
   @Override
   public boolean prepareSubmission(OperationDraftIdentity identity, OperationSubmissionOrigin origin, UUID transferId,
      OperationWorkspacePlan plan, BooleanSupplier owned, Consumer<Runnable> complete) {
      var owner = current.get();
      if (owner == null || transferId == null) return false;
      var draft = owner.buildSubmittedDraft(identity, origin, transferId);
      if (draft == null) return false;
      var store = store(owner);
      var receipt = new OperationSubmissionReceipt(transferId, origin, identity,
         ResourceLocation.parse(owner.id().dimension()), OperationSubmissionOutcome.IN_FLIGHT, now.getAsLong());
      if (store.readOnly() || !store.canAccept(transferId)
         || store.find(transferId).map(existing -> existing.outcome().applied()).orElse(false)) return false;
      Path receiptFile = owner.receipts().file(), draftFile = owner.drafts().file();
      var admission = new ReceiptAdmission(store, receipt, receiptFile);
      boolean admitted;
      try {
         admitted = queue.submitPrepared(() -> {
            admission.record();
            var receipts = store.all();
            Object receiptTicket = OperationClipboardStore.reserve(receiptFile);
            Object draftTicket = OperationClipboardStore.reserve(draftFile);
            return new ClientStorageQueue.Task<>(() -> {
               Runnable send = encoder.prepare(plan, transferId);
               OperationClipboardStore.save(receiptFile, OperationSubmissionReceiptCodec.encode(receipts), receiptTicket);
               OperationClipboardStore.save(draftFile, ClientOperationDraftCodec.encode(draft), draftTicket);
               return send;
            }, (send, error) -> {
               boolean durable = error == null && OperationClipboardStore.current(receiptFile, receiptTicket)
                  && OperationClipboardStore.current(draftFile, draftTicket);
               if (durable) { store.confirmSaved(receipts); owner.drafts().markReady(); }
               if (current.get() != owner || !owned.getAsBoolean()) {
                  unsent(store, receipt, receiptFile);
                  return;
               }
               if (!durable) {
                  unsent(store, receipt, receiptFile);
                  failed.accept("fastformer.message.operation_draft_save_failed", error);
               }
               complete.accept(durable ? send : null);
            });
         });
      } catch (RuntimeException failure) {
         admission.reject();
         throw failure;
      }
      // Executor rejection can happen after the admitted preparation ran. Its receipt
      // must not remain IN_FLIGHT when no send continuation can ever run.
      if (!admitted) admission.reject();
      return admitted;
   }

   /** A queue may reject after preparing; only an actual record creates a retry obligation. */
   private final class ReceiptAdmission {
      private final OperationSubmissionReceiptStore store;
      private final OperationSubmissionReceipt receipt;
      private final Path file;
      private boolean recorded;

      ReceiptAdmission(OperationSubmissionReceiptStore store, OperationSubmissionReceipt receipt, Path file) {
         this.store = store; this.receipt = receipt; this.file = file;
      }

      void record() {
         if (!store.record(receipt)) throw new IllegalStateException("The submission receipt cannot be recorded");
         recorded = true;
      }

      void reject() {
         if (recorded) { recorded = false; unsent(store, receipt, file); }
      }
   }

   private void unsent(OperationSubmissionReceiptStore store, OperationSubmissionReceipt receipt, Path file) {
      store.record(receipt.withOutcome(OperationSubmissionOutcome.FAILED_RETRYABLE, now.getAsLong()));
      deferred.request(file, store);
   }

   @Override
   public OperationDraftSettlement.Result clearDraftCopies(UUID transferId) {
      return clearDraftCopies(current.get(), transferId);
   }

   private OperationDraftSettlement.Result clearDraftCopies(ClientScope owner, UUID transferId) {
      var draft = owner == null ? null : owner.suspendedOperationDraftData();
      var staged = OperationDraftSettlement.resolveStaged(transferId, draft == null ? null : draft.submissionId(), () -> {
         owner.discardSuspendedOperationDraft();
         if (draft.remainder() != null) owner.stageSuspendedOperationDraft(draft.remainder().asDraft());
      });
      var durable = OperationDraftSettlement.resolveDurable(transferId, probe(owner), () -> {
         try { owner.drafts().settle(transferId); changed.run(); return true; }
         catch (IOException | RuntimeException failure) {
            failed.accept(null, failure);
            return false;
         }
      });
      return OperationDraftSettlement.combine(staged, durable);
   }

   private OperationDraftSettlement.DurableProbe probe(ClientScope owner) {
      if (owner == null) return OperationDraftSettlement.DurableProbe.absent();
      try { return owner.drafts().probe(); }
      catch (IOException | RuntimeException failure) {
         failed.accept(null, failure);
         return OperationDraftSettlement.DurableProbe.unreadable();
      }
   }

   @Override
   public OperationDraftSettlement.Applied settleAppliedSubmission(UUID transferId) {
      var owner = current.get();
      if (owner == null || transferId == null) {
         return new OperationDraftSettlement.Applied(OperationDraftSettlement.combine(
            OperationDraftSettlement.Ownership.UNCONFIRMED, OperationDraftSettlement.Ownership.UNCONFIRMED), false);
      }
      var store = store(owner);
      var receipt = store.find(transferId).orElse(null);
      if (receipt != null && !receipt.outcome().applied()) {
         store.record(receipt.withOutcome(OperationSubmissionOutcome.APPLIED, now.getAsLong()));
      }
      persist(owner, store);
      var drafts = clearDraftCopies(owner, transferId);
      if (drafts.confirmed()) store.confirmCleanup(transferId, now.getAsLong());
      persist(owner, store);
      return new OperationDraftSettlement.Applied(drafts, !store.readOnly() && !store.dirty());
   }

   public int replayPendingCleanups() {
      var owner = current.get();
      if (owner == null) return 0;
      var store = store(owner);
      int cleared = 0;
      for (var receipt : store.needingCleanup()) {
         if (clearDraftCopies(owner, receipt.transferId()).confirmed()) {
            store.confirmCleanup(receipt.transferId(), now.getAsLong());
            cleared++;
         }
      }
      persist(owner, store);
      return cleared;
   }

   @Override
   public boolean updateSubmissionReceipt(UUID transferId, OperationSubmissionOutcome outcome, long nowMillis) {
      var owner = current.get();
      if (owner == null || transferId == null || outcome == null) return false;
      var store = store(owner);
      var receipt = store.find(transferId).orElse(null);
      boolean updated = receipt != null && store.record(receipt.withOutcome(outcome, nowMillis));
      return persist(owner, store) && updated;
   }

   public boolean forgetSubmissionReceipt(UUID transferId) {
      var owner = current.get();
      if (owner == null || transferId == null) return false;
      var store = store(owner);
      boolean removed = store.remove(transferId);
      return persist(owner, store) && removed;
   }

   @Override
   public Optional<OperationSubmissionReceipt> submissionReceipt(UUID transferId) {
      var owner = current.get();
      return owner == null || transferId == null ? Optional.empty() : store(owner).find(transferId);
   }

   @Override
   public List<OperationSubmissionReceipt> openSubmissionReceipts() {
      var owner = current.get();
      return owner == null ? List.of() : store(owner).open();
   }

   private boolean persist(ClientScope owner, OperationSubmissionReceiptStore store) {
      if (!store.dirty()) return true;
      try { owner.receipts().save(); }
      catch (IOException | RuntimeException failure) {
         failed.accept("fastformer.message.operation_receipt_save_failed", failure);
      }
      return !store.dirty();
   }
}
