package io.github.fastformer.client.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fastformer.client.operation.clipboard.OperationClipboardStore;
import io.github.fastformer.client.operation.selection.ClientSelectionSession;
import io.github.fastformer.client.operation.workspace.ClientOperationWorkspace;
import io.github.fastformer.fastplace.selection.OperationSelectionMode;
import io.github.fastformer.network.payload.operation.OperationSubmissionOutcome;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests the settlement of an applied submission over its two draft owners.
 *
 * <p>The decision tests inject the two inputs, so a delete failure and an unreadable file
 * are forced directly. The replay tests use real files, a real receipt store, and real
 * draft NBT, so the storage path is exercised rather than mirrored.</p>
 */
class OperationDraftSettlementTest {
   private static final ResourceLocation OVERWORLD =
      ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");
   private static final UUID APPLIED_ID = new UUID(0xAA01L, 1L);
   private static final UUID NEWER_ID = new UUID(0xAA02L, 2L);

   // ---- staged owner ----

   @Test
   void absenceOfAStagedDraftIsConfirmed() {
      AtomicBoolean cleared = new AtomicBoolean();

      OperationDraftSettlement.Ownership result = OperationDraftSettlement.resolveStaged(
         APPLIED_ID, null, () -> cleared.set(true)
      );

      assertEquals(OperationDraftSettlement.Ownership.ABSENT, result);
      assertFalse(cleared.get());
   }

   @Test
   void aStagedDraftOfAnotherSubmissionStays() {
      AtomicBoolean cleared = new AtomicBoolean();

      // The player built this draft after the send. It is not the applied submission's
      // copy, so it stays.
      OperationDraftSettlement.Ownership result = OperationDraftSettlement.resolveStaged(
         APPLIED_ID, NEWER_ID, () -> cleared.set(true)
      );

      assertEquals(OperationDraftSettlement.Ownership.ABSENT, result);
      assertFalse(cleared.get());
   }

   @Test
   void aStagedDraftOfTheSubmissionLeaves() {
      AtomicBoolean cleared = new AtomicBoolean();

      OperationDraftSettlement.Ownership result = OperationDraftSettlement.resolveStaged(
         APPLIED_ID, APPLIED_ID, () -> cleared.set(true)
      );

      // Clearing memory cannot fail, so this owner always confirms.
      assertEquals(OperationDraftSettlement.Ownership.REMOVED, result);
      assertTrue(cleared.get());
   }

   // ---- durable owner ----

   @Test
   void aMissingFileIsConfirmed() {
      AtomicBoolean removed = new AtomicBoolean();

      OperationDraftSettlement.Ownership result = OperationDraftSettlement.resolveDurable(
         APPLIED_ID, OperationDraftSettlement.DurableProbe.absent(), () -> {
            removed.set(true);
            return true;
         }
      );

      assertEquals(OperationDraftSettlement.Ownership.ABSENT, result);
      assertFalse(removed.get());
   }

   @Test
   void anUnreadableFileIsUnconfirmedAndStays() {
      AtomicBoolean removed = new AtomicBoolean();

      // The client cannot prove which submission owns this file. A delete could destroy
      // the draft of another submission.
      OperationDraftSettlement.Ownership result = OperationDraftSettlement.resolveDurable(
         APPLIED_ID, OperationDraftSettlement.DurableProbe.unreadable(), () -> {
            removed.set(true);
            return true;
         }
      );

      assertEquals(OperationDraftSettlement.Ownership.UNCONFIRMED, result);
      assertFalse(removed.get());
   }

   @Test
   void aFileThatNamesNoSubmissionIsUnconfirmed() {
      AtomicBoolean removed = new AtomicBoolean();

      OperationDraftSettlement.Ownership result = OperationDraftSettlement.resolveDurable(
         APPLIED_ID, OperationDraftSettlement.unattributed(), () -> {
            removed.set(true);
            return true;
         }
      );

      assertEquals(OperationDraftSettlement.Ownership.UNCONFIRMED, result);
      assertFalse(removed.get());
   }

   @Test
   void aFileOfAnotherSubmissionStays() {
      AtomicBoolean removed = new AtomicBoolean();

      OperationDraftSettlement.Ownership result = OperationDraftSettlement.resolveDurable(
         APPLIED_ID, OperationDraftSettlement.DurableProbe.read(NEWER_ID), () -> {
            removed.set(true);
            return true;
         }
      );

      assertEquals(OperationDraftSettlement.Ownership.ABSENT, result);
      assertFalse(removed.get());
   }

   @Test
   void aFileOfTheSubmissionLeaves() {
      AtomicBoolean removed = new AtomicBoolean();

      // The delete must succeed for this owner to confirm. A delete that fails is the
      // separate case below, and `resolveDurable` reports it as UNCONFIRMED.
      OperationDraftSettlement.Ownership result = OperationDraftSettlement.resolveDurable(
         APPLIED_ID, OperationDraftSettlement.DurableProbe.read(APPLIED_ID), () -> {
            removed.set(true);
            return true;
         }
      );

      assertEquals(OperationDraftSettlement.Ownership.REMOVED, result);
      assertTrue(removed.get());
   }

   @Test
   void aFailedDeleteIsUnconfirmed() {
      // The file is still there. The client must not claim that the copy is gone.
      OperationDraftSettlement.Ownership result = OperationDraftSettlement.resolveDurable(
         APPLIED_ID, OperationDraftSettlement.DurableProbe.read(APPLIED_ID), () -> false
      );

      assertEquals(OperationDraftSettlement.Ownership.UNCONFIRMED, result);
   }

   // ---- the two owners stay independent ----

   @Test
   void eachOwnerIsExaminedOnItsOwn() {
      AtomicBoolean stagedCleared = new AtomicBoolean();
      AtomicBoolean durableRemoved = new AtomicBoolean();

      // The staged copy belongs to the applied submission, and the file belongs to a newer
      // submission. The staged copy leaves on its own, without touching the file.
      OperationDraftSettlement.Ownership staged = OperationDraftSettlement.resolveStaged(
         APPLIED_ID, APPLIED_ID, () -> stagedCleared.set(true)
      );
      OperationDraftSettlement.Ownership durable = OperationDraftSettlement.resolveDurable(
         APPLIED_ID, OperationDraftSettlement.DurableProbe.read(NEWER_ID), () -> {
            durableRemoved.set(true);
            return true;
         }
      );

      assertEquals(OperationDraftSettlement.Ownership.REMOVED, staged);
      assertEquals(OperationDraftSettlement.Ownership.ABSENT, durable);
      assertTrue(stagedCleared.get());
      assertFalse(durableRemoved.get());
      assertTrue(OperationDraftSettlement.combine(staged, durable).confirmed());
   }

   @Test
   void theResultIsConfirmedOnlyWhenNoOwnerIsUnconfirmed() {
      OperationDraftSettlement.Ownership[] values = OperationDraftSettlement.Ownership.values();
      for (OperationDraftSettlement.Ownership staged : values) {
         for (OperationDraftSettlement.Ownership durable : values) {
            boolean expected = staged != OperationDraftSettlement.Ownership.UNCONFIRMED
               && durable != OperationDraftSettlement.Ownership.UNCONFIRMED;
            assertEquals(
               expected,
               OperationDraftSettlement.combine(staged, durable).confirmed(),
               "staged=" + staged + " durable=" + durable
            );
         }
      }
   }

   // ---- real write failures ----

   @Test
   void aFailedWriteKeepsTheRecordDirtyAndIsRetried(@TempDir Path directory) throws IOException {
      Path blocker = directory.resolve("blocker");
      Files.writeString(blocker, "x");
      Path blocked = blocker.resolve("receipts.nbt.gz");
      Path file = directory.resolve("receipts.nbt.gz");

      OperationSubmissionReceiptStore store = new OperationSubmissionReceiptStore();
      store.record(appliedReceipt(APPLIED_ID));

      // The record is in memory only. The store must say so, and it must keep saying so
      // after the failed write. A caller that rechecks this flag cannot claim a durable
      // state that a restart would lose.
      assertTrue(store.dirty());
      assertFalse(saveOrFail(store, blocked));
      assertTrue(store.dirty());
      assertEquals(1, store.needingCleanup().size());

      // The next attempt writes. The applied result and its pending cleanup reach the disk
      // together.
      assertTrue(saveOrFail(store, file));
      assertFalse(store.dirty());

      OperationSubmissionReceiptStore reloaded = OperationSubmissionReceiptStore.load(file);
      assertTrue(reloaded.find(APPLIED_ID).orElseThrow().needsCleanup());
   }

   @Test
   void aFailedCleanupWriteKeepsTheStoreDirtyUntilItWorks(@TempDir Path directory) throws IOException {
      Path blocker = directory.resolve("blocker");
      Files.writeString(blocker, "x");
      Path blocked = blocker.resolve("receipts.nbt.gz");
      Path file = directory.resolve("receipts.nbt.gz");

      OperationSubmissionReceiptStore store = new OperationSubmissionReceiptStore();
      store.record(appliedReceipt(APPLIED_ID));
      assertTrue(saveOrFail(store, file));
      assertFalse(store.dirty());

      store.confirmCleanup(APPLIED_ID, 20L);
      assertTrue(store.dirty());
      // Memory says the cleanup is recorded. The disk still says pending.
      assertFalse(saveOrFail(store, blocked));
      assertTrue(store.dirty());

      // The retry writes the truth, and the disk then agrees with the memory.
      assertTrue(saveOrFail(store, file));
      assertFalse(store.dirty());
      assertFalse(
         OperationSubmissionReceiptStore.load(file).find(APPLIED_ID).orElseThrow().needsCleanup()
      );
   }

   @Test
   void aCleanStoreNeedsNoWrite(@TempDir Path directory) {
      OperationSubmissionReceiptStore store = new OperationSubmissionReceiptStore();
      store.record(appliedReceipt(APPLIED_ID));
      store.confirmCleanup(APPLIED_ID, 10L);
      assertTrue(saveOrFail(store, directory.resolve("receipts.nbt.gz")));

      // A store that matches the disk needs no write, so a caller does not report a
      // failure that did not happen.
      assertFalse(store.dirty());
   }

   // ---- replay over real files ----

   @Test
   void aReplayClearsTheAppliedDraftAndStopsAsking(@TempDir Path directory) throws IOException {
      Path draftFile = directory.resolve("draft.nbt.gz");
      Path receiptFile = directory.resolve("receipts.nbt.gz");
      writeDraft(draftFile, APPLIED_ID);

      OperationSubmissionReceiptStore store = new OperationSubmissionReceiptStore();
      store.record(appliedReceipt(APPLIED_ID));
      store.save(receiptFile);
      assertEquals(1, store.needingCleanup().size());

      // This is the scope-boundary replay: probe, clear, confirm, save.
      boolean confirmed = replayOnce(store, draftFile, APPLIED_ID);
      store.save(receiptFile);

      assertTrue(confirmed);
      assertFalse(Files.exists(draftFile));

      OperationSubmissionReceiptStore reloaded = OperationSubmissionReceiptStore.load(receiptFile);
      assertTrue(reloaded.needingCleanup().isEmpty());
      assertEquals(OperationSubmissionOutcome.APPLIED, reloaded.find(APPLIED_ID).orElseThrow().outcome());
   }

   @Test
   void aReplayKeepsTheDraftOfANewerSubmission(@TempDir Path directory) throws IOException {
      Path draftFile = directory.resolve("draft.nbt.gz");
      // The file belongs to a newer submission, so the older applied result must not
      // remove it. The client then owes nothing, because it owns no copy here.
      writeDraft(draftFile, NEWER_ID);

      OperationSubmissionReceiptStore store = new OperationSubmissionReceiptStore();
      store.record(appliedReceipt(APPLIED_ID));

      boolean confirmed = replayOnce(store, draftFile, APPLIED_ID);

      assertTrue(confirmed);
      assertTrue(Files.exists(draftFile));
      assertEquals(NEWER_ID, ClientOperationDraftCodec.readSubmissionId(readTag(draftFile)));
   }

   @Test
   void aReplayOfADraftFromAnOlderFormatStaysUnconfirmed(@TempDir Path directory) throws IOException {
      Path draftFile = directory.resolve("draft.nbt.gz");
      // A draft from an older client names no submission. The client cannot prove that
      // this file belongs to the applied submission, so it keeps the file and stays
      // unconfirmed.
      writeDraft(draftFile, null);

      OperationSubmissionReceiptStore store = new OperationSubmissionReceiptStore();
      store.record(appliedReceipt(APPLIED_ID));

      boolean confirmed = replayOnce(store, draftFile, APPLIED_ID);

      assertFalse(confirmed);
      assertTrue(Files.exists(draftFile));
   }

   @Test
   void aReplayOfACorruptFileStaysUnconfirmed(@TempDir Path directory) throws IOException {
      Path draftFile = directory.resolve("draft.nbt.gz");
      Files.writeString(draftFile, "this is not compressed nbt");

      OperationSubmissionReceiptStore store = new OperationSubmissionReceiptStore();
      store.record(appliedReceipt(APPLIED_ID));

      boolean confirmed = replayOnce(store, draftFile, APPLIED_ID);

      assertFalse(confirmed);
      assertTrue(Files.exists(draftFile));
   }

   @Test
   void aMissingDraftFileConfirmsTheCleanup(@TempDir Path directory) throws IOException {
      Path draftFile = directory.resolve("absent.nbt.gz");
      OperationSubmissionReceiptStore store = new OperationSubmissionReceiptStore();
      store.record(appliedReceipt(APPLIED_ID));

      // The submission never wrote a durable copy, or the copy already left. Nothing is
      // owed, and the client must not keep asking.
      assertTrue(replayOnce(store, draftFile, APPLIED_ID));
   }

   @Test
   void aFailedReceiptWriteIsNotReportedAsSaved(@TempDir Path directory) throws IOException {
      Path draftFile = directory.resolve("draft.nbt.gz");
      writeDraft(draftFile, APPLIED_ID);

      OperationSubmissionReceiptStore readOnly = OperationSubmissionReceiptStore.unreadable();
      readOnly.record(appliedReceipt(APPLIED_ID));

      // The copies leave, but the client cannot record that fact. A later start would
      // replay the cleanup again, which is safe, but the settlement must not claim a
      // durable state.
      boolean confirmed = replayOnce(readOnly, draftFile, APPLIED_ID);
      boolean saved = saveOrFail(readOnly, directory.resolve("receipts.nbt.gz"));

      assertTrue(confirmed);
      assertFalse(saved);
   }

   // ---- helpers ----

   /** Runs one replay step over a real file, the way the scope boundary does. */
   private static boolean replayOnce(
      OperationSubmissionReceiptStore store, Path draftFile, UUID transferId
   ) {
      OperationDraftSettlement.DurableProbe probe = probe(draftFile);
      OperationDraftSettlement.Ownership durable = OperationDraftSettlement.resolveDurable(
         transferId, probe, () -> deleteIfExists(draftFile)
      );
      OperationDraftSettlement.Result result = OperationDraftSettlement.combine(
         OperationDraftSettlement.Ownership.ABSENT, durable
      );
      if (result.confirmed()) {
         store.confirmCleanup(transferId, System.currentTimeMillis());
      }
      return result.confirmed();
   }

   /** Reads the draft ownership the way the manager reads it. */
   private static OperationDraftSettlement.DurableProbe probe(Path file) {
      try {
         java.util.Optional<CompoundTag> root = OperationClipboardStore.loadStrict(file);
         if (root.isEmpty()) {
            return OperationDraftSettlement.DurableProbe.absent();
         }
         return OperationDraftSettlement.DurableProbe.read(
            ClientOperationDraftCodec.readSubmissionId(root.get())
         );
      } catch (IOException | RuntimeException exception) {
         return OperationDraftSettlement.DurableProbe.unreadable();
      }
   }

   private static boolean deleteIfExists(Path file) {
      try {
         Files.deleteIfExists(file);
         return true;
      } catch (IOException exception) {
         return false;
      }
   }

   private static boolean saveOrFail(OperationSubmissionReceiptStore store, Path file) {
      try {
         store.save(file);
         return true;
      } catch (IOException | RuntimeException exception) {
         return false;
      }
   }

   private static OperationSubmissionReceipt appliedReceipt(UUID transferId) {
      return new OperationSubmissionReceipt(
         transferId, OperationSubmissionOrigin.LOCAL_ONLY, null, OVERWORLD,
         OperationSubmissionOutcome.APPLIED, 1000L
      );
   }

   /** A record with nothing left to do, so the store may reclaim it. */
   private static OperationSubmissionReceipt historyReceipt(UUID transferId) {
      return new OperationSubmissionReceipt(
         transferId, OperationSubmissionOrigin.LOCAL_ONLY, null, OVERWORLD,
         OperationSubmissionOutcome.UNKNOWN, 1000L
      );
   }

   /** Writes a real draft file with the given submission id, or none. */
   private static void writeDraft(Path file, UUID submissionId) throws IOException {
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
      OperationClipboardStore.save(file, tag);
   }

   private static CompoundTag readTag(Path file) throws IOException {
      return OperationClipboardStore.loadStrict(file).orElseThrow();
   }

   @Test
   void theDurableReaderAnswersAllThreeOwnershipQuestions(@TempDir Path directory) throws IOException {
      // A file that names a submission, a file that names none, and no file at all. These
      // are the three inputs that the settlement must tell apart.
      Path named = directory.resolve("named.nbt.gz");
      writeDraft(named, APPLIED_ID);
      assertEquals(APPLIED_ID, ClientOperationDraftCodec.readSubmissionId(readTag(named)));

      Path unnamed = directory.resolve("unnamed.nbt.gz");
      writeDraft(unnamed, null);
      assertNull(ClientOperationDraftCodec.readSubmissionId(readTag(unnamed)));

      Path absent = directory.resolve("absent.nbt.gz");
      assertEquals(
         OperationDraftSettlement.DurableProbe.State.ABSENT,
         probe(absent).state()
      );
      assertEquals(
         OperationDraftSettlement.DurableProbe.State.READ,
         probe(named).state()
      );
   }

   @Test
   void anAppliedReceiptWithPendingCleanupIsNotEvicted() {
      OperationSubmissionReceiptStore store = new OperationSubmissionReceiptStore();
      // The applied receipt is the oldest record, and it still owes a cleanup. The
      // receipts after it have nothing left to do.
      store.record(appliedReceipt(APPLIED_ID));
      for (int index = 0; index < OperationSubmissionReceiptCodec.MAX_RECEIPTS * 2; index++) {
         store.record(historyReceipt(new UUID(0xBB00L, index)));
      }

      // The cleanup is what keeps that draft out of the editor. An eviction would drop the
      // obligation and let the draft return.
      assertTrue(store.find(APPLIED_ID).orElseThrow().needsCleanup());
      assertTrue(store.all().size() <= OperationSubmissionReceiptCodec.MAX_RECEIPTS);
   }

   @Test
   void aStoreFullOfLiveObligationsRefusesAnother() {
      OperationSubmissionReceiptStore store = new OperationSubmissionReceiptStore();
      for (int index = 0; index < OperationSubmissionReceiptCodec.MAX_RECEIPTS; index++) {
         store.record(appliedReceipt(new UUID(0xCC00L, index)));
      }

      // Every record still owes a cleanup, so none may leave and the store must refuse.
      assertFalse(store.canAccept(APPLIED_ID));
      assertEquals(OperationSubmissionReceiptCodec.MAX_RECEIPTS, store.all().size());
   }

   @Test
   void aKnownAppliedResultIsNotDowngraded() {
      OperationSubmissionReceiptStore store = new OperationSubmissionReceiptStore();
      store.record(appliedReceipt(APPLIED_ID));
      OperationSubmissionReceipt applied = store.find(APPLIED_ID).orElseThrow();

      // The server ledger can restart and then answer UNKNOWN for work that the client
      // already saw applied. The weaker answer must not replace the applied record, or the
      // client would wait again and could offer the draft for a second send.
      assertFalse(store.record(applied.withOutcome(OperationSubmissionOutcome.UNKNOWN, 20L)));
      assertFalse(store.record(applied.withOutcome(OperationSubmissionOutcome.FAILED_RETRYABLE, 30L)));
      assertEquals(OperationSubmissionOutcome.APPLIED, store.find(APPLIED_ID).orElseThrow().outcome());
      assertTrue(store.find(APPLIED_ID).orElseThrow().needsCleanup());
   }

   @Test
   void anUnknownResultStillMovesToApplied() {
      OperationSubmissionReceiptStore store = new OperationSubmissionReceiptStore();
      OperationSubmissionReceipt unknown = new OperationSubmissionReceipt(
         APPLIED_ID, OperationSubmissionOrigin.LOCAL_ONLY, null, OVERWORLD,
         OperationSubmissionOutcome.UNKNOWN, 10L
      );
      store.record(unknown);

      // The stronger answer must still be accepted, whatever the client knew before.
      assertTrue(store.record(unknown.withOutcome(OperationSubmissionOutcome.APPLIED, 20L)));
      assertTrue(store.find(APPLIED_ID).orElseThrow().needsCleanup());
   }

   @Test
   void aTerminalAppliedRecordSurvivesAReload(@TempDir Path directory) throws IOException {
      Path file = directory.resolve("receipts.nbt.gz");
      OperationSubmissionReceiptStore store = new OperationSubmissionReceiptStore();
      store.record(appliedReceipt(APPLIED_ID));
      store.confirmCleanup(APPLIED_ID, 20L);
      store.save(file);

      // A later answer for this transfer meets a reloaded store, not the memory that
      // produced it. The reload must carry the applied state and the cleanup flag.
      OperationSubmissionReceiptStore reloaded = OperationSubmissionReceiptStore.load(file);
      OperationSubmissionReceipt applied = reloaded.find(APPLIED_ID).orElseThrow();
      assertFalse(applied.needsCleanup());
      assertFalse(reloaded.record(applied.withOutcome(OperationSubmissionOutcome.UNKNOWN, 30L)));
      assertEquals(OperationSubmissionOutcome.APPLIED, reloaded.find(APPLIED_ID).orElseThrow().outcome());
   }

   @Test
   void aReceiptOnAReadOnlyStoreStillHoldsItsState() {
      OperationSubmissionReceiptStore store = OperationSubmissionReceiptStore.unreadable();
      store.record(appliedReceipt(APPLIED_ID));

      // A store that cannot write still reports its state. The caller must read the write
      // result instead of trusting the memory.
      assertEquals(1, store.needingCleanup().size());
      assertTrue(store.readOnly());
   }

   @Test
   void clearingAnAppliedReceiptTwiceIsIdempotent() {
      OperationSubmissionReceiptStore store = new OperationSubmissionReceiptStore();
      store.record(appliedReceipt(APPLIED_ID));

      assertTrue(store.confirmCleanup(APPLIED_ID, 10L));
      assertFalse(store.confirmCleanup(APPLIED_ID, 20L));
      assertTrue(store.needingCleanup().isEmpty());
   }

   @Test
   void aNonAppliedReceiptNeverAsksForACleanup() {
      List<OperationSubmissionOutcome> outcomes = new ArrayList<>(List.of(
         OperationSubmissionOutcome.IN_FLIGHT,
         OperationSubmissionOutcome.IN_PROGRESS,
         OperationSubmissionOutcome.FAILED_RETRYABLE,
         OperationSubmissionOutcome.FAILED_NONRETRYABLE,
         OperationSubmissionOutcome.RECOVERY_REQUIRED,
         OperationSubmissionOutcome.UNKNOWN
      ));

      for (OperationSubmissionOutcome outcome : outcomes) {
         OperationSubmissionReceipt receipt = new OperationSubmissionReceipt(
            APPLIED_ID, OperationSubmissionOrigin.LOCAL_ONLY, null, OVERWORLD, outcome, 1L
         );
         assertFalse(receipt.needsCleanup(), outcome.name());
      }
   }

   @Test
   void aCleanupFlagOnANonAppliedOutcomeIsRejected() {
      // The flag means "the world write happened". A receipt that says otherwise would
      // make the client clear a draft of work that never applied.
      assertThrows(IllegalArgumentException.class, () -> new OperationSubmissionReceipt(
         APPLIED_ID, OperationSubmissionOrigin.LOCAL_ONLY, null, OVERWORLD,
         OperationSubmissionOutcome.UNKNOWN, 1L, true
      ));
   }

   @Test
   void aChangeToAnAppliedOutcomeStartsTheCleanup() {
      OperationSubmissionReceipt pending = new OperationSubmissionReceipt(
         APPLIED_ID, OperationSubmissionOrigin.LOCAL_ONLY, null, OVERWORLD,
         OperationSubmissionOutcome.IN_FLIGHT, 1L
      );
      assertFalse(pending.needsCleanup());

      // The first applied answer owes a cleanup, and the record says so on disk.
      OperationSubmissionReceipt applied = pending.withOutcome(OperationSubmissionOutcome.APPLIED, 2L);
      assertTrue(applied.needsCleanup());

      OperationSubmissionReceipt cleared = applied.withCleanupConfirmed(3L);
      assertFalse(cleared.needsCleanup());
      assertEquals(OperationSubmissionOutcome.APPLIED, cleared.outcome());

      // A change away from applied drops the flag, because no cleanup is owed.
      assertFalse(applied.withOutcome(OperationSubmissionOutcome.UNKNOWN, 4L).needsCleanup());
   }

   @Test
   void aFailedDeleteLeavesTheFileForALaterAttempt(@TempDir Path directory) throws IOException {
      Path draftFile = directory.resolve("draft.nbt.gz");
      writeDraft(draftFile, APPLIED_ID);

      OperationSubmissionReceiptStore store = new OperationSubmissionReceiptStore();
      store.record(appliedReceipt(APPLIED_ID));

      // The first attempt cannot remove the file. The store keeps the pending cleanup, so
      // the next boundary tries again.
      OperationDraftSettlement.Ownership first = OperationDraftSettlement.resolveDurable(
         APPLIED_ID, probe(draftFile), () -> false
      );
      assertEquals(OperationDraftSettlement.Ownership.UNCONFIRMED, first);
      assertTrue(Files.exists(draftFile));
      assertEquals(1, store.needingCleanup().size());

      // The next attempt succeeds.
      assertTrue(replayOnce(store, draftFile, APPLIED_ID));
      assertFalse(Files.exists(draftFile));
      assertTrue(store.needingCleanup().isEmpty());
   }

   @Test
   void aFailedDeleteCountsEveryAttempt() {
      AtomicInteger attempts = new AtomicInteger();
      OperationDraftSettlement.DurableProbe probe = OperationDraftSettlement.DurableProbe.read(APPLIED_ID);

      OperationDraftSettlement.Ownership first = OperationDraftSettlement.resolveDurable(
         APPLIED_ID, probe, () -> {
            attempts.incrementAndGet();
            return false;
         }
      );
      OperationDraftSettlement.Ownership second = OperationDraftSettlement.resolveDurable(
         APPLIED_ID, probe, () -> {
            attempts.incrementAndGet();
            return true;
         }
      );

      assertEquals(OperationDraftSettlement.Ownership.UNCONFIRMED, first);
      assertEquals(OperationDraftSettlement.Ownership.REMOVED, second);
      assertEquals(2, attempts.get());
   }
}
