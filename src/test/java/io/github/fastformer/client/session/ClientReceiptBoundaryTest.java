package io.github.fastformer.client.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fastformer.client.operation.clipboard.OperationClipboardStore;
import io.github.fastformer.client.operation.selection.ClientSelectionSession;
import io.github.fastformer.client.operation.workspace.ClientOperationWorkspace;
import io.github.fastformer.fastplace.selection.OperationSelectionMode;
import io.github.fastformer.network.payload.operation.OperationSubmissionOutcome;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The boundaries where the client cannot read the record of a submission.
 *
 * <p>A receipt file that cannot be read is not the same as a receipt file with no entry. The
 * file may hold an applied result for the very submission that a saved draft names, so the
 * client must not treat that draft as new. These tests drive the manager with real files at
 * an injected path, so the read path, the guard, and the retry boundary are covered rather
 * than a helper.</p>
 */
class ClientReceiptBoundaryTest {
   @TempDir
   Path directory;

   private static final ResourceLocation OVERWORLD =
      ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");

   private static ClientSessionManager.SessionKey keyFor(ClientPlayerSession session, String connection) {
      return new ClientSessionManager.SessionKey(connection, "minecraft:overworld", session.playerId());
   }

   // ---- the load guard against an unreadable receipt file ----

   @Test
   void aDraftIsNotStagedWhenItsReceiptFileCannotBeRead() throws Exception {
      ClientSessionManager manager = ClientSessionManager.instance();
      ClientPlayerSession session = new ClientPlayerSession(UUID.randomUUID());
      ClientSessionManager.SessionKey key = keyFor(session, "boundary-a");
      UUID transferId = UUID.randomUUID();
      Path draftFile = this.directory.resolve("draft.nbt.gz");
      OperationClipboardStore.save(draftFile, draftTag(transferId));

      // The receipt file is damaged. It may still name this submission as applied.
      Path receiptFile = this.directory.resolve("receipts.nbt.gz");
      Files.writeString(receiptFile, "not compressed nbt");
      manager.installReceiptFile(key, receiptFile);
      assertTrue(manager.receiptStore(key).readOnly());
      assertEquals(ClientDraftLoadState.RETRYABLE_FAILURE, manager.receiptLoadState(key));

      assertEquals(ClientDraftLoadState.AWAITING_RECEIPT, manager.attemptDraftLoad(key, session, null, draftFile));

      // The draft must not enter the staged slot, because the client cannot prove that its
      // submission is not already in the world. The read is not complete either, so the state
      // must not claim READY: a readable receipt re-opens this same file.
      assertFalse(session.suspendedOperationDraft());
      assertEquals(ClientDraftLoadState.AWAITING_RECEIPT, manager.draftLoadState(key));
      // The file stays, so an explicit retry can still read the receipt later.
      assertTrue(Files.exists(draftFile));
   }

   @Test
   void aDraftIsStagedWhenItsReceiptFileIsReadableAndHoldsNoEntry() throws Exception {
      ClientSessionManager manager = ClientSessionManager.instance();
      ClientPlayerSession session = new ClientPlayerSession(UUID.randomUUID());
      ClientSessionManager.SessionKey key = keyFor(session, "boundary-b");
      UUID transferId = UUID.randomUUID();
      Path draftFile = this.directory.resolve("draft.nbt.gz");
      OperationClipboardStore.save(draftFile, draftTag(transferId));

      // An empty receipt file is readable, and it holds no entry for this submission, so
      // the client has no evidence against the draft.
      Path receiptFile = this.directory.resolve("receipts.nbt.gz");
      manager.installReceiptFile(key, receiptFile);

      assertEquals(ClientDraftLoadState.READY, manager.attemptDraftLoad(key, session, null, draftFile));

      assertTrue(session.suspendedOperationDraft());
   }

   @Test
   void aDraftWithoutASubmissionIdIsStagedEvenWhenTheReceiptFileCannotBeRead() throws Exception {
      ClientSessionManager manager = ClientSessionManager.instance();
      ClientPlayerSession session = new ClientPlayerSession(UUID.randomUUID());
      ClientSessionManager.SessionKey key = keyFor(session, "boundary-c");
      Path draftFile = this.directory.resolve("draft.nbt.gz");
      // A draft from an older client names no submission, so no receipt answers for it.
      OperationClipboardStore.save(draftFile, draftTag(null));

      Path receiptFile = this.directory.resolve("receipts.nbt.gz");
      Files.writeString(receiptFile, "not compressed nbt");
      manager.installReceiptFile(key, receiptFile);

      assertEquals(ClientDraftLoadState.READY, manager.attemptDraftLoad(key, session, null, draftFile));

      assertTrue(session.suspendedOperationDraft());
   }

   @Test
   void anAppliedReceiptStillKeepsTheDraftOut() throws Exception {
      ClientSessionManager manager = ClientSessionManager.instance();
      ClientPlayerSession session = new ClientPlayerSession(UUID.randomUUID());
      ClientSessionManager.SessionKey key = keyFor(session, "boundary-d");
      UUID transferId = UUID.randomUUID();
      Path draftFile = this.directory.resolve("draft.nbt.gz");
      OperationClipboardStore.save(draftFile, draftTag(transferId));

      Path receiptFile = this.directory.resolve("receipts.nbt.gz");
      OperationSubmissionReceiptStore receipts = new OperationSubmissionReceiptStore();
      receipts.record(receipt(transferId, OperationSubmissionOutcome.APPLIED));
      receipts.save(receiptFile);
      manager.installReceiptFile(key, receiptFile);

      assertEquals(ClientDraftLoadState.READY, manager.attemptDraftLoad(key, session, null, draftFile));

      assertFalse(session.suspendedOperationDraft());
      assertTrue(Files.exists(draftFile));
   }

   // ---- the explicit retry boundary for the receipt file ----

   @Test
   void aFailedReceiptReadIsRetriedAtAnEnvironmentBoundary() throws Exception {
      ClientSessionManager manager = ClientSessionManager.instance();
      ClientPlayerSession session = new ClientPlayerSession(UUID.randomUUID());
      UUID playerId = session.playerId();
      manager.activateScope(playerId, "boundary-e", "minecraft:overworld");
      ClientSessionManager.SessionKey key = keyFor(session, "boundary-e");

      Path receiptFile = this.directory.resolve("receipts.nbt.gz");
      Files.writeString(receiptFile, "not compressed nbt");
      manager.installReceiptFile(key, receiptFile);
      assertTrue(manager.receiptStore(key).readOnly());
      assertEquals(ClientDraftLoadState.RETRYABLE_FAILURE, manager.currentReceiptLoadState());

      // Leaving and entering the environment again is the retry boundary. The state clears,
      // so the next use reads the file again instead of caching the failure forever.
      manager.activateScope(playerId, "boundary-other", "minecraft:overworld");
      manager.activateScope(playerId, "boundary-e", "minecraft:overworld");
      assertEquals(ClientDraftLoadState.NOT_LOADED, manager.currentReceiptLoadState());

      // The file is readable now. The boundary lets the client see the applied result.
      OperationSubmissionReceiptStore receipts = new OperationSubmissionReceiptStore();
      receipts.record(receipt(UUID.randomUUID(), OperationSubmissionOutcome.APPLIED));
      receipts.save(receiptFile);
      assertFalse(manager.receiptStore(key).readOnly());
   }

   @Test
   void aVersionMismatchWaitsForAnExplicitRetry() throws Exception {
      ClientSessionManager manager = ClientSessionManager.instance();
      ClientPlayerSession session = new ClientPlayerSession(UUID.randomUUID());
      UUID playerId = session.playerId();
      manager.activateScope(playerId, "boundary-f", "minecraft:overworld");
      ClientSessionManager.SessionKey key = keyFor(session, "boundary-f");

      Path receiptFile = this.directory.resolve("receipts.nbt.gz");
      CompoundTag future = new CompoundTag();
      future.putInt("Version", OperationSubmissionReceiptCodec.CURRENT_VERSION + 1);
      OperationClipboardStore.save(receiptFile, future);
      manager.installReceiptFile(key, receiptFile);

      assertTrue(manager.receiptStore(key).readOnly());
      assertEquals(ClientDraftLoadState.VERSION_INCOMPATIBLE, manager.currentReceiptLoadState());

      // A newer format does not clear at an environment boundary, because only a newer
      // client can read it. An explicit retry is still offered and still re-reads it.
      manager.activateScope(playerId, "boundary-other", "minecraft:overworld");
      manager.activateScope(playerId, "boundary-f", "minecraft:overworld");
      assertEquals(ClientDraftLoadState.VERSION_INCOMPATIBLE, manager.currentReceiptLoadState());
      assertTrue(manager.retryCurrentReceiptLoad());
      assertEquals(ClientDraftLoadState.NOT_LOADED, manager.currentReceiptLoadState());
      assertTrue(manager.receiptStore(key).readOnly());
   }

   @Test
   void aRetryWithoutAFailureReportsNothingToDo() {
      ClientSessionManager manager = ClientSessionManager.instance();
      ClientPlayerSession session = new ClientPlayerSession(UUID.randomUUID());
      manager.activateScope(session.playerId(), "boundary-g", "minecraft:overworld");

      // Nothing failed for this scope, so there is no retry to make.
      assertFalse(manager.retryCurrentReceiptLoad());
   }

   // ---- settlement without a usable record ----

   @Test
   void aSettlementOnAnUnreadableReceiptFileIsNotReportedAsRecorded() throws Exception {
      ClientSessionManager manager = ClientSessionManager.instance();
      ClientPlayerSession session = new ClientPlayerSession(UUID.randomUUID());
      UUID transferId = UUID.randomUUID();
      manager.activateScope(session.playerId(), "boundary-h", "minecraft:overworld");
      ClientSessionManager.SessionKey key = keyFor(session, "boundary-h");

      Path receiptFile = this.directory.resolve("receipts.nbt.gz");
      Files.writeString(receiptFile, "not compressed nbt");
      manager.installReceiptFile(key, receiptFile);

      OperationDraftSettlement.Applied applied = manager.settleAppliedSubmission(transferId);

      // The record is not durable, because the file cannot be read. The client must not
      // treat "no evidence" as proof that the submission was settled.
      assertFalse(applied.recordSaved());
      assertFalse(applied.confirmed());
   }

   @Test
   void aSettlementWithNoReceiptAndAReadableFileIsRecorded() throws Exception {
      ClientSessionManager manager = ClientSessionManager.instance();
      ClientPlayerSession session = new ClientPlayerSession(UUID.randomUUID());
      UUID transferId = UUID.randomUUID();
      manager.activateScope(session.playerId(), "boundary-i", "minecraft:overworld");
      ClientSessionManager.SessionKey key = keyFor(session, "boundary-i");
      manager.installReceiptFile(key, this.directory.resolve("receipts.nbt.gz"));

      OperationDraftSettlement.Applied applied = manager.settleAppliedSubmission(transferId);

      // A readable file with no entry owes nothing, and nothing changed, so the durable
      // record is complete. This is the case that must stay confirmed.
      assertTrue(applied.recordSaved());
   }

   @Test
   void anAppliedReceiptIsRecordedWhenTheFileIsWritable() throws Exception {
      ClientSessionManager manager = ClientSessionManager.instance();
      ClientPlayerSession session = new ClientPlayerSession(UUID.randomUUID());
      UUID transferId = UUID.randomUUID();
      manager.activateScope(session.playerId(), "boundary-j", "minecraft:overworld");
      ClientSessionManager.SessionKey key = keyFor(session, "boundary-j");
      Path receiptFile = this.directory.resolve("receipts.nbt.gz");

      OperationSubmissionReceiptStore receipts = new OperationSubmissionReceiptStore();
      receipts.record(receipt(transferId, OperationSubmissionOutcome.IN_PROGRESS));
      receipts.save(receiptFile);
      manager.installReceiptFile(key, receiptFile);

      OperationDraftSettlement.Applied applied = manager.settleAppliedSubmission(transferId);

      assertTrue(applied.recordSaved());
      OperationSubmissionReceipt stored =
         OperationSubmissionReceiptStore.load(receiptFile).find(transferId).orElseThrow();
      // The applied result is on the disk. Whether a cleanup is still owed depends on the
      // draft copy, which this test does not place.
      assertEquals(OperationSubmissionOutcome.APPLIED, stored.outcome());
   }

   // ---- a pending write always has an owner ----

   @Test
   void aForgetRetriesAWriteThatAnEarlierCallLeftPending() throws Exception {
      ClientSessionManager manager = ClientSessionManager.instance();
      ClientPlayerSession session = new ClientPlayerSession(UUID.randomUUID());
      manager.activateScope(session.playerId(), "boundary-k", "minecraft:overworld");
      ClientSessionManager.SessionKey key = keyFor(session, "boundary-k");
      Path receiptFile = this.directory.resolve("receipts.nbt.gz");
      manager.installReceiptFile(key, receiptFile);

      UUID transferId = UUID.randomUUID();
      OperationSubmissionReceiptStore receipts = manager.receiptStore(key);
      receipts.record(receipt(transferId, OperationSubmissionOutcome.APPLIED));
      assertTrue(receipts.dirty());

      // The record is in memory and the disk is empty. Removing it here changes nothing,
      // but this call must still write, because the store is the only owner of the pending
      // write and no other call is coming.
      assertTrue(manager.forgetSubmissionReceipt(transferId));
      assertFalse(receipts.dirty());
      assertFalse(Files.exists(receiptFile));
   }

   @Test
   void anUpdateFlushesAWriteThatAnEarlierCallLeftPending() throws Exception {
      ClientSessionManager manager = ClientSessionManager.instance();
      ClientPlayerSession session = new ClientPlayerSession(UUID.randomUUID());
      manager.activateScope(session.playerId(), "boundary-l", "minecraft:overworld");
      ClientSessionManager.SessionKey key = keyFor(session, "boundary-l");
      Path receiptFile = this.directory.resolve("receipts.nbt.gz");
      manager.installReceiptFile(key, receiptFile);

      UUID known = UUID.randomUUID();
      OperationSubmissionReceiptStore receipts = manager.receiptStore(key);
      receipts.record(receipt(known, OperationSubmissionOutcome.APPLIED));
      assertTrue(receipts.dirty());

      // This call changes nothing for the unknown id, and it must still flush the pending
      // write of the known record.
      assertFalse(manager.updateSubmissionReceipt(
         UUID.randomUUID(), OperationSubmissionOutcome.FAILED_RETRYABLE, 10L
      ));
      assertFalse(receipts.dirty());
      assertTrue(OperationSubmissionReceiptStore.load(receiptFile).find(known).isPresent());
   }

   @Test
   void aFailedWriteStaysPendingAndTheNextCallRetriesIt() throws Exception {
      ClientSessionManager manager = ClientSessionManager.instance();
      ClientPlayerSession session = new ClientPlayerSession(UUID.randomUUID());
      manager.activateScope(session.playerId(), "boundary-m", "minecraft:overworld");
      ClientSessionManager.SessionKey key = keyFor(session, "boundary-m");

      // A regular file where the receipt directory must go. Every write fails, with a real
      // file system error rather than an injected flag.
      Path blocker = this.directory.resolve("blocker");
      Files.writeString(blocker, "x");
      manager.installReceiptFile(key, blocker.resolve("receipts.nbt.gz"));

      UUID transferId = UUID.randomUUID();
      UUID keeper = UUID.randomUUID();
      OperationSubmissionReceiptStore receipts = manager.receiptStore(key);
      receipts.record(receipt(transferId, OperationSubmissionOutcome.APPLIED));
      // A second receipt keeps the store non-empty, so the save must write a file. An empty
      // store only deletes a file that does not exist, and that delete reports success even
      // behind an unusable path, so a single receipt would never reach a real write failure.
      receipts.record(receipt(keeper, OperationSubmissionOutcome.APPLIED));

      // The write fails, and the store reports that it still differs from the disk.
      assertFalse(manager.forgetSubmissionReceipt(transferId));
      assertTrue(receipts.dirty());
      assertTrue(receipts.find(keeper).isPresent(), "the removal dropped another receipt");

      // The blocker leaves. The next mutator call writes the pending change, so no write is
      // stranded by the early return of the call that produced it.
      Files.delete(blocker);
      assertFalse(manager.updateSubmissionReceipt(
         UUID.randomUUID(), OperationSubmissionOutcome.UNKNOWN, 20L
      ));
      assertFalse(receipts.dirty());
   }

   // ---- the receipt answer re-opens the draft read ----

   @Test
   void aRepairedReceiptLetsTheWaitedDraftArrive() throws Exception {
      ClientSessionManager manager = ClientSessionManager.instance();
      ClientPlayerSession session = new ClientPlayerSession(UUID.randomUUID());
      manager.activateScope(session.playerId(), "boundary-n", "minecraft:overworld");
      ClientSessionManager.SessionKey key = keyFor(session, "boundary-n");
      UUID transferId = UUID.randomUUID();
      Path draftFile = this.directory.resolve("draft.nbt.gz");
      OperationClipboardStore.save(draftFile, draftTag(transferId));

      Path receiptFile = this.directory.resolve("receipts.nbt.gz");
      Files.writeString(receiptFile, "not compressed nbt");
      manager.installReceiptFile(key, receiptFile);
      assertEquals(ClientDraftLoadState.AWAITING_RECEIPT, manager.attemptDraftLoad(key, session, null, draftFile));
      assertFalse(session.suspendedOperationDraft());

      // The receipt file is repaired. The explicit retry boundary clears the failed read, so
      // the next use reads the file again and answers the question the draft waited for.
      Files.delete(receiptFile);
      assertTrue(manager.retryCurrentReceiptLoad());
      assertFalse(manager.receiptStore(key).readOnly());

      assertEquals(ClientDraftLoadState.READY, manager.attemptDraftLoad(key, session, null, draftFile));
      assertTrue(session.suspendedOperationDraft(), "the repaired receipt did not release the draft");
   }

   @Test
   void aRepairedReceiptFileIsNotReadWithoutABoundary() throws Exception {
      ClientSessionManager manager = ClientSessionManager.instance();
      ClientPlayerSession session = new ClientPlayerSession(UUID.randomUUID());
      ClientSessionManager.SessionKey key = keyFor(session, "boundary-o");
      UUID transferId = UUID.randomUUID();
      Path draftFile = this.directory.resolve("draft.nbt.gz");
      OperationClipboardStore.save(draftFile, draftTag(transferId));

      Path receiptFile = this.directory.resolve("receipts.nbt.gz");
      Files.writeString(receiptFile, "not compressed nbt");
      manager.installReceiptFile(key, receiptFile);
      assertEquals(ClientDraftLoadState.AWAITING_RECEIPT, manager.attemptDraftLoad(key, session, null, draftFile));

      // The file becomes readable on its own. Without a boundary the wait stays closed, so a
      // damaged file is read once instead of on every tick.
      Files.delete(receiptFile);
      assertEquals(ClientDraftLoadState.AWAITING_RECEIPT, manager.attemptDraftLoad(key, session, null, draftFile));
      assertFalse(session.suspendedOperationDraft());
   }

   @Test
   void anAppliedReceiptKeepsTheWaitedDraftOut() throws Exception {
      ClientSessionManager manager = ClientSessionManager.instance();
      ClientPlayerSession session = new ClientPlayerSession(UUID.randomUUID());
      manager.activateScope(session.playerId(), "boundary-p", "minecraft:overworld");
      ClientSessionManager.SessionKey key = keyFor(session, "boundary-p");
      UUID transferId = UUID.randomUUID();
      Path draftFile = this.directory.resolve("draft.nbt.gz");
      OperationClipboardStore.save(draftFile, draftTag(transferId));

      Path receiptFile = this.directory.resolve("receipts.nbt.gz");
      Files.writeString(receiptFile, "not compressed nbt");
      manager.installReceiptFile(key, receiptFile);
      assertEquals(ClientDraftLoadState.AWAITING_RECEIPT, manager.attemptDraftLoad(key, session, null, draftFile));

      // The repaired file names this very submission as applied, so the answer is final.
      OperationSubmissionReceiptStore receipts = new OperationSubmissionReceiptStore();
      receipts.record(receipt(transferId, OperationSubmissionOutcome.APPLIED));
      Files.delete(receiptFile);
      receipts.save(receiptFile);
      assertTrue(manager.retryCurrentReceiptLoad());

      assertEquals(ClientDraftLoadState.READY, manager.attemptDraftLoad(key, session, null, draftFile));
      assertFalse(session.suspendedOperationDraft(), "an applied submission returned to the editor");
      assertTrue(Files.exists(draftFile), "the applied draft file left without a confirmed cleanup");
   }

   @Test
   void aDiscardedDraftDoesNotReturnWhenTheReceiptRecovers() throws Exception {
      ClientSessionManager manager = ClientSessionManager.instance();
      ClientPlayerSession session = new ClientPlayerSession(UUID.randomUUID());
      manager.activateScope(session.playerId(), "boundary-q", "minecraft:overworld");
      ClientSessionManager.SessionKey key = keyFor(session, "boundary-q");
      UUID transferId = UUID.randomUUID();
      Path draftFile = this.directory.resolve("draft.nbt.gz");
      OperationClipboardStore.save(draftFile, draftTag(transferId));
      // The delete path of an explicit discard must use this file, so the flow is real and
      // needs no game directory.
      manager.installDraftFile(key, draftFile);

      Path receiptFile = this.directory.resolve("receipts.nbt.gz");
      Files.writeString(receiptFile, "not compressed nbt");
      manager.installReceiptFile(key, receiptFile);
      assertEquals(ClientDraftLoadState.AWAITING_RECEIPT, manager.attemptDraftLoad(key, session, null, draftFile));

      // The player ends the draft. That decision outranks a receipt that becomes readable
      // later, so the wait must not re-open.
      manager.discardCurrentDraft();
      assertEquals(ClientDraftLoadState.READY, manager.draftLoadState(key));
      assertFalse(Files.exists(draftFile), "the explicit discard left the draft file");

      Files.delete(receiptFile);
      assertTrue(manager.retryCurrentReceiptLoad());

      assertEquals(ClientDraftLoadState.READY, manager.attemptDraftLoad(key, session, null, draftFile));
      assertFalse(session.suspendedOperationDraft(), "a discarded draft returned to the editor");
   }

   @Test
   void liveWorkKeepsTheDurableCopyOutOfTheEditor() throws Exception {
      ClientSessionManager manager = ClientSessionManager.instance();
      ClientPlayerSession session = new ClientPlayerSession(UUID.randomUUID());
      ClientSessionManager.SessionKey key = keyFor(session, "boundary-r");
      UUID transferId = UUID.randomUUID();
      Path draftFile = this.directory.resolve("draft.nbt.gz");
      OperationClipboardStore.save(draftFile, draftTag(transferId));
      manager.installReceiptFile(key, this.directory.resolve("receipts.nbt.gz"));

      // The player is already using a selection in this environment. The receipt file is
      // readable and holds no entry, so only the live work keeps the copy out.
      session.selectionSession().restoreDraftState(new ClientSelectionSession.DraftState(
         OperationSelectionMode.CUBOID, List.of(new BlockPos(1, 2, 3)), 0, null, null
      ));
      assertTrue(session.selectionSession().hasDraft());

      assertEquals(ClientDraftLoadState.NOT_LOADED, manager.attemptDraftLoad(key, session, null, draftFile));
      assertFalse(session.suspendedOperationDraft(), "the read replaced live work");
      assertEquals(ClientDraftLoadState.NOT_LOADED, manager.draftLoadState(key));

      // The live selection leaves. The next pass reads the file and stages the copy.
      session.selectionSession().clearDraft();
      assertEquals(ClientDraftLoadState.READY, manager.attemptDraftLoad(key, session, null, draftFile));
      assertTrue(session.suspendedOperationDraft());
   }

   // ---- helpers ----

   private static OperationSubmissionReceipt receipt(UUID transferId, OperationSubmissionOutcome outcome) {
      return new OperationSubmissionReceipt(
         transferId, OperationSubmissionOrigin.LOCAL_ONLY, null, OVERWORLD, outcome, 1000L
      );
   }

   /** A real draft file that names one submission, or none. */
   private static CompoundTag draftTag(UUID submissionId) {
      ClientOperationDraft draft = new ClientOperationDraft(
         null,
         new ClientOperationWorkspace.DraftState(List.of(), java.util.Set.of(), 0),
         new ClientSelectionSession.DraftState(
            OperationSelectionMode.CUBOID, List.of(new BlockPos(1, 2, 3)), 0, null, null
         )
      );
      CompoundTag tag = ClientOperationDraftCodec.encode(draft);
      if (submissionId != null) {
         tag.putUUID("SubmissionId", submissionId);
      }
      return tag;
   }
}
