package io.github.fastformer.fastplace.history;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HistoryBatchStoreTest {
   @TempDir Path root;

   @Test
   void cleanupExpiredBatchesUpdatesIndexBeforeDeletingOldHistory() throws Exception {
      HistoryBatchStore store = new HistoryBatchStore(root, Runnable::run, 1, 32, 4096, 4096, 8, 8);
      UUID owner = UUID.randomUUID();
      UUID expiredUndo = UUID.randomUUID();
      UUID currentUndo = UUID.randomUUID();
      UUID expiredRedo = UUID.randomUUID();
      UUID pendingPublication = UUID.randomUUID();
      store.publishBatch(owner, expiredUndo, new byte[]{1}).join();
      store.publishBatch(owner, currentUndo, new byte[]{2}).join();
      store.publishBatch(owner, expiredRedo, new byte[]{3}).join();
      store.publishBatch(owner, pendingPublication, new byte[]{4}).join();
      store.publishIndex(owner, new HistoryOrderIndex(
         List.of(currentUndo, expiredUndo), List.of(expiredRedo)
      )).join();
      Instant cutoff = Instant.now().minusSeconds(5);
      Path batches = root.resolve(owner.toString()).resolve("batches");
      for (UUID expired : List.of(expiredUndo, expiredRedo, pendingPublication)) {
         java.nio.file.Files.setLastModifiedTime(
            batches.resolve(expired + ".dat"), FileTime.from(cutoff.minusSeconds(10))
         );
      }
      assertEquals(2, store.cleanupExpiredBatches(owner, cutoff).join());

      assertEquals(new HistoryOrderIndex(List.of(currentUndo), List.of()), store.loadIndex(owner).join().orElseThrow());
      assertTrue(store.loadBatch(owner, expiredUndo).join().isEmpty());
      assertTrue(store.loadBatch(owner, expiredRedo).join().isEmpty());
      assertTrue(store.loadBatch(owner, currentUndo).join().isPresent());
      assertTrue(store.loadBatch(owner, pendingPublication).join().isPresent());
   }

   @Test
   void cleanupExpiredBatchesRequiresAValidIndexAndPreservesData() throws Exception {
      HistoryBatchStore store = store(Runnable::run, 1024);
      UUID owner = UUID.randomUUID();
      UUID batch = UUID.randomUUID();
      store.publishBatch(owner, batch, new byte[]{1}).join();
      assertThrows(CompletionException.class, () -> store.cleanupExpiredBatches(owner, Instant.now()).join());
      assertTrue(store.loadBatch(owner, batch).join().isPresent());

      java.nio.file.Files.write(root.resolve(owner.toString()).resolve("index.dat"), new byte[]{1, 2, 3});
      assertThrows(CompletionException.class, () -> store.cleanupExpiredBatches(owner, Instant.now()).join());
      assertTrue(store.loadBatch(owner, batch).join().isPresent());
   }

   @Test
   void cleanupExpiredAllContinuesWhenOneOwnerIndexIsDamaged() throws Exception {
      HistoryBatchStore store = store(Runnable::run, 4096);
      UUID valid = UUID.randomUUID();
      UUID damaged = UUID.randomUUID();
      UUID validBatch = UUID.randomUUID();
      UUID damagedBatch = UUID.randomUUID();
      store.publishBatch(valid, validBatch, new byte[]{1}).join();
      store.publishIndex(valid, new HistoryOrderIndex(List.of(validBatch), List.of())).join();
      store.publishBatch(damaged, damagedBatch, new byte[]{2}).join();
      store.publishIndex(damaged, new HistoryOrderIndex(List.of(damagedBatch), List.of())).join();
      java.nio.file.Files.setLastModifiedTime(root.resolve(valid.toString()).resolve("batches").resolve(validBatch + ".dat"),
         FileTime.from(Instant.now().minusSeconds(60)));
      java.nio.file.Files.write(root.resolve(damaged.toString()).resolve("index.dat"), new byte[]{1, 2, 3});
      HistoryBatchStore.CleanupSummary summary = store.cleanupExpiredAll(Instant.now()).join();
      assertEquals(2, summary.owners());
      assertEquals(1, summary.deletedBatches());
      assertEquals(1, summary.failedOwners());
      assertTrue(store.loadBatch(valid, validBatch).join().isEmpty());
      assertTrue(store.loadBatch(damaged, damagedBatch).join().isPresent());
   }

   @Test
   void cleanupExpiredAllIgnoresNonCanonicalUuidDirectories() throws Exception {
      HistoryBatchStore store = store(Runnable::run, 1024);
      java.nio.file.Files.createDirectories(root.resolve("00000000-0000-0000-0000-00000000000A"));

      HistoryBatchStore.CleanupSummary summary = store.cleanupExpiredAll(Instant.now()).join();

      assertEquals(new HistoryBatchStore.CleanupSummary(0, 0, 0), summary);
   }

   @Test
   void cleanupExpiredAllPreservesActiveOwnerHistory() throws Exception {
      HistoryBatchStore store = store(Runnable::run, 1024);
      UUID owner = UUID.randomUUID();
      UUID batch = UUID.randomUUID();
      store.publishBatch(owner, batch, new byte[]{1}).join();
      store.publishIndex(owner, new HistoryOrderIndex(List.of(batch), List.of())).join();
      java.nio.file.Files.setLastModifiedTime(
         root.resolve(owner.toString()).resolve("batches").resolve(batch + ".dat"),
         FileTime.from(Instant.now().minusSeconds(60))
      );

      HistoryBatchStore.CleanupSummary summary = store.cleanupExpiredAll(Instant.now(), Set.of(owner)).join();

      assertEquals(new HistoryBatchStore.CleanupSummary(0, 0, 0), summary);
      assertTrue(store.loadBatch(owner, batch).join().isPresent());
      assertEquals(new HistoryOrderIndex(List.of(batch), List.of()), store.loadIndex(owner).join().orElseThrow());
   }

   @Test
   void cleanupSummaryCombinesBothCleanupPhases() {
      var retired = new HistoryBatchStore.CleanupSummary(2, 3, 1);
      var expired = new HistoryBatchStore.CleanupSummary(4, 5, 2);

      assertEquals(new HistoryBatchStore.CleanupSummary(6, 8, 3), retired.plus(expired));
   }

   @Test
   void startupScanCleansOfflineOwnersAndPreservesDamagedOwner() throws Exception {
      HistoryBatchStore store = store(Runnable::run, 1024);
      UUID validOwner = UUID.randomUUID();
      UUID damagedOwner = UUID.randomUUID();
      UUID validBatch = UUID.randomUUID();
      UUID damagedBatch = UUID.randomUUID();
      for (var entry : java.util.Map.of(validOwner, validBatch, damagedOwner, damagedBatch).entrySet()) {
         store.publishBatch(entry.getKey(), entry.getValue(), new byte[]{1}).join();
         store.publishIndex(entry.getKey(), new HistoryOrderIndex(List.of(), List.of())).join();
         store.stageRetirements(entry.getKey(), java.util.Set.of(entry.getValue())).join();
      }
      java.nio.file.Files.write(root.resolve(damagedOwner.toString()).resolve("index.dat"), new byte[]{0});
      HistoryBatchStore reopened = store(Runnable::run, 1024);
      var summary = reopened.resumeRetiredCleanup().join();
      assertEquals(2, summary.owners());
      assertEquals(1, summary.deletedBatches());
      assertEquals(1, summary.failedOwners());
      assertTrue(reopened.loadBatch(validOwner, validBatch).join().isEmpty());
      assertTrue(reopened.loadBatch(damagedOwner, damagedBatch).join().isPresent());
      assertTrue(java.nio.file.Files.exists(root.resolve(damagedOwner.toString()).resolve("retired.dat")));
   }

   @Test
   void cleanupIntentSurvivesReopeningAfterIndexReplacement() {
      HistoryBatchStore store = store(Runnable::run, 1024);
      UUID owner = UUID.randomUUID();
      UUID old = UUID.randomUUID();
      UUID current = UUID.randomUUID();
      store.publishBatch(owner, old, new byte[]{1}).join();
      store.publishBatch(owner, current, new byte[]{2}).join();
      store.publishIndex(owner, new HistoryOrderIndex(List.of(old), List.of())).join();
      store.stageRetirements(owner, java.util.Set.of(old)).join();
      store.publishIndex(owner, new HistoryOrderIndex(List.of(current), List.of())).join();
      HistoryBatchStore reopened = store(Runnable::run, 1024);
      assertEquals(1, reopened.cleanupUnreferencedBatches(owner, java.util.Set.of()).join());
      assertTrue(reopened.loadBatch(owner, old).join().isEmpty());
      assertTrue(reopened.loadBatch(owner, current).join().isPresent());
      assertFalse(java.nio.file.Files.exists(root.resolve(owner.toString()).resolve("retired.dat")));
   }

   @Test
   void stagedCleanupBeforeIndexReplacementCannotDeleteReferencedHistory() {
      HistoryBatchStore store = store(Runnable::run, 1024);
      UUID owner = UUID.randomUUID();
      UUID batch = UUID.randomUUID();
      store.publishBatch(owner, batch, new byte[]{1}).join();
      store.publishIndex(owner, new HistoryOrderIndex(List.of(batch), List.of())).join();
      store.stageRetirements(owner, java.util.Set.of(batch)).join();
      HistoryBatchStore reopened = store(Runnable::run, 1024);
      assertEquals(0, reopened.cleanupUnreferencedBatches(owner, java.util.Set.of()).join());
      assertTrue(reopened.loadBatch(owner, batch).join().isPresent());
   }

   @Test
   void failedCleanupRetainsCandidatesForAnEmptyRetry() throws Exception {
      HistoryBatchStore store = store(Runnable::run, 1024);
      UUID owner = UUID.randomUUID();
      UUID retired = UUID.randomUUID();
      UUID current = UUID.randomUUID();
      store.publishBatch(owner, retired, new byte[]{1}).join();
      store.publishBatch(owner, current, new byte[]{2}).join();
      store.publishIndex(owner, new HistoryOrderIndex(List.of(current), List.of())).join();
      Path index = root.resolve(owner.toString()).resolve("index.dat");
      byte[] validIndex = java.nio.file.Files.readAllBytes(index);
      java.nio.file.Files.write(index, new byte[]{0});
      assertThrows(CompletionException.class,
         () -> store.cleanupUnreferencedBatches(owner, java.util.Set.of(retired)).join());
      assertTrue(store.loadBatch(owner, retired).join().isPresent());
      java.nio.file.Files.write(index, validIndex);
      assertEquals(1, store.cleanupUnreferencedBatches(owner, java.util.Set.of()).join());
      assertTrue(store.loadBatch(owner, retired).join().isEmpty());
      assertTrue(store.loadBatch(owner, current).join().isPresent());
      assertEquals(0, store.cleanupUnreferencedBatches(owner, java.util.Set.of()).join());
   }

   @Test
   void ownerQuotaIncludesIndexesAndDoesNotBlockAnotherOwner() {
      HistoryBatchStore store = new HistoryBatchStore(root, Runnable::run, 1, 32, 4096, 1024, 8, 2, 65);
      UUID owner = UUID.randomUUID();
      UUID batch = UUID.randomUUID();
      store.publishBatch(owner, batch, new byte[]{1}).join(); // 21 bytes.
      HistoryOrderIndex index = new HistoryOrderIndex(List.of(batch), List.of());
      store.publishIndex(owner, index).join(); // 44 bytes: exactly 65 combined.
      assertThrows(CompletionException.class,
         () -> store.publishBatch(owner, UUID.randomUUID(), new byte[]{2}).join());
      store.publishIndex(owner, new HistoryOrderIndex(List.of(), List.of(batch))).join();
      assertEquals(List.of(batch), store.loadIndex(owner).join().orElseThrow().redo());
      UUID other = UUID.randomUUID();
      store.publishBatch(other, UUID.randomUUID(), new byte[]{3}).join();
      assertArrayEquals(new byte[]{1}, store.loadBatch(owner, batch).join().orElseThrow());
   }

   @Test
   void rejectedIndexGrowthKeepsThePreviousOrder() {
      HistoryBatchStore store = new HistoryBatchStore(root, Runnable::run, 1, 32, 4096, 1024, 8, 2, 90);
      UUID owner = UUID.randomUUID();
      UUID first = UUID.randomUUID();
      UUID second = UUID.randomUUID();
      store.publishBatch(owner, first, new byte[]{1}).join();
      HistoryOrderIndex original = new HistoryOrderIndex(List.of(first), List.of());
      store.publishIndex(owner, original).join();
      store.publishBatch(owner, second, new byte[]{2}).join(); // 86 bytes before index growth.
      assertThrows(CompletionException.class,
         () -> store.publishIndex(owner, new HistoryOrderIndex(List.of(second, first), List.of())).join());
      assertEquals(original, store.loadIndex(owner).join().orElseThrow());
   }

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
   void explicitCleanupKeepsUndoRedoAndOtherFiles() throws Exception {
      HistoryBatchStore store = store(Runnable::run, 1024);
      UUID owner = UUID.randomUUID();
      UUID undo = UUID.randomUUID();
      UUID redo = UUID.randomUUID();
      UUID unused = UUID.randomUUID();
      store.publishBatch(owner, undo, new byte[]{1}).join();
      store.publishBatch(owner, redo, new byte[]{2}).join();
      store.publishBatch(owner, unused, new byte[]{3}).join();
      store.publishIndex(owner, new HistoryOrderIndex(List.of(undo), List.of(redo))).join();
      Path batches = root.resolve(owner.toString()).resolve("batches");
      Path unrelated = batches.resolve("notes.dat");
      java.nio.file.Files.writeString(unrelated, "keep");

      UUID pending = UUID.randomUUID();
      store.publishBatch(owner, pending, new byte[]{4}).join();
      assertEquals(1, store.cleanupUnreferencedBatches(owner, java.util.Set.of(unused, undo, redo)).join());
      assertTrue(store.loadBatch(owner, pending).join().isPresent());
      assertArrayEquals(new byte[]{1}, store.loadBatch(owner, undo).join().orElseThrow());
      assertArrayEquals(new byte[]{2}, store.loadBatch(owner, redo).join().orElseThrow());
      assertTrue(store.loadBatch(owner, unused).join().isEmpty());
      assertTrue(java.nio.file.Files.isRegularFile(unrelated));
   }

   @Test
   void missingOrDamagedIndexRefusesCleanupAndPreservesBatches() throws Exception {
      HistoryBatchStore store = store(Runnable::run, 1024);
      UUID owner = UUID.randomUUID();
      UUID batch = UUID.randomUUID();
      store.publishBatch(owner, batch, new byte[]{1}).join();
      assertThrows(CompletionException.class, () -> store.cleanupUnreferencedBatches(owner, java.util.Set.of(batch)).join());
      assertTrue(store.loadBatch(owner, batch).join().isPresent());

      Path index = root.resolve(owner.toString()).resolve("index.dat");
      java.nio.file.Files.write(index, new byte[]{1, 2, 3});
      assertThrows(CompletionException.class, () -> store.cleanupUnreferencedBatches(owner, java.util.Set.of(batch)).join());
      assertTrue(store.loadBatch(owner, batch).join().isPresent());
   }

   @Test
   void capacityScanIgnoresAFileRemovedAfterDirectoryEnumeration() throws Exception {
      Path removed = root.resolve("removed.dat");

      assertEquals(0L, HistoryBatchStore.existingFileSize(removed));
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
