package io.github.fastformer.client.session;

import io.github.fastformer.network.payload.operation.OperationSubmissionOutcome;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

/**
 * Versioned NBT codec for the durable workspace submission receipts of one client scope.
 *
 * <p>The receipt file is separate from the draft file. A receipt records what the
 * client knew about a submission at the time of the disconnect, so the client can ask
 * the server what happened.</p>
 */
public final class OperationSubmissionReceiptCodec {
   public static final int CURRENT_VERSION = 1;
   /** Receipts kept for one scope. A write above this limit is refused. */
   public static final int MAX_RECEIPTS = 32;
   /**
    * Receipts that this client reads from one file.
    *
    * <p>This bound is larger than {@link #MAX_RECEIPTS} on purpose. An older client or a
    * damaged write can leave a larger file, and a refusal would make every receipt in it
    * unreadable. The client keeps such a file and reports the capacity problem instead of
    * losing the results that the file holds.</p>
    */
   public static final int MAX_DECODED_RECEIPTS = 256;
   private static final String VERSION_KEY = "Version";
   private static final String RECEIPTS_KEY = "Receipts";

   private OperationSubmissionReceiptCodec() {
   }

   public static CompoundTag encode(List<OperationSubmissionReceipt> receipts) {
      if (receipts.size() > MAX_RECEIPTS) {
         throw new IllegalArgumentException("Too many submission receipts");
      }
      CompoundTag root = new CompoundTag();
      root.putInt(VERSION_KEY, CURRENT_VERSION);
      ListTag list = new ListTag();
      for (OperationSubmissionReceipt receipt : receipts) {
         list.add(encodeReceipt(receipt));
      }
      root.put(RECEIPTS_KEY, list);
      return root;
   }

   public static List<OperationSubmissionReceipt> decode(CompoundTag root) throws IOException {
      if (root == null) {
         throw new IOException("Missing submission receipt root");
      }
      if (!root.contains(VERSION_KEY, Tag.TAG_INT)) {
         throw new IOException("Submission receipts have no version");
      }
      if (root.getInt(VERSION_KEY) != CURRENT_VERSION) {
         throw new VersionMismatchException("Unsupported submission receipt version");
      }
      ListTag list = root.getList(RECEIPTS_KEY, Tag.TAG_COMPOUND);
      if (list.size() > MAX_DECODED_RECEIPTS) {
         throw new IOException("Too many submission receipts to read");
      }
      ArrayList<OperationSubmissionReceipt> receipts = new ArrayList<>(list.size());
      for (int index = 0; index < list.size(); index++) {
         receipts.add(decodeReceipt(list.getCompound(index)));
      }
      // The client never reads IN_FLIGHT as an answered state. A receipt that stalls at
      // IN_FLIGHT becomes a query, and the server answers it.
      return List.copyOf(receipts);
   }

   /**
    * Raised when a durable receipt file belongs to another format version.
    *
    * <p>Callers must keep the file. A version mismatch is not corruption, and a later
    * client version may read the same file again.</p>
    */
   public static final class VersionMismatchException extends IOException {
      public VersionMismatchException(String message) {
         super(message);
      }
   }

   private static CompoundTag encodeReceipt(OperationSubmissionReceipt receipt) {
      CompoundTag tag = new CompoundTag();
      tag.putUUID("TransferId", receipt.transferId());
      tag.putString("Origin", receipt.origin().name());
      tag.putString("Outcome", receipt.outcome().name());
      tag.putString("Dimension", receipt.dimension().toString());
      tag.putLong("RecordedAt", receipt.recordedAtMillis());
      if (receipt.outcome().applied()) {
         // An applied record always writes the flag, both when it is true and when it is
         // false. An absent flag then means one thing only: a file from an older client,
         // which never confirmed a cleanup. An older reader ignores this key.
         tag.putBoolean("CleanupPending", receipt.needsCleanup());
      }
      if (receipt.identity() != null) {
         tag.put("Identity", ClientOperationDraftCodec.encodeIdentity(receipt.identity()));
      }
      return tag;
   }

   private static OperationSubmissionReceipt decodeReceipt(CompoundTag tag) throws IOException {
      OperationSubmissionOrigin origin = enumValue(
         OperationSubmissionOrigin.class, tag.getString("Origin"), "submission origin"
      );
      OperationSubmissionOutcome outcome = enumValue(
         OperationSubmissionOutcome.class, tag.getString("Outcome"), "submission outcome"
      );
      ResourceLocation dimension = ResourceLocation.tryParse(tag.getString("Dimension"));
      if (dimension == null) {
         throw new IOException("Submission receipt has an invalid dimension");
      }
      OperationDraftIdentity identity = tag.contains("Identity", Tag.TAG_COMPOUND)
         ? ClientOperationDraftCodec.decodeIdentity(tag.getCompound("Identity")) : null;
      boolean hasTransferId = tag.contains("TransferId");
      if (!hasTransferId) {
         throw new IOException("Submission receipt has no transfer id");
      }
      // A file from an older client carries no cleanup flag. An applied record in such a
      // file may still own a draft copy, because the older writer deleted that copy on a
      // best-effort path. Treat it as pending, so the client clears the copy once at the
      // next boundary. The cleanup is safe: it removes a draft only when that draft names
      // this submission.
      boolean cleanupPending = tag.contains("CleanupPending", Tag.TAG_BYTE)
         ? tag.getBoolean("CleanupPending") : outcome.applied();
      try {
         return new OperationSubmissionReceipt(
            tag.getUUID("TransferId"), origin, identity, dimension, outcome,
            tag.getLong("RecordedAt"), cleanupPending
         );
      } catch (IllegalArgumentException exception) {
         throw new IOException("Submission receipt is inconsistent", exception);
      }
   }

   private static <E extends Enum<E>> E enumValue(Class<E> type, String value, String name) throws IOException {
      try {
         return Enum.valueOf(type, value);
      } catch (IllegalArgumentException exception) {
         throw new IOException("Invalid submission receipt " + name, exception);
      }
   }
}
