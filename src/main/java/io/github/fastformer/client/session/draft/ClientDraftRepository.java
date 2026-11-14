package io.github.fastformer.client.session.draft;

import io.github.fastformer.client.operation.clipboard.OperationClipboardStore;
import io.github.fastformer.client.session.submission.OperationDraftSettlement;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.core.HolderGetter;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.Block;

/** Current-format draft persistence and read boundaries for one client scope. */
public final class ClientDraftRepository {
   private final Supplier<Path> defaultFile;
   private Path explicitFile;
   private ClientDraftLoadState readState = ClientDraftLoadState.NOT_LOADED;
   private ClientOperationDraft durableDraft;

   public ClientDraftRepository(Supplier<Path> defaultFile) {
      this.defaultFile = Objects.requireNonNull(defaultFile, "defaultFile");
   }

   public Path file() {
      return explicitFile == null ? defaultFile.get() : explicitFile;
   }

   public void useFile(Path file) {
      explicitFile = Objects.requireNonNull(file, "file");
   }

   public ClientDraftLoadState readState() {
      return readState;
   }

   public boolean retry() {
      if (!readState.mayRetry()) return false;
      readState = ClientDraftLoadState.NOT_LOADED;
      return true;
   }

   public void retryTransientFailure() {
      if (readState == ClientDraftLoadState.RETRYABLE_FAILURE) retry();
   }

   public void rearmAfterReceipt() {
      if (readState == ClientDraftLoadState.AWAITING_RECEIPT) readState = ClientDraftLoadState.NOT_LOADED;
   }

   public void markReady() {
      readState = ClientDraftLoadState.READY;
   }

   public void restoreRejected() {
      readState = ClientDraftLoadState.RETRYABLE_FAILURE;
   }

   /** Only confirmation of these exact immutable contents permits staged work to be evicted. */
   public void confirmDurable(ClientOperationDraft draft) {
      durableDraft = Objects.requireNonNull(draft, "draft");
      markReady();
   }

   public boolean canEvict(ClientOperationDraft staged) {
      return (staged == null || staged == durableDraft)
         && (readState == ClientDraftLoadState.NOT_LOADED || readState == ClientDraftLoadState.READY);
   }

   public enum ReceiptVerdict { APPLIED, NOT_APPLIED, UNKNOWN }

   public enum ReadNotice { NONE, APPLIED, AWAITING_RECEIPT }

   /** Does not stage content or change live input/selection state. */
   public ReadResult read(HolderGetter<Block> blocks, boolean liveWork,
      Function<UUID, ReceiptVerdict> receiptVerdict) {
      if (readState.blocksRead() || liveWork) return result(null, null, ReadNotice.NONE);
      CompoundTag root;
      try {
         root = OperationClipboardStore.loadStrict(file()).orElse(null);
      } catch (IOException | RuntimeException failure) {
         readState = ClientDraftLoadState.RETRYABLE_FAILURE;
         return result(null, failure, ReadNotice.NONE);
      }
      if (root == null) {
         markReady();
         return result(null, null, ReadNotice.NONE);
      }
      ClientOperationDraft draft;
      try {
         draft = ClientOperationDraftCodec.decode(root, blocks);
      } catch (ClientOperationDraftCodec.VersionMismatchException failure) {
         readState = ClientDraftLoadState.VERSION_INCOMPATIBLE;
         return result(null, failure, ReadNotice.NONE);
      } catch (IOException | RuntimeException failure) {
         readState = ClientDraftLoadState.CORRUPT;
         return result(null, failure, ReadNotice.NONE);
      }
      ReceiptVerdict verdict = receiptVerdict.apply(draft.submissionId());
      if (verdict == ReceiptVerdict.APPLIED) {
         markReady();
         return result(null, null, ReadNotice.APPLIED);
      }
      if (verdict == ReceiptVerdict.UNKNOWN) {
         readState = ClientDraftLoadState.AWAITING_RECEIPT;
         return result(null, null, ReadNotice.AWAITING_RECEIPT);
      }
      confirmDurable(draft);
      return result(draft, null, ReadNotice.NONE);
   }

   private ReadResult result(ClientOperationDraft draft, Exception failure, ReadNotice notice) {
      return new ReadResult(readState, draft, failure, notice);
   }

   public record ReadResult(ClientDraftLoadState state, ClientOperationDraft draft,
      Exception failure, ReadNotice notice) {}

   public OperationDraftSettlement.DurableProbe probe() throws IOException {
      var root = OperationClipboardStore.loadStrict(file());
      if (root.isEmpty()) return OperationDraftSettlement.DurableProbe.absent();
      UUID submission = ClientOperationDraftCodec.readSubmissionId(root.get());
      if (submission == null && root.get().getBoolean("Unsubmitted")) {
         return OperationDraftSettlement.DurableProbe.absent();
      }
      return OperationDraftSettlement.DurableProbe.read(submission);
   }

   public void delete() throws IOException {
      OperationClipboardStore.delete(file());
      durableDraft = null;
   }

   /** Clears only this submission, retaining an unsubmitted remainder when present. */
   public void settle(UUID transferId) throws IOException {
      var root = OperationClipboardStore.loadStrict(file()).orElse(null);
      if (root == null || !transferId.equals(ClientOperationDraftCodec.readSubmissionId(root))) return;
      if (!root.contains("Remainder", Tag.TAG_COMPOUND)) {
         delete();
         return;
      }
      OperationClipboardStore.save(file(), root.getCompound("Remainder"));
      readState = ClientDraftLoadState.NOT_LOADED;
      durableDraft = null;
   }
}
