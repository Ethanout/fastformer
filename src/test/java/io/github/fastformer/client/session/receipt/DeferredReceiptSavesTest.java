package io.github.fastformer.client.session.receipt;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.client.session.storage.ClientStorageQueue;
import io.github.fastformer.client.session.submission.OperationSubmissionOrigin;
import io.github.fastformer.workspace.submission.OperationSubmissionOutcome;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeferredReceiptSavesTest {
   @TempDir Path directory;

   @Test
   void fullQueueRetainsTheReceiptAndPersistsLatestStateAfterAdmission() throws Exception {
      var storage = new ClientStorageQueue();
      var release = new CountDownLatch(1);
      var store = new OperationSubmissionReceiptStore();
      var receipt = receipt();
      store.record(receipt);
      var saves = new DeferredReceiptSaves(storage, System::nanoTime, (path, failure) -> fail(failure));
      Path file = directory.resolve("receipts.dat");
      try {
         assertTrue(storage.submit(() -> { assertTrue(release.await(5, TimeUnit.SECONDS)); return true; }, (v, e) -> {}));
         assertTrue(storage.submit(() -> true, (v, e) -> {}));
         assertTrue(storage.submit(() -> true, (v, e) -> {}));
         saves.request(file, store);
         assertEquals(1, saves.pendingCount());
         assertFalse(Files.exists(file));
         var latest = receipt.withOutcome(OperationSubmissionOutcome.FAILED_RETRYABLE, 20L);
         store.record(latest);
         saves.request(file, store);
         release.countDown();
         awaitSaved(storage, saves);
         assertFalse(store.dirty());
         assertEquals(latest, OperationSubmissionReceiptStore.load(file).find(receipt.transferId()).orElseThrow());
      } finally { release.countDown(); }
   }

   @Test
   void filesystemFailureRetainsTheObligationAndRetriesAfterBackoff() throws Exception {
      var storage = new ClientStorageQueue();
      var clock = new AtomicLong();
      var failures = new AtomicInteger();
      var store = new OperationSubmissionReceiptStore();
      var receipt = receipt();
      store.record(receipt);
      var saves = new DeferredReceiptSaves(storage, clock::get, (path, failure) -> failures.incrementAndGet());
      Path blocked = directory.resolve("blocked");
      Files.writeString(blocked, "not a directory");
      Path file = blocked.resolve("receipts.dat");
      saves.request(file, store);
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (failures.get() == 0 && System.nanoTime() < deadline) { storage.drain(); saves.tick(); Thread.yield(); }
      assertEquals(1, failures.get());
      assertTrue(store.dirty());
      assertEquals(1, saves.pendingCount());
      Files.delete(blocked);
      Files.createDirectory(blocked);
      clock.set(TimeUnit.SECONDS.toNanos(1));
      awaitSaved(storage, saves);
      assertEquals(receipt, OperationSubmissionReceiptStore.load(file).find(receipt.transferId()).orElseThrow());
   }

   private static void awaitSaved(ClientStorageQueue storage, DeferredReceiptSaves saves) {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (saves.pendingCount() > 0 && System.nanoTime() < deadline) { storage.drain(); saves.tick(); Thread.yield(); }
      assertEquals(0, saves.pendingCount());
   }

   private static OperationSubmissionReceipt receipt() {
      return new OperationSubmissionReceipt(UUID.randomUUID(), OperationSubmissionOrigin.LOCAL_ONLY, null,
         ResourceLocation.withDefaultNamespace("overworld"), OperationSubmissionOutcome.IN_FLIGHT, 10L);
   }
}
