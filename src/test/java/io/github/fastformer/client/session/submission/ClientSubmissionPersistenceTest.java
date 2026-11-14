package io.github.fastformer.client.session.submission;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.client.operation.clipboard.OperationClipboardStore;
import io.github.fastformer.client.session.receipt.OperationSubmissionReceiptStore;
import io.github.fastformer.client.session.scope.ClientScope;
import io.github.fastformer.client.session.scope.ClientScopeId;
import io.github.fastformer.client.session.storage.ClientStorageQueue;
import io.github.fastformer.common.selection.OperationSelectionMode;
import io.github.fastformer.workspace.submission.OperationSubmissionOutcome;
import io.github.fastformer.workspace.submission.OperationWorkspacePlan;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClientSubmissionPersistenceTest {
   @TempDir Path directory;

   @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); }

   @Test
   void switchingScopesWhileEncodingKeepsTheReceiptWithItsOriginalOwner() throws Exception {
      var started = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var sent = new AtomicInteger();
      try (var worker = new Worker()) {
         var queue = new ClientStorageQueue(worker);
         var first = scope("first");
         var second = scope("second");
         var current = new AtomicReference<>(first);
         var failures = new ArrayList<String>();
         var persistence = persistence(queue, current, (plan, id) -> {
            started.countDown();
            try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new java.io.IOException(failure); }
            return sent::incrementAndGet;
         }, failures);
         UUID transfer = UUID.randomUUID();
         try {
            assertTrue(persistence.prepareSubmission(null, OperationSubmissionOrigin.CLIENT_SELECTION, transfer,
               new OperationWorkspacePlan(List.of()), () -> true, send -> { fail("A departed scope must not send"); }));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            current.set(second);
         } finally { release.countDown(); }
         drain(queue, persistence);

         assertEquals(0, sent.get());
         assertEquals(OperationSubmissionOutcome.FAILED_RETRYABLE,
            OperationSubmissionReceiptStore.load(first.receipts().file()).find(transfer).orElseThrow().outcome());
         assertTrue(OperationClipboardStore.loadStrict(first.drafts().file()).isPresent());
         assertTrue(Files.notExists(second.receipts().file()));
         assertTrue(Files.notExists(second.drafts().file()));
         assertTrue(failures.isEmpty());
      }
   }

   @Test
   void executorRejectionAfterPreparationRetainsAndRetriesTheUnsentReceipt() throws Exception {
      try (var worker = new Worker()) {
         worker.reject = true;
         var queue = new ClientStorageQueue(worker);
         var owner = scope("rejected");
         var persistence = persistence(queue, new AtomicReference<>(owner), (plan, id) -> () -> fail("No send"), new ArrayList<>());
         UUID transfer = UUID.randomUUID();
         assertFalse(persistence.prepareSubmission(null, OperationSubmissionOrigin.CLIENT_SELECTION, transfer,
            new OperationWorkspacePlan(List.of()), () -> true, send -> fail("Rejected work cannot complete")));
         assertEquals(OperationSubmissionOutcome.FAILED_RETRYABLE, persistence.store(owner).find(transfer).orElseThrow().outcome());
         assertFalse(persistence.idle());
         assertTrue(queue.idle());

         worker.reject = false;
         drain(queue, persistence);
         assertEquals(OperationSubmissionOutcome.FAILED_RETRYABLE,
            OperationSubmissionReceiptStore.load(owner.receipts().file()).find(transfer).orElseThrow().outcome());
         assertFalse(persistence.store(owner).dirty());
      }
   }

   @Test
   void secondFileFailureNeverPublishesASendAndPersistsRetryableOutcome() throws Exception {
      try (var worker = new Worker()) {
         var queue = new ClientStorageQueue(worker);
         var owner = scope("failed-draft");
         var blocker = directory.resolve("blocker");
         Files.writeString(blocker, "not a directory");
         owner.drafts().useFile(blocker.resolve("draft.dat"));
         var failures = new ArrayList<String>();
         var persistence = persistence(queue, new AtomicReference<>(owner), (plan, id) -> () -> fail("No send"), failures);
         var completions = new AtomicInteger();
         UUID transfer = UUID.randomUUID();
         assertTrue(persistence.prepareSubmission(null, OperationSubmissionOrigin.CLIENT_SELECTION, transfer,
            new OperationWorkspacePlan(List.of()), () -> true, send -> { assertNull(send); completions.incrementAndGet(); }));
         drain(queue, persistence);
         assertEquals(1, completions.get());
         assertEquals(List.of("fastformer.message.operation_draft_save_failed"), failures);
         assertEquals(OperationSubmissionOutcome.FAILED_RETRYABLE,
            OperationSubmissionReceiptStore.load(owner.receipts().file()).find(transfer).orElseThrow().outcome());
      }
   }

   private ClientScope scope(String name) {
      var owner = new ClientScope(new ClientScopeId(name, "minecraft:overworld", UUID.randomUUID()));
      owner.drafts().useFile(directory.resolve(name + "-draft.dat"));
      owner.receipts().useFile(directory.resolve(name + "-receipts.dat"));
      owner.selectionSession().restoreDraftState(new io.github.fastformer.client.operation.selection.ClientSelectionSession.DraftState(
         OperationSelectionMode.CUBOID, List.of(BlockPos.ZERO), 0, BlockPos.ZERO, BlockPos.ZERO));
      return owner;
   }

   private static ClientSubmissionPersistence persistence(ClientStorageQueue queue, AtomicReference<ClientScope> current,
      ClientSubmissionPersistence.Encoder encoder, List<String> failures) {
      return new ClientSubmissionPersistence(queue, current::get, encoder, () -> {},
         (key, error) -> failures.add(key), System::currentTimeMillis, System::nanoTime);
   }

   private static void drain(ClientStorageQueue queue, ClientSubmissionPersistence persistence) {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while ((!queue.idle() || !persistence.idle()) && System.nanoTime() < deadline) {
         queue.drain(); persistence.tick(); Thread.yield();
      }
      assertTrue(queue.idle());
      assertTrue(persistence.idle());
   }

   private static final class Worker extends ThreadPoolExecutor {
      volatile boolean reject;
      Worker() { super(1, 1, 1, TimeUnit.SECONDS, new LinkedBlockingQueue<>()); }
      @Override public void execute(Runnable task) {
         if (reject) throw new RejectedExecutionException("Injected storage admission failure");
         super.execute(task);
      }
   }
}
