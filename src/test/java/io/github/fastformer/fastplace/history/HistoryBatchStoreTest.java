package io.github.fastformer.fastplace.history;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HistoryBatchStoreTest {
   @TempDir Path root;

   @Test
   void publishesImmutableBatchesBeforeTheirIndex() {
      HistoryBatchStore store = store(Runnable::run, 1024);
      UUID owner = UUID.randomUUID();
      UUID first = UUID.randomUUID();
      UUID second = UUID.randomUUID();
      byte[] payload = {1, 2};

      store.publishBatch(owner, first, payload).join();
      payload[0] = 9;
      store.publishBatch(owner, second, new byte[]{3}).join();
      store.publishIndex(owner, new HistoryOrderIndex(List.of(first), List.of(second))).join();

      assertArrayEquals(new byte[]{1, 2}, store.loadBatch(owner, first).join().orElseThrow());
      assertEquals(List.of(first), store.loadIndex(owner).join().orElseThrow().undo());
      assertEquals(List.of(second), store.loadIndex(owner).join().orElseThrow().redo());
      assertThrows(CompletionException.class, () -> store.publishBatch(owner, first, new byte[]{8}).join());
      assertArrayEquals(new byte[]{1, 2}, store.loadBatch(owner, first).join().orElseThrow());
   }

   @Test
   void unpublishedReferenceCannotReplacePreviousIndex() {
      HistoryBatchStore store = store(Runnable::run, 1024);
      UUID owner = UUID.randomUUID();
      UUID published = UUID.randomUUID();
      store.publishBatch(owner, published, new byte[]{1}).join();
      HistoryOrderIndex original = new HistoryOrderIndex(List.of(published), List.of());
      store.publishIndex(owner, original).join();

      UUID missing = UUID.randomUUID();
      assertThrows(CompletionException.class,
            () -> store.publishIndex(owner, new HistoryOrderIndex(List.of(missing), List.of())).join());
      assertEquals(original, store.loadIndex(owner).join().orElseThrow());
   }

   @Test
   void loadedIndexCannotReferenceADeletedBatch() throws Exception {
      HistoryBatchStore store = store(Runnable::run, 1024);
      UUID owner = UUID.randomUUID();
      UUID batch = UUID.randomUUID();
      store.publishBatch(owner, batch, new byte[]{1}).join();
      store.publishIndex(owner, new HistoryOrderIndex(List.of(batch), List.of())).join();
      java.nio.file.Files.delete(root.resolve(owner.toString()).resolve("batches").resolve(batch + ".dat"));

      assertThrows(CompletionException.class, () -> store.loadIndex(owner).join());
   }

   @Test
   void boundedQueueRejectsPayloadUntilPublishedWriteFinishes() {
      ArrayDeque<Runnable> jobs = new ArrayDeque<>();
      HistoryBatchStore store = store(jobs::addLast, 21);
      UUID owner = UUID.randomUUID();
      var first = store.publishBatch(owner, UUID.randomUUID(), new byte[]{1});
      assertTrue(store.publishBatch(owner, UUID.randomUUID(), new byte[]{2}).isCompletedExceptionally());
      while (!jobs.isEmpty()) jobs.removeFirst().run();
      first.join();
      var retry = store.publishBatch(owner, UUID.randomUUID(), new byte[]{2});
      while (!jobs.isEmpty()) jobs.removeFirst().run();
      retry.join();
   }

   @Test
   void operationLimitAlsoBoundsQueuedReads() {
      ArrayDeque<Runnable> jobs = new ArrayDeque<>();
      HistoryBatchStore store = new HistoryBatchStore(root, jobs::addLast, 1, 32, 4096, 1024, 1, 2);
      UUID owner = UUID.randomUUID();
      UUID batch = UUID.randomUUID();
      var first = store.loadBatch(owner, batch);
      assertTrue(store.loadIndex(owner).isCompletedExceptionally());

      while (!jobs.isEmpty()) jobs.removeFirst().run();
      assertTrue(first.join().isEmpty());
      var retry = store.loadIndex(owner);
      while (!jobs.isEmpty()) jobs.removeFirst().run();
      assertTrue(retry.join().isEmpty());
   }

   @Test
   void indexRejectsDuplicateAndExcessEntries() {
      HistoryBatchStore store = store(Runnable::run, 1024);
      UUID owner = UUID.randomUUID();
      UUID batch = UUID.randomUUID();
      store.publishBatch(owner, batch, new byte[]{1}).join();
      assertThrows(CompletionException.class,
            () -> store.publishIndex(owner, new HistoryOrderIndex(List.of(batch), List.of(batch))).join());

      UUID second = UUID.randomUUID();
      UUID third = UUID.randomUUID();
      assertTrue(store.publishIndex(owner, new HistoryOrderIndex(List.of(batch, second, third), List.of()))
            .isCompletedExceptionally());
   }

   @Test
   void cancelledCallerRetainsQueueCapacityUntilWriteFinishes() {
      ArrayDeque<Runnable> jobs = new ArrayDeque<>();
      HistoryBatchStore store = store(jobs::addLast, 21);
      UUID owner = UUID.randomUUID();
      UUID batch = UUID.randomUUID();
      byte[] payload = {4};
      var write = store.publishBatch(owner, batch, payload);
      payload[0] = 9;
      write.cancel(false);
      assertTrue(store.publishBatch(owner, UUID.randomUUID(), new byte[]{8}).isCompletedExceptionally());
      while (!jobs.isEmpty()) jobs.removeFirst().run();
      var read = store.loadBatch(owner, batch);
      while (!jobs.isEmpty()) jobs.removeFirst().run();
      assertArrayEquals(new byte[]{4}, read.join().orElseThrow());
   }

   @Test
   void unpublishedIndexLeavesPreviousOrderReadableAfterReopen() {
      HistoryBatchStore store = store(Runnable::run, 1024);
      UUID owner = UUID.randomUUID();
      UUID oldBatch = UUID.randomUUID();
      UUID newBatch = UUID.randomUUID();
      store.publishBatch(owner, oldBatch, new byte[]{1}).join();
      HistoryOrderIndex oldIndex = new HistoryOrderIndex(List.of(oldBatch), List.of());
      store.publishIndex(owner, oldIndex).join();
      store.publishBatch(owner, newBatch, new byte[]{2}).join();

      // A stop between batch and index publication must leave the old order intact.
      HistoryBatchStore reopened = store(Runnable::run, 1024);
      assertEquals(oldIndex, reopened.loadIndex(owner).join().orElseThrow());
      reopened.publishIndex(owner, new HistoryOrderIndex(List.of(newBatch, oldBatch), List.of())).join();
      assertEquals(List.of(newBatch, oldBatch), store(Runnable::run, 1024).loadIndex(owner).join().orElseThrow().undo());
      reopened.publishIndex(owner, new HistoryOrderIndex(List.of(oldBatch), List.of(newBatch))).join();
      assertEquals(List.of(newBatch), store(Runnable::run, 1024).loadIndex(owner).join().orElseThrow().redo());
   }

   private HistoryBatchStore store(java.util.concurrent.Executor executor, long queueBytes) {
      return new HistoryBatchStore(root, executor, 1, 32, 4096, queueBytes, 8, 2);
   }
}
