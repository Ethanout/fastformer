package io.github.fastformer.server.history;

import io.github.fastformer.server.history.entry.HistoryEntry;
import io.github.fastformer.server.history.entry.HistoryEntryFormat;

import io.github.fastformer.server.history.store.HistoryBatchStore;
import io.github.fastformer.server.history.store.HistoryOrderIndex;

import com.mojang.logging.LogUtils;
import io.github.fastformer.common.persistence.IncompatibleWorldDataException;
import io.github.fastformer.common.persistence.MinecraftDataVersion;
import io.github.fastformer.core.persist.Envelope;
import io.github.fastformer.core.persist.StoragePaths;
import io.github.fastformer.server.lifecycle.ServerBackgroundTasks;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

/** Connects completed world histories to the per-save durable batch store. */
final class DiskHistoryRepository<E extends HistoryEntry> implements HistoryRepository<E> {
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final int FORMAT_VERSION = 1;
   private static final int MAX_BATCH_BYTES = 256 * 1024 * 1024;
   private static final long MAX_QUEUE_BYTES = MAX_BATCH_BYTES + (long)Envelope.HEADER_BYTES;
   private static final int MAX_QUEUE_OPERATIONS = 512;
   private static final long MAX_PENDING_ENCODE_BYTES = 512L * 1024L * 1024L;
   private static final long MAX_DECODED_BATCH_BYTES = 512L * 1024L * 1024L;
   private static final int MAX_PENDING_OPERATIONS = 512;
   private static final int MAX_INDEX_ENTRIES = HistoryService.MAX_LIMIT * 2;
   private final HistoryWriteQueue queue;
   private final HistoryBatchStore storage;
   private final net.minecraft.core.HolderLookup.Provider registries;
   private final HistoryEntryFormat<E> format;
   private boolean closed;

   DiskHistoryRepository(HistoryBatchStore storage, Executor executor,
      net.minecraft.core.HolderLookup.Provider registries, HistoryEntryFormat<E> format) {
      this.storage = Objects.requireNonNull(storage, "storage");
      this.format = Objects.requireNonNull(format, "format");
      this.registries = Objects.requireNonNull(registries, "registries");
      this.queue = new HistoryWriteQueue(executor, MAX_PENDING_OPERATIONS, MAX_PENDING_ENCODE_BYTES);
   }

   @Override
   public CompletableFuture<Void> pendingWrites() {
      return queue.pendingWrites();
   }

   @Override
   public synchronized void resumeCleanup(java.util.Set<UUID> activeOwners) {
      if (closed) return;
      storage.resumeRetiredCleanup().thenCompose(summary -> {
         int days = HistoryStorageConfig.retentionDays();
         return days <= 0 ? CompletableFuture.completedFuture(summary)
            : storage.cleanupExpiredAll(
               java.time.Instant.now().minus(java.time.Duration.ofDays(days)), activeOwners
            )
               .thenApply(summary::plus);
      }).whenComplete((summary, failure) -> {
         if (failure != null) {
            LOGGER.error("Could not scan pending history cleanup", failure);
         } else if (summary.owners() > 0) {
            LOGGER.info("History cleanup scanned {} owners, removed {} batches, failed for {} owners",
               summary.owners(), summary.deletedBatches(), summary.failedOwners());
         }
      });
   }

   @Override
   public CompletableFuture<Void> publishNewBatch(
      UUID owner,
      E batch,
      List<UUID> undo,
      List<UUID> redo
   ) {
      Objects.requireNonNull(owner, "owner");
      Objects.requireNonNull(batch, "batch");
      HistoryOrderIndex index = new HistoryOrderIndex(undo, redo);
      CompletableFuture<Void> result = queue.submit(owner, batch.estimatedBytes(), () ->
         storage.publishBatch(owner, batch.operationId(), encode(batch))
            .thenCompose(ignored -> replaceIndex(owner, index))
      );
      result.whenComplete((ignored, failure) -> reportFailure(owner, batch.operationId(), failure));
      return result;
   }

   @Override
   public CompletableFuture<Void> publishIndex(UUID owner, List<UUID> undo, List<UUID> redo) {
      HistoryOrderIndex index = new HistoryOrderIndex(undo, redo);
      CompletableFuture<Void> result = queue.submit(owner, 0L, () -> replaceIndex(owner, index));
      result.whenComplete((ignored, failure) -> reportFailure(owner, null, failure));
      return result;
   }

   @Override
   public CompletableFuture<Void> publishSnapshot(
      UUID owner,
      List<E> undo,
      List<E> redo,
      List<UUID> undoOrder,
      List<UUID> redoOrder
   ) {
      List<E> ownedUndo = List.copyOf(undo);
      List<E> ownedRedo = List.copyOf(redo);
      long retainedBytes = Math.addExact(estimatedBytes(ownedUndo), estimatedBytes(ownedRedo));
      HistoryOrderIndex index = new HistoryOrderIndex(undoOrder, redoOrder);
      CompletableFuture<Void> result = queue.submit(owner, retainedBytes, () -> {
         CompletableFuture<Void> batches = CompletableFuture.completedFuture(null);
         for (E batch : ownedUndo) batches = ensureBatch(owner, batch, batches);
         for (E batch : ownedRedo) batches = ensureBatch(owner, batch, batches);
         return batches.thenCompose(ignored -> replaceIndex(owner, index));
      });
      result.whenComplete((ignored, failure) -> reportFailure(owner, null, failure));
      return result;
   }

   @Override
   public CompletableFuture<Void> publishBatchOnly(
      UUID owner, E batch
   ) {
      Objects.requireNonNull(owner, "owner");
      Objects.requireNonNull(batch, "batch");
      CompletableFuture<Void> result = queue.submit(owner, batch.estimatedBytes(), () ->
         storage.publishBatch(owner, batch.operationId(), encode(batch))
      );
      result.whenComplete((ignored, failure) -> reportFailure(owner, batch.operationId(), failure));
      return result;
   }

   @Override
   public CompletableFuture<Void> reconcileCommittedJournal(UUID owner, UUID operation) {
      return queue.submit(owner, 0L, () -> storage.loadIndex(owner).thenCompose(existing -> {
            HistoryOrderIndex previous = existing.orElse(new HistoryOrderIndex(List.of(), List.of()));
            if (previous.undo().contains(operation) || previous.redo().contains(operation)) {
               return CompletableFuture.completedFuture(null);
            }
            return storage.retiredBatches(owner).thenCompose(retired -> {
               if (existing.isPresent() && retired.contains(operation)) return CompletableFuture.completedFuture(null);
               return storage.verifyBatch(owner, operation).thenCompose(published -> {
                  if (!published) return CompletableFuture.failedFuture(new IOException(
                     "Committed journal has no published history batch: " + operation));
                  ArrayList<UUID> undo = new ArrayList<>(Math.min(MAX_INDEX_ENTRIES, previous.undo().size() + 1));
                  undo.add(operation);
                  for (UUID id : previous.undo()) {
                     if (undo.size() >= MAX_INDEX_ENTRIES) break;
                     undo.add(id);
                  }
                  return storage.publishIndex(owner, new HistoryOrderIndex(undo, List.of()));
               });
            });
         }));
   }

   @Override
   public CompletableFuture<Snapshot<E>> loadSnapshot(
      UUID owner, int maxEntriesPerStack, long maxRetainedBytes
   ) {
      if (owner == null || maxEntriesPerStack < 1 || maxRetainedBytes < 1L) {
         return CompletableFuture.completedFuture(Snapshot.empty());
      }
      return queue.submit(owner, 0L, () -> storage.loadIndex(owner).thenCompose(optional -> {
            if (optional.isEmpty()) return CompletableFuture.completedFuture(Snapshot.empty());
            HistoryOrderIndex index = optional.orElseThrow();
            LoadAccumulator accumulator = new LoadAccumulator(maxRetainedBytes);
            return loadBatches(storage, owner, index.undo(), maxEntriesPerStack, registries, accumulator)
               .thenCompose(undo -> loadBatches(
                  storage, owner, index.redo(), maxEntriesPerStack, registries, accumulator
               ).thenApply(redo -> new Snapshot<>(undo, redo, index.undo(), index.redo())));
         }));
   }

   @Override
   public CompletableFuture<List<E>> loadPage(
      UUID owner,
      List<UUID> ids,
      int maxEntries,
      long maxRetainedBytes
   ) {
      if (owner == null || ids == null || ids.isEmpty()
         || maxEntries < 1 || maxRetainedBytes < 1L) {
         return CompletableFuture.completedFuture(List.of());
      }
      List<UUID> ownedIds = List.copyOf(ids);
      return queue.submit(owner, 0L, () -> loadBatches(
         storage, owner, ownedIds, maxEntries, registries,
         new LoadAccumulator(maxRetainedBytes)
      ));
   }

   private CompletableFuture<List<E>> loadBatches(
      HistoryBatchStore storage,
      UUID owner,
      List<UUID> ids,
      int maxEntries,
      net.minecraft.core.HolderLookup.Provider registries,
      LoadAccumulator accumulator
   ) {
      CompletableFuture<ArrayList<E>> chain = CompletableFuture.completedFuture(new ArrayList<>());
      for (UUID id : ids) {
         chain = chain.thenCompose(loaded -> {
            if (loaded.size() >= maxEntries || accumulator.full) {
               return CompletableFuture.completedFuture(loaded);
            }
            return storage.loadBatch(owner, id).thenCompose(payload -> {
               if (payload.isEmpty()) {
                  throw new java.util.concurrent.CompletionException(
                     new IOException("History index references a missing batch: " + id)
                  );
               }
               E batch;
               try {
                  batch = format.decode(registries, payload.orElseThrow(), MAX_DECODED_BATCH_BYTES);
                  if (!id.equals(batch.operationId())) throw new IOException("History entry ID differs from its indexed file: " + id);
               } catch (IncompatibleWorldDataException incompatible) {
                  return storage.quarantineBatch(owner, id, incompatible.getMessage()).thenCompose(path -> {
                     LOGGER.error("FastFormer quarantined incompatible history for owner {} at {}. "
                        + "The ordering index is retained for administrator review.", owner, path, incompatible);
                     return CompletableFuture.<ArrayList<E>>failedFuture(incompatible);
                  });
               } catch (IOException failure) {
                  throw new java.util.concurrent.CompletionException(failure);
               }
               long bytes = batch.estimatedBytes();
               if (!loaded.isEmpty() && bytes > accumulator.remainingBytes) {
                  accumulator.full = true;
                  return CompletableFuture.completedFuture(loaded);
               }
               loaded.add(batch);
               accumulator.remainingBytes = Math.max(0L, accumulator.remainingBytes - bytes);
               accumulator.full = accumulator.remainingBytes == 0L;
               return CompletableFuture.completedFuture(loaded);
            });
         });
      }
      return chain.thenApply(List::copyOf);
   }

   private static final class LoadAccumulator {
      private long remainingBytes;
      private boolean full;

      private LoadAccumulator(long remainingBytes) {
         this.remainingBytes = remainingBytes;
      }
   }

   private byte[] encode(E batch) {
      try {
         return format.encode(batch, MAX_BATCH_BYTES);
      } catch (IOException failure) {
         throw new java.io.UncheckedIOException(failure);
      }
   }

   private CompletableFuture<Void> ensureBatch(
      UUID owner, E batch, CompletableFuture<Void> previous
   ) {
      return previous.thenCompose(ignored -> storage.verifyBatch(owner, batch.operationId()).thenCompose(existing ->
         existing
            ? CompletableFuture.completedFuture(null)
            : storage.publishBatch(owner, batch.operationId(), encode(batch))
      ));
   }

   private CompletableFuture<Void> replaceIndex(UUID owner, HistoryOrderIndex next) {
      return storage.loadIndex(owner).thenCompose(previous -> {
         java.util.Set<UUID> retired = new java.util.HashSet<>();
         previous.ifPresent(index -> {
            retired.addAll(index.undo());
            retired.addAll(index.redo());
         });
         retired.removeAll(next.undo());
         retired.removeAll(next.redo());
         return storage.stageRetirements(owner, retired).thenCompose(staged -> storage.publishIndex(owner, next)).thenCompose(ignored -> {
            return storage.cleanupUnreferencedBatches(owner, retired).thenApply(deleted -> null);
         });
      });
   }

   private static long estimatedBytes(List<? extends HistoryEntry> batches) {
      long total = 0L;
      for (HistoryEntry batch : batches) total = Math.addExact(total, batch.estimatedBytes());
      return total;
   }

   static <E extends HistoryEntry> DiskHistoryRepository<E> open(MinecraftServer server, HistoryEntryFormat<E> format) {
      Objects.requireNonNull(server, "server");
      Executor executor = ServerBackgroundTasks.serial(server, "history-io", MAX_PENDING_OPERATIONS);
      Path world = server.getWorldPath(LevelResource.ROOT);
      Path recoveryDirectory = StoragePaths.recovery(world);
      HistoryBatchStore storage = new HistoryBatchStore(
         StoragePaths.history(world), executor,
         new Envelope.Format(0x46464832, FORMAT_VERSION, MinecraftDataVersion.current()), MAX_BATCH_BYTES,
         HistoryStorageConfig.totalDiskBytes(), MAX_QUEUE_BYTES, MAX_QUEUE_OPERATIONS,
         MAX_INDEX_ENTRIES, HistoryStorageConfig.ownerDiskBytes(),
         owner -> io.github.fastformer.server.recovery.RecoveryHistoryReferences.operations(recoveryDirectory, owner)
      );
      return new DiskHistoryRepository<>(storage, executor, server.registryAccess(), format);
   }

   @Override
   public synchronized void close() {
      // Accepted operations retain their store and budget until completion.
      closed = true;
      queue.close();
   }

   private static void reportFailure(UUID owner, UUID batch, Throwable failure) {
      if (failure == null) return;
      if (batch == null) {
         LOGGER.error("Could not persist FastFormer history index for {}", owner, failure);
      } else {
         LOGGER.error("Could not persist FastFormer history batch {} for {}", batch, owner, failure);
      }
   }
}
