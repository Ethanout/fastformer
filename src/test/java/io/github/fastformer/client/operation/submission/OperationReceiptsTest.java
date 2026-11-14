package io.github.fastformer.client.operation.submission;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.client.session.receipt.OperationSubmissionReceipt;
import io.github.fastformer.client.session.submission.OperationDraftSettlement;
import io.github.fastformer.client.session.submission.OperationSubmissionOrigin;
import io.github.fastformer.protocol.c2s.workspace.OperationWorkspaceReceiptQueryPayload;
import io.github.fastformer.protocol.s2c.workspace.OperationWorkspaceReceiptPayload;
import io.github.fastformer.common.workspace.submission.OperationSubmissionOutcome;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class OperationReceiptsTest {
   private static final ResourceLocation WORLD = ResourceLocation.parse("minecraft:overworld");
   private static final ResourceLocation OTHER = ResourceLocation.parse("minecraft:the_nether");

   @Test
   void queryBatchesOnlyOpenTransfersOfTheCurrentWorld() {
      var store = new Store();
      int count = OperationWorkspaceReceiptQueryPayload.MAX_TRANSFER_IDS + 3;
      for (int i = 0; i < count; i++) store.add(WORLD, OperationSubmissionOutcome.IN_FLIGHT, false);
      store.add(WORLD, OperationSubmissionOutcome.APPLIED, false);
      store.add(OTHER, OperationSubmissionOutcome.IN_FLIGHT, false);
      var sent = new ArrayList<OperationWorkspaceReceiptQueryPayload>();
      var receipts = receipts(store, sent, new ArrayList<>());
      receipts.query();
      assertEquals(2, sent.size());
      assertEquals(count, sent.stream().mapToInt(packet -> packet.transferIds().size()).sum());
      sent.forEach(packet -> {
         assertEquals(WORLD, packet.dimension());
         assertTrue(packet.transferIds().size() <= OperationWorkspaceReceiptQueryPayload.MAX_TRANSFER_IDS);
      });
      assertEquals(0, store.updates);
   }

   @Test
   void mismatchedWorldUnknownTransferAndRepeatedOutcomeDoNotSettleAnything() {
      var store = new Store();
      UUID id = store.add(WORLD, OperationSubmissionOutcome.IN_PROGRESS, false);
      UUID other = store.add(OTHER, OperationSubmissionOutcome.IN_PROGRESS, false);
      var messages = new ArrayList<String>();
      var receipts = receipts(store, new ArrayList<>(), messages);
      receipts.apply(answer(OTHER, id, OperationSubmissionOutcome.APPLIED));
      receipts.apply(answer(WORLD, UUID.randomUUID(), OperationSubmissionOutcome.APPLIED));
      receipts.apply(answer(WORLD, other, OperationSubmissionOutcome.APPLIED));
      receipts.apply(answer(WORLD, id, OperationSubmissionOutcome.IN_PROGRESS));
      assertEquals(0, store.updates);
      assertEquals(0, store.settlements);
      assertTrue(messages.isEmpty());
   }

   @Test
   void unknownServerAnswerCannotReplaceKnownAppliedOutcomeAndPendingCleanupRetries() {
      var store = new Store();
      UUID confirmed = store.add(WORLD, OperationSubmissionOutcome.APPLIED, false);
      UUID pending = store.add(WORLD, OperationSubmissionOutcome.APPLIED, true);
      var messages = new ArrayList<String>();
      var receipts = receipts(store, new ArrayList<>(), messages);
      receipts.apply(answer(WORLD, confirmed, OperationSubmissionOutcome.UNKNOWN));
      receipts.apply(answer(WORLD, pending, OperationSubmissionOutcome.UNKNOWN));
      assertEquals(0, store.updates);
      assertEquals(1, store.settlements);
      assertEquals(OperationSubmissionOutcome.APPLIED, store.values.get(confirmed).outcome());
      assertEquals(List.of("fastformer.message.operation_submit_success"), messages);
   }

   @Test
   void appliedReceiptReportsCleanupFailureAndOtherAnswersUpdateOnlyTheirOwnReceipt() {
      var store = new Store();
      store.cleanupConfirmed = false;
      UUID id = store.add(WORLD, OperationSubmissionOutcome.IN_FLIGHT, false);
      var messages = new ArrayList<String>();
      var receipts = receipts(store, new ArrayList<>(), messages);
      receipts.apply(answer(WORLD, id, OperationSubmissionOutcome.APPLIED));
      assertEquals(List.of("fastformer.message.operation_submit_cleanup_pending"), messages);
      assertEquals(1, store.settlements);
      UUID unknown = store.add(WORLD, OperationSubmissionOutcome.IN_FLIGHT, false);
      receipts.apply(answer(WORLD, unknown, OperationSubmissionOutcome.UNKNOWN));
      assertEquals(1, store.updates);
      assertEquals(123, store.values.get(unknown).recordedAtMillis());
      assertEquals("fastformer.message.operation_submit_unverified", messages.getLast());
   }

   @Test
   void failedQueryKeepsTransfersOpenAndCanRetry() {
      var store = new Store();
      UUID id = store.add(WORLD, OperationSubmissionOutcome.IN_FLIGHT, false);
      var failures = new ArrayList<RuntimeException>();
      var receipts = new OperationReceipts(() -> store, () -> WORLD,
         packet -> { throw new IllegalStateException("connection unavailable"); }, key -> fail(key), () -> 123, failures::add);
      receipts.query();
      receipts.query();
      assertEquals(2, failures.size());
      assertEquals(OperationSubmissionOutcome.IN_FLIGHT, store.values.get(id).outcome());
      assertEquals(0, store.updates);
   }

   private static OperationReceipts receipts(Store store, List<OperationWorkspaceReceiptQueryPayload> sent, List<String> messages) {
      return new OperationReceipts(() -> store, () -> WORLD, sent::add, messages::add, () -> 123,
         failure -> fail(failure));
   }

   private static OperationWorkspaceReceiptPayload answer(ResourceLocation world, UUID id, OperationSubmissionOutcome outcome) {
      return new OperationWorkspaceReceiptPayload(world, List.of(new OperationWorkspaceReceiptPayload.Entry(id, outcome)));
   }

   private static final class Store implements SubmissionReceiptAccess {
      final Map<UUID, OperationSubmissionReceipt> values = new LinkedHashMap<>();
      int updates;
      int settlements;
      boolean cleanupConfirmed = true;

      UUID add(ResourceLocation dimension, OperationSubmissionOutcome outcome, boolean pending) {
         UUID id = UUID.randomUUID();
         values.put(id, new OperationSubmissionReceipt(id, OperationSubmissionOrigin.LOCAL_ONLY,
            null, dimension, outcome, 0, pending));
         return id;
      }
      public List<OperationSubmissionReceipt> openSubmissionReceipts() { return List.copyOf(values.values()); }
      public Optional<OperationSubmissionReceipt> submissionReceipt(UUID id) { return Optional.ofNullable(values.get(id)); }
      public OperationDraftSettlement.Applied settleAppliedSubmission(UUID id) {
         settlements++;
         values.computeIfPresent(id, (key, value) -> value.withOutcome(OperationSubmissionOutcome.APPLIED, 123));
         var absent = OperationDraftSettlement.Ownership.ABSENT;
         return new OperationDraftSettlement.Applied(new OperationDraftSettlement.Result(absent, absent), cleanupConfirmed);
      }
      public boolean updateSubmissionReceipt(UUID id, OperationSubmissionOutcome outcome, long now) {
         updates++;
         values.computeIfPresent(id, (key, value) -> value.withOutcome(outcome, now));
         return true;
      }
   }
}
