package io.github.fastformer.client.session;

import io.github.fastformer.client.operation.clipboard.OperationClipboardStore;
import io.github.fastformer.network.payload.operation.OperationSubmissionOutcome;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;

/**
 * Durable store for the workspace submission receipts of one client scope.
 *
 * <p>A save that fails must reach the player. A silent failure would let the client
 * claim a recovery that it cannot make.</p>
 */
public final class OperationSubmissionReceiptStore {
   private final Map<UUID, OperationSubmissionReceipt> receipts = new LinkedHashMap<>();
   private boolean dirty;
   private boolean readOnly;

   /**
    * A store that refuses to write.
    *
    * <p>A file that failed to read or that belongs to another version must stay
    * untouched. A later client version may read it again, so this store keeps the file
    * and reports every write attempt as a failure.</p>
    */
   public static OperationSubmissionReceiptStore unreadable() {
      OperationSubmissionReceiptStore store = new OperationSubmissionReceiptStore();
      store.readOnly = true;
      return store;
   }

   /** True when this store keeps an existing file instead of writing over it. */
   public boolean readOnly() {
      return this.readOnly;
   }

   /** Records or replaces one receipt. The caller persists with {@link #save(Path)}. */
   public boolean record(OperationSubmissionReceipt receipt) {
      if (receipt == null) {
         return false;
      }
      OperationSubmissionReceipt previous = this.receipts.get(receipt.transferId());
      if (previous != null && previous.outcome() == OperationSubmissionOutcome.APPLIED
         && !receipt.outcome().applied()) {
         // A known applied result is final. The server ledger can restart and then answer
         // UNKNOWN for work that the client already saw applied. A weaker answer must not
         // replace the applied record, because the client would then wait again or offer
         // the draft for a second send.
         return false;
      }
      if (previous == null && !makeRoom()) {
         return false;
      }
      this.receipts.put(receipt.transferId(), receipt);
      this.dirty = true;
      return true;
   }

   /**
    * True when this receipt may leave the store to make room.
    *
    * <p>A receipt that still waits for a result may not leave, because the server may
    * still apply that work. A receipt that applied but still owes a draft cleanup may not
    * leave either, because the cleanup is what stops that draft from returning to the
    * editor. Only a receipt with nothing left to do may leave.</p>
    */
   private static boolean reclaimable(OperationSubmissionReceipt receipt) {
      return !receipt.outcome().open() && !receipt.needsCleanup();
   }

   /**
    * Makes room for one new receipt.
    *
    * <p>The store never passes its limit, because a file that passes the limit cannot be
    * read again. Only a receipt with nothing left to do leaves.</p>
    *
    * @return true when the store can hold one more receipt
    */
   private boolean makeRoom() {
      if (this.receipts.size() < OperationSubmissionReceiptCodec.MAX_RECEIPTS) {
         return true;
      }
      for (OperationSubmissionReceipt receipt : this.receipts.values()) {
         if (reclaimable(receipt)) {
            this.receipts.remove(receipt.transferId());
            return true;
         }
      }
      // Every receipt still has work to do. The store cannot take another one, and the
      // caller must tell the player instead of dropping a pending obligation.
      return false;
   }

   /** True when the store can record this submission without dropping a live obligation. */
   public boolean canAccept(UUID transferId) {
      if (transferId != null && this.receipts.containsKey(transferId)) {
         return true;
      }
      if (this.receipts.size() < OperationSubmissionReceiptCodec.MAX_RECEIPTS) {
         return true;
      }
      for (OperationSubmissionReceipt receipt : this.receipts.values()) {
         if (reclaimable(receipt)) {
            return true;
         }
      }
      return false;
   }

   /** Returns the receipt of one submission, when the client recorded it. */
   public Optional<OperationSubmissionReceipt> find(UUID transferId) {
      return transferId == null ? Optional.empty() : Optional.ofNullable(this.receipts.get(transferId));
   }

   /** Returns every recorded receipt, oldest first. */
   public List<OperationSubmissionReceipt> all() {
      return List.copyOf(this.receipts.values());
   }

   /** Returns the receipts that still wait for a result. */
   public List<OperationSubmissionReceipt> open() {
      ArrayList<OperationSubmissionReceipt> result = new ArrayList<>();
      for (OperationSubmissionReceipt receipt : this.receipts.values()) {
         if (receipt.outcome().open()) {
            result.add(receipt);
         }
      }
      return List.copyOf(result);
   }

   /**
    * Returns the applied receipts whose local draft copy has not left yet.
    *
    * <p>The client replays these at a scope boundary. The flag is durable, so a restart
    * before the cleanup does not lose the known applied result.</p>
    */
   public List<OperationSubmissionReceipt> needingCleanup() {
      ArrayList<OperationSubmissionReceipt> result = new ArrayList<>();
      for (OperationSubmissionReceipt receipt : this.receipts.values()) {
         if (receipt.needsCleanup()) {
            result.add(receipt);
         }
      }
      return List.copyOf(result);
   }

   /**
    * Records that the local draft of one submission is gone.
    *
    * @return true when the change reached the memory of this store
    */
   public boolean confirmCleanup(UUID transferId, long nowMillis) {
      OperationSubmissionReceipt existing = transferId == null ? null : this.receipts.get(transferId);
      if (existing == null || !existing.needsCleanup()) {
         return false;
      }
      this.receipts.put(transferId, existing.withCleanupConfirmed(nowMillis));
      this.dirty = true;
      return true;
   }

   public boolean isEmpty() {
      return this.receipts.isEmpty();
   }

   /** Removes one receipt and marks the store for a save. */
   public boolean remove(UUID transferId) {
      if (transferId == null || this.receipts.remove(transferId) == null) {
         return false;
      }
      this.dirty = true;
      return true;
   }

   public void clear() {
      if (this.receipts.isEmpty()) {
         return;
      }
      this.receipts.clear();
      this.dirty = true;
   }

   /** True when a record changed since the last successful save. */
   public boolean dirty() {
      return this.dirty;
   }

   /**
    * Writes the receipts to disk.
    *
    * @throws IOException when the write fails. The caller must tell the player, because
    *                     a failed write means the client cannot restore this submission.
    */
   public void save(Path file) throws IOException {
      if (this.readOnly) {
         // The durable file could not be read or belongs to another version. Keep it.
         throw new IOException("The submission receipt file must not be overwritten");
      }
      if (this.receipts.isEmpty()) {
         // An empty store has no result to keep, so the file goes. A delete that fails
         // throws, and the caller reports it. A stale file would name submissions that
         // the client already reported, and the server ledger may have dropped or
         // restarted since, so the client must not read it as pending work.
         Files.deleteIfExists(file);
         this.dirty = false;
         return;
      }
      OperationClipboardStore.save(file, OperationSubmissionReceiptCodec.encode(all()));
      this.dirty = false;
   }

   /**
    * Reads the receipts of one scope.
    *
    * <p>A file that holds more receipts than the limit cannot be written back without
    * dropping one. The client keeps that file and reports the problem, because a drop
    * could lose the only record of work that the server still performs.</p>
    */
   public static OperationSubmissionReceiptStore load(Path file) throws IOException {
      OperationSubmissionReceiptStore store = new OperationSubmissionReceiptStore();
      Optional<CompoundTag> root = OperationClipboardStore.loadStrict(file);
      if (root.isEmpty()) {
         return store;
      }
      for (OperationSubmissionReceipt receipt : OperationSubmissionReceiptCodec.decode(root.get())) {
         store.receipts.put(receipt.transferId(), receipt);
      }
      store.dropFinishedOverLimit();
      if (store.receipts.size() > OperationSubmissionReceiptCodec.MAX_RECEIPTS) {
         // Every receipt still waits for a result. A drop would lose one of them, and a
         // write would pass the limit that a later read rejects. Keep the file.
         throw new IOException("Too many pending submission receipts");
      }
      store.dirty = false;
      return store;
   }

   /**
    * Drops reclaimable receipts until the store fits its limit.
    *
    * <p>A receipt that still has work to do stays. That covers a receipt that waits for a
    * result and a receipt that applied but still owes a draft cleanup.</p>
    */
   private void dropFinishedOverLimit() {
      if (this.receipts.size() <= OperationSubmissionReceiptCodec.MAX_RECEIPTS) {
         return;
      }
      int excess = this.receipts.size() - OperationSubmissionReceiptCodec.MAX_RECEIPTS;
      ArrayList<UUID> reclaimable = new ArrayList<>();
      for (OperationSubmissionReceipt receipt : this.receipts.values()) {
         if (reclaimable(receipt)) {
            reclaimable.add(receipt.transferId());
         }
      }
      for (UUID transferId : reclaimable) {
         if (excess <= 0) {
            break;
         }
         this.receipts.remove(transferId);
         excess--;
      }
   }

   /** The number of receipts that still have work to do. */
   public int pendingObligationCount() {
      int total = 0;
      for (OperationSubmissionReceipt receipt : this.receipts.values()) {
         if (!reclaimable(receipt)) {
            total++;
         }
      }
      return total;
   }
}
