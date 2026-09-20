package io.github.fastformer.client.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fastformer.client.operation.clipboard.OperationClipboardStore;
import io.github.fastformer.network.payload.operation.OperationSubmissionOutcome;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests the durable shape and the eviction rules of the submission receipts. */
class OperationSubmissionReceiptTest {
   private static final ResourceLocation OVERWORLD =
      ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");
   private static final ResourceLocation NETHER =
      ResourceLocation.fromNamespaceAndPath("minecraft", "the_nether");

   private static UUID transfer(int seed) {
      return new UUID(0x1234L, seed);
   }

   private static OperationSubmissionReceipt receipt(int seed, OperationSubmissionOutcome outcome) {
      return new OperationSubmissionReceipt(
         transfer(seed), OperationSubmissionOrigin.SERVER_SELECTION, null, OVERWORLD, outcome, 1000L + seed
      );
   }

   /** Builds a server selection identity that a receipt can carry. */
   private static OperationDraftIdentity identity(int seed) {
      return new OperationDraftIdentity(
         io.github.fastformer.fastplace.selection.OperationSelectionMode.CUBOID,
         List.of(new net.minecraft.core.BlockPos(seed, 0, 0)),
         0,
         null,
         null,
         0.0D,
         new net.minecraft.core.BlockPos(0, 0, 0),
         new net.minecraft.core.BlockPos(seed, 1, 1)
      );
   }

   @Test
   void aLocalOnlyReceiptRejectsAnIdentity() {
      // A local-only submission has no server selection, so an identity on it would be a
      // contradiction that a later restore could act on.
      assertThrows(IllegalArgumentException.class, () -> new OperationSubmissionReceipt(
         transfer(1), OperationSubmissionOrigin.LOCAL_ONLY, identity(1), OVERWORLD,
         OperationSubmissionOutcome.IN_FLIGHT, 1000L
      ));
   }

   @Test
   void aReceiptNeedsItsCoreFields() {
      assertThrows(NullPointerException.class, () -> new OperationSubmissionReceipt(
         null, OperationSubmissionOrigin.LOCAL_ONLY, null, OVERWORLD,
         OperationSubmissionOutcome.IN_FLIGHT, 1000L
      ));
      assertThrows(NullPointerException.class, () -> new OperationSubmissionReceipt(
         transfer(1), OperationSubmissionOrigin.LOCAL_ONLY, null, null,
         OperationSubmissionOutcome.IN_FLIGHT, 1000L
      ));
      assertThrows(NullPointerException.class, () -> new OperationSubmissionReceipt(
         transfer(1), OperationSubmissionOrigin.LOCAL_ONLY, null, OVERWORLD, null, 1000L
      ));
   }

   @Test
   void aLocalOnlyReceiptNeedsNoServerIdentity() {
      OperationSubmissionReceipt local = new OperationSubmissionReceipt(
         transfer(1), OperationSubmissionOrigin.LOCAL_ONLY, null, OVERWORLD,
         OperationSubmissionOutcome.IN_FLIGHT, 1000L
      );
      assertFalse(local.requiresServerIdentity());
      assertFalse(local.confirmsIdentity());
   }

   @Test
   void codecRoundTripsAReceiptWithoutAnIdentity() throws IOException {
      List<OperationSubmissionReceipt> source = List.of(
         receipt(1, OperationSubmissionOutcome.IN_FLIGHT),
         receipt(2, OperationSubmissionOutcome.IN_PROGRESS),
         receipt(3, OperationSubmissionOutcome.APPLIED),
         receipt(4, OperationSubmissionOutcome.FAILED_RETRYABLE),
         receipt(5, OperationSubmissionOutcome.FAILED_NONRETRYABLE),
         receipt(6, OperationSubmissionOutcome.RECOVERY_REQUIRED),
         receipt(7, OperationSubmissionOutcome.UNKNOWN)
      );

      List<OperationSubmissionReceipt> restored =
         OperationSubmissionReceiptCodec.decode(OperationSubmissionReceiptCodec.encode(source));

      assertEquals(source, restored);
   }

   @Test
   void codecRoundTripsADifferentDimension() throws IOException {
      OperationSubmissionReceipt nether = new OperationSubmissionReceipt(
         transfer(9), OperationSubmissionOrigin.SERVER_SELECTION, null, NETHER,
         OperationSubmissionOutcome.IN_PROGRESS, 42L
      );

      List<OperationSubmissionReceipt> restored =
         OperationSubmissionReceiptCodec.decode(OperationSubmissionReceiptCodec.encode(List.of(nether)));

      assertEquals(NETHER, restored.get(0).dimension());
   }

   @Test
   void codecKeepsAnIdentityWhenOneExists() throws IOException {
      OperationDraftIdentity identity = identity(9);
      OperationSubmissionReceipt confirmed = new OperationSubmissionReceipt(
         transfer(9), OperationSubmissionOrigin.SERVER_SELECTION, identity, OVERWORLD,
         OperationSubmissionOutcome.IN_FLIGHT, 42L
      );

      List<OperationSubmissionReceipt> restored =
         OperationSubmissionReceiptCodec.decode(OperationSubmissionReceiptCodec.encode(List.of(confirmed)));

      assertTrue(restored.get(0).confirmsIdentity());
      assertEquals(identity, restored.get(0).identity());
   }

   @Test
   void anAppliedReceiptKeepsAPendingCleanupOnDisk() {
      OperationSubmissionReceipt applied = new OperationSubmissionReceipt(
         transfer(1), OperationSubmissionOrigin.LOCAL_ONLY, null, OVERWORLD,
         OperationSubmissionOutcome.APPLIED, 10L
      );
      assertTrue(applied.needsCleanup());

      // The pending cleanup survives a restart, because a later start must finish it
      // without any server answer.
      List<OperationSubmissionReceipt> reloaded = decodeIgnoringChecked(
         OperationSubmissionReceiptCodec.encode(List.of(applied))
      );
      assertTrue(reloaded.get(0).needsCleanup());
      assertEquals(OperationSubmissionOutcome.APPLIED, reloaded.get(0).outcome());
   }

   @Test
   void aConfirmedCleanupSurvivesARestart() {
      OperationSubmissionReceipt applied = new OperationSubmissionReceipt(
         transfer(1), OperationSubmissionOrigin.LOCAL_ONLY, null, OVERWORLD,
         OperationSubmissionOutcome.APPLIED, 10L
      ).withCleanupConfirmed(20L);

      List<OperationSubmissionReceipt> reloaded = decodeIgnoringChecked(
         OperationSubmissionReceiptCodec.encode(List.of(applied))
      );
      assertFalse(reloaded.get(0).needsCleanup());
   }

   @Test
   void anOlderFileTreatsAnAppliedReceiptAsPending() {
      // An older writer deleted the draft copy on a best-effort path and recorded no flag.
      // The applied record must therefore start as pending, so the copy is cleared once.
      CompoundTag root = OperationSubmissionReceiptCodec.encode(
         List.of(receipt(3, OperationSubmissionOutcome.APPLIED))
      );
      CompoundTag entry = root.getList("Receipts", net.minecraft.nbt.Tag.TAG_COMPOUND).getCompound(0);
      entry.remove("CleanupPending");
      assertFalse(entry.contains("CleanupPending"));

      List<OperationSubmissionReceipt> reloaded = decodeIgnoringChecked(root);

      assertFalse(reloaded.isEmpty());
      assertTrue(reloaded.get(0).needsCleanup());
   }

   @Test
   void anOlderFileKeepsOtherOutcomesClean() {
      // An unknown result owes no cleanup. The migration must not invent one, because the
      // draft of an unknown submission must stay for the player.
      CompoundTag root = OperationSubmissionReceiptCodec.encode(
         List.of(receipt(4, OperationSubmissionOutcome.UNKNOWN))
      );
      root.getList("Receipts", net.minecraft.nbt.Tag.TAG_COMPOUND).getCompound(0).remove("CleanupPending");

      List<OperationSubmissionReceipt> reloaded = decodeIgnoringChecked(root);

      assertFalse(reloaded.get(0).needsCleanup());
   }

   private static List<OperationSubmissionReceipt> decodeIgnoringChecked(CompoundTag root) {
      try {
         return OperationSubmissionReceiptCodec.decode(root);
      } catch (IOException exception) {
         throw new AssertionError("The receipt file must decode", exception);
      }
   }

   @Test
   void decodeRefusesAnotherVersion() {
      CompoundTag root = new CompoundTag();
      root.putInt("Version", OperationSubmissionReceiptCodec.CURRENT_VERSION + 1);

      // A version mismatch is not corruption. The caller must keep the file, so the
      // exception type must be distinguishable.
      assertThrows(
         OperationSubmissionReceiptCodec.VersionMismatchException.class,
         () -> OperationSubmissionReceiptCodec.decode(root)
      );
   }

   @Test
   void decodeRefusesAReceiptWithoutAVersion() {
      assertThrows(IOException.class, () -> OperationSubmissionReceiptCodec.decode(new CompoundTag()));
      assertThrows(IOException.class, () -> OperationSubmissionReceiptCodec.decode(null));
   }

   @Test
   void aReadOnlyStoreRefusesToWrite(@TempDir Path directory) {
      OperationSubmissionReceiptStore store = OperationSubmissionReceiptStore.unreadable();
      store.record(receipt(1, OperationSubmissionOutcome.IN_FLIGHT));

      assertTrue(store.readOnly());
      // The file may belong to a later client version. A write would destroy it.
      assertThrows(IOException.class, () -> store.save(directory.resolve("receipts.nbt")));
   }

   @Test
   void aStoreSurvivesASaveAndLoad(@TempDir Path directory) throws IOException {
      Path file = directory.resolve("receipts.nbt");
      OperationSubmissionReceiptStore store = new OperationSubmissionReceiptStore();
      store.record(receipt(1, OperationSubmissionOutcome.IN_PROGRESS));
      store.record(receipt(2, OperationSubmissionOutcome.APPLIED));
      store.save(file);

      OperationSubmissionReceiptStore restored = OperationSubmissionReceiptStore.load(file);

      assertEquals(2, restored.all().size());
      assertEquals(
         OperationSubmissionOutcome.IN_PROGRESS, restored.find(transfer(1)).orElseThrow().outcome()
      );
   }

   @Test
   void aMissingFileYieldsAnEmptyStore(@TempDir Path directory) throws IOException {
      OperationSubmissionReceiptStore restored =
         OperationSubmissionReceiptStore.load(directory.resolve("absent.nbt"));

      assertTrue(restored.isEmpty());
      assertFalse(restored.readOnly());
   }

   @Test
   void aStoreRefusesToDropAnOpenReceipt() {
      OperationSubmissionReceiptStore store = new OperationSubmissionReceiptStore();
      // One open receipt first, then more finished receipts than the limit allows.
      assertEquals(true, store.record(receipt(0, OperationSubmissionOutcome.IN_FLIGHT)));
      for (int index = 0; index <= OperationSubmissionReceiptCodec.MAX_RECEIPTS; index++) {
         store.record(receipt(index + 1, OperationSubmissionOutcome.APPLIED));
      }

      // The open receipt is the only record of a submission that the server may still
      // apply. An eviction would leave the client with no way to learn the result.
      assertNotNull(store.find(transfer(0)).orElse(null));
      assertTrue(store.all().size() <= OperationSubmissionReceiptCodec.MAX_RECEIPTS);
   }

   @Test
   void aStoreNeverPassesItsLimit() {
      OperationSubmissionReceiptStore store = new OperationSubmissionReceiptStore();
      // More finished receipts than the limit, with no open receipt at all.
      for (int index = 0; index < OperationSubmissionReceiptCodec.MAX_RECEIPTS * 3; index++) {
         store.record(receipt(index, OperationSubmissionOutcome.APPLIED));
      }

      // A file that passes the limit cannot be read again, so the store must hold the
      // line even when every receipt is finished.
      assertEquals(OperationSubmissionReceiptCodec.MAX_RECEIPTS, store.all().size());
   }

   @Test
   void aFullStoreOfPendingReceiptsRefusesAnother() {
      OperationSubmissionReceiptStore store = new OperationSubmissionReceiptStore();
      for (int index = 0; index < OperationSubmissionReceiptCodec.MAX_RECEIPTS; index++) {
         assertEquals(true, store.record(receipt(index, OperationSubmissionOutcome.IN_PROGRESS)));
      }

      // A disconnect can leave a pending receipt behind, and a later login can add
      // another one. When the store is full of pending results, a new submission must be
      // refused with feedback instead of dropping a pending result.
      assertFalse(store.canAccept(transfer(999)));
      assertFalse(store.record(receipt(999, OperationSubmissionOutcome.IN_FLIGHT)));
      assertEquals(OperationSubmissionReceiptCodec.MAX_RECEIPTS, store.all().size());
      assertFalse(store.find(transfer(999)).isPresent());
   }

   @Test
   void aPendingReceiptAccumulatesAcrossReconnects() {
      OperationSubmissionReceiptStore store = new OperationSubmissionReceiptStore();
      // Each reconnect leaves one UNKNOWN receipt behind. UNKNOWN is not open, so it may
      // leave when the store needs room. A pending receipt must not.
      for (int index = 0; index < OperationSubmissionReceiptCodec.MAX_RECEIPTS; index++) {
         store.record(receipt(index, OperationSubmissionOutcome.UNKNOWN));
      }
      store.record(receipt(500, OperationSubmissionOutcome.IN_FLIGHT));

      assertNotNull(store.find(transfer(500)).orElse(null));
      assertTrue(store.all().size() <= OperationSubmissionReceiptCodec.MAX_RECEIPTS);
   }

   @Test
   void aFullStoreStillAcceptsAnUpdate() {
      OperationSubmissionReceiptStore store = new OperationSubmissionReceiptStore();
      for (int index = 0; index < OperationSubmissionReceiptCodec.MAX_RECEIPTS; index++) {
         store.record(receipt(index, OperationSubmissionOutcome.IN_PROGRESS));
      }

      // An answer to a recorded submission must always fit. The result is what the
      // client waits for, so a full store must not block it.
      assertTrue(store.canAccept(transfer(0)));
      assertTrue(store.record(receipt(0, OperationSubmissionOutcome.APPLIED)));
   }

   @Test
   void aFileAboveTheLimitStaysReadable(@TempDir Path directory) throws IOException {
      Path file = directory.resolve("receipts.nbt");
      // A larger file can come from an older client or from a damaged write. It must not
      // become unreadable, because every result in it would be lost. The write path
      // refuses this, so the file is built directly.
      OperationClipboardStore.save(file, oversizedTag(OperationSubmissionOutcome.APPLIED));

      OperationSubmissionReceiptStore restored = OperationSubmissionReceiptStore.load(file);

      assertEquals(OperationSubmissionReceiptCodec.MAX_RECEIPTS, restored.all().size());
      assertFalse(restored.readOnly());
   }

   @Test
   void aFileWithOnlyPendingReceiptsAboveTheLimitIsKept(@TempDir Path directory) throws IOException {
      Path file = directory.resolve("receipts.nbt");
      OperationClipboardStore.save(file, oversizedTag(OperationSubmissionOutcome.IN_PROGRESS));

      // No receipt may leave, so the store can only report the problem. A drop would lose
      // the only record of work that the server may still apply.
      assertThrows(IOException.class, () -> OperationSubmissionReceiptStore.load(file));
   }

   /** Builds a receipt file that holds more receipts than the read limit allows. */
   private static CompoundTag oversizedTag(OperationSubmissionOutcome outcome) {
      CompoundTag root = new CompoundTag();
      root.putInt("Version", OperationSubmissionReceiptCodec.CURRENT_VERSION);
      net.minecraft.nbt.ListTag list = new net.minecraft.nbt.ListTag();
      int count = OperationSubmissionReceiptCodec.MAX_RECEIPTS + 10;
      for (int index = 0; index < count; index++) {
         OperationSubmissionReceipt source = receipt(index, outcome);
         CompoundTag tag = new CompoundTag();
         tag.putUUID("TransferId", source.transferId());
         tag.putString("Origin", source.origin().name());
         tag.putString("Outcome", source.outcome().name());
         tag.putString("Dimension", source.dimension().toString());
         tag.putLong("RecordedAt", source.recordedAtMillis());
         if (outcome.applied()) {
            // A current-version writer always writes this flag for an applied receipt, both
            // when it is true and when it is false. An absent flag means a file from an
            // older client, and the reader must then treat the receipt as pending cleanup.
            tag.putBoolean("CleanupPending", false);
         }
         list.add(tag);
      }
      root.put("Receipts", list);
      return root;
   }

   @Test
   void aWriteAboveTheLimitIsRefused() {
      List<OperationSubmissionReceipt> tooMany = new ArrayList<>();
      for (int index = 0; index <= OperationSubmissionReceiptCodec.MAX_RECEIPTS; index++) {
         tooMany.add(receipt(index, OperationSubmissionOutcome.IN_PROGRESS));
      }

      // The store holds its own limit, so the codec never sees too many receipts from
      // the store. The codec still refuses a caller that passes too many.
      assertThrows(IllegalArgumentException.class, () -> OperationSubmissionReceiptCodec.encode(tooMany));
   }

   @Test
   void openListsOnlyWaitingReceipts() {
      OperationSubmissionReceiptStore store = new OperationSubmissionReceiptStore();
      store.record(receipt(1, OperationSubmissionOutcome.IN_FLIGHT));
      store.record(receipt(2, OperationSubmissionOutcome.IN_PROGRESS));
      store.record(receipt(3, OperationSubmissionOutcome.APPLIED));
      store.record(receipt(4, OperationSubmissionOutcome.UNKNOWN));

      List<UUID> open = store.open().stream().map(OperationSubmissionReceipt::transferId).toList();

      assertEquals(List.of(transfer(1), transfer(2)), open);
   }

   @Test
   void anEmptyStoreDeletesItsFile(@TempDir Path directory) throws IOException {
      Path file = directory.resolve("receipts.nbt");
      OperationSubmissionReceiptStore store = new OperationSubmissionReceiptStore();
      store.record(receipt(1, OperationSubmissionOutcome.APPLIED));
      store.save(file);
      store.remove(transfer(1));
      store.save(file);

      assertFalse(java.nio.file.Files.exists(file));
   }

   @Test
   void outcomeSemanticsDoNotCollapse() {
      // The player may send a retryable failure again. A non-retryable failure and a
      // recovery handoff are different states, because the world may hold part of the
      // work. A report that flattens them would let the client repeat applied work.
      assertTrue(OperationSubmissionOutcome.FAILED_RETRYABLE.retryable());
      assertFalse(OperationSubmissionOutcome.FAILED_NONRETRYABLE.retryable());
      assertFalse(OperationSubmissionOutcome.RECOVERY_REQUIRED.retryable());
      assertTrue(OperationSubmissionOutcome.FAILED_NONRETRYABLE.terminalFailure());
      assertTrue(OperationSubmissionOutcome.RECOVERY_REQUIRED.terminalFailure());
      assertFalse(OperationSubmissionOutcome.IN_PROGRESS.terminalFailure());
      assertTrue(OperationSubmissionOutcome.IN_PROGRESS.open());
      assertTrue(OperationSubmissionOutcome.IN_FLIGHT.open());
      assertFalse(OperationSubmissionOutcome.APPLIED.open());
      assertFalse(OperationSubmissionOutcome.UNKNOWN.open());
      assertFalse(OperationSubmissionOutcome.IN_FLIGHT.reportable());
      assertTrue(OperationSubmissionOutcome.UNKNOWN.reportable());
   }

   @Test
   void aChangeOfOutcomeKeepsTheIdentity() {
      OperationDraftIdentity identity = identity(1);
      OperationSubmissionReceipt original = new OperationSubmissionReceipt(
         transfer(1), OperationSubmissionOrigin.SERVER_SELECTION, identity, OVERWORLD,
         OperationSubmissionOutcome.IN_FLIGHT, 10L
      );

      OperationSubmissionReceipt answered = original.withOutcome(OperationSubmissionOutcome.UNKNOWN, 20L);

      assertEquals(original.identity(), answered.identity());
      assertEquals(original.dimension(), answered.dimension());
      assertEquals(OperationSubmissionOutcome.UNKNOWN, answered.outcome());
      assertEquals(20L, answered.recordedAtMillis());
   }
}
