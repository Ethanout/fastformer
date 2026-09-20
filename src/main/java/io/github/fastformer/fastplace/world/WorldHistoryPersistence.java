package io.github.fastformer.fastplace.world;

import com.mojang.logging.LogUtils;
import io.github.fastformer.fastplace.history.HistoryBatchStore;
import io.github.fastformer.fastplace.history.HistoryOrderIndex;
import io.github.fastformer.fastplace.history.WorldHistoryBatchCodec;
import io.github.fastformer.fastplace.history.HistoryStorageConfig;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

/** Connects completed world histories to the per-save durable batch store. */
final class WorldHistoryPersistence {
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final int FORMAT_VERSION = 1;
   private static final int MAX_BATCH_BYTES = 256 * 1024 * 1024;
   private static final long MAX_QUEUE_BYTES = MAX_BATCH_BYTES + 20L;
   private static final int MAX_QUEUE_OPERATIONS = 512;
   private static final long MAX_PENDING_ENCODE_BYTES = 512L * 1024L * 1024L;
   private static final long MAX_DECODED_BATCH_BYTES = 512L * 1024L * 1024L;
   private static final int MAX_PENDING_OPERATIONS = 512;
   // The store serializes file operations. This executor keeps those operations
   // off the server thread while the coordinator preserves publication order.
   private static final java.util.concurrent.Executor IO_EXECUTOR = new java.util.concurrent.ThreadPoolExecutor(
      1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
      new java.util.concurrent.ArrayBlockingQueue<>(MAX_PENDING_OPERATIONS),
      runnable -> {
         Thread thread = new Thread(runnable, "FastFormer-history-io");
         thread.setDaemon(true);
         return thread;
      },
      new java.util.concurrent.ThreadPoolExecutor.AbortPolicy()
   );
   private static final int MAX_INDEX_ENTRIES = WorldHistoryManager.MAX_LIMIT * 2;
   private static final Map<MinecraftServer, HistoryBatchStore> STORES = new WeakHashMap<>();
   private static final Map<MinecraftServer, Map<UUID, CompletableFuture<Void>>> OWNER_CHAINS = new WeakHashMap<>();
   private static long pendingEncodeBytes;
   private static int pendingOperations;

   private WorldHistoryPersistence() {
   }

   static synchronized CompletableFuture<Void> pendingWrites(MinecraftServer server) {
      Map<UUID, CompletableFuture<Void>> chains = OWNER_CHAINS.get(server);
      if (chains == null) return CompletableFuture.completedFuture(null);
      return CompletableFuture.allOf(chains.values().toArray(CompletableFuture[]::new));
   }

   static void awaitShutdown(MinecraftServer server, CompletableFuture<Void> finalSnapshots) {
      try {
         CompletableFuture.allOf(finalSnapshots, pendingWrites(server))
            .get(30, java.util.concurrent.TimeUnit.SECONDS);
      } catch (InterruptedException failure) {
         Thread.currentThread().interrupt();
         LOGGER.error("Server shutdown was interrupted while waiting for history writes", failure);
      } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failure) {
         LOGGER.error("History writes did not finish before the shutdown deadline", failure);
      }
   }

   static void resumeCleanup(MinecraftServer server, java.util.Set<UUID> activeOwners) {
      store(server).resumeRetiredCleanup().thenCompose(summary -> {
         int days = HistoryStorageConfig.retentionDays();
         return days <= 0 ? CompletableFuture.completedFuture(summary)
            : store(server).cleanupExpiredAll(
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

   static CompletableFuture<Void> publishNewBatch(
      MinecraftServer server,
      UUID owner,
      WorldChangeBatch batch,
      List<UUID> undo,
      List<UUID> redo
   ) {
      if (server == null || owner == null || batch == null) return CompletableFuture.completedFuture(null);
      HistoryOrderIndex index = new HistoryOrderIndex(undo, redo);
      CompletableFuture<Void> result = enqueue(server, owner, batch.estimatedBytes(), () ->
         store(server).publishBatch(owner, batch.operationId(), encode(batch))
            .thenCompose(ignored -> replaceIndex(server, owner, index))
      );
      result.whenComplete((ignored, failure) -> reportFailure(owner, batch.operationId(), failure));
      return result;
   }

   static CompletableFuture<Void> publishIndex(MinecraftServer server, UUID owner, List<UUID> undo, List<UUID> redo) {
      if (server == null || owner == null) return CompletableFuture.completedFuture(null);
      HistoryOrderIndex index = new HistoryOrderIndex(undo, redo);
      CompletableFuture<Void> result = enqueue(server, owner, 0L, () -> replaceIndex(server, owner, index));
      result.whenComplete((ignored, failure) -> reportFailure(owner, null, failure));
      return result;
   }

   static CompletableFuture<Void> publishSnapshot(
      MinecraftServer server, UUID owner, List<WorldChangeBatch> undo, List<WorldChangeBatch> redo
   ) {
      return publishSnapshot(server, owner, undo, redo, operationIds(undo), operationIds(redo));
   }

   static CompletableFuture<Void> publishSnapshot(
      MinecraftServer server,
      UUID owner,
      List<WorldChangeBatch> undo,
      List<WorldChangeBatch> redo,
      List<UUID> undoOrder,
      List<UUID> redoOrder
   ) {
      if (server == null || owner == null) return CompletableFuture.completedFuture(null);
      List<WorldChangeBatch> ownedUndo = List.copyOf(undo);
      List<WorldChangeBatch> ownedRedo = List.copyOf(redo);
      long retainedBytes = Math.addExact(estimatedBytes(ownedUndo), estimatedBytes(ownedRedo));
      HistoryOrderIndex index = new HistoryOrderIndex(undoOrder, redoOrder);
      CompletableFuture<Void> result = enqueue(server, owner, retainedBytes, () -> {
         CompletableFuture<Void> batches = CompletableFuture.completedFuture(null);
         for (WorldChangeBatch batch : ownedUndo) batches = ensureBatch(server, owner, batch, batches);
         for (WorldChangeBatch batch : ownedRedo) batches = ensureBatch(server, owner, batch, batches);
         return batches.thenCompose(ignored -> replaceIndex(server, owner, index));
      });
      result.whenComplete((ignored, failure) -> reportFailure(owner, null, failure));
      return result;
   }

   static CompletableFuture<Void> publishBatchOnly(
      MinecraftServer server, UUID owner, WorldChangeBatch batch
   ) {
      if (server == null || owner == null || batch == null) return CompletableFuture.completedFuture(null);
      CompletableFuture<Void> result = enqueue(server, owner, batch.estimatedBytes(), () ->
         store(server).publishBatch(owner, batch.operationId(), encode(batch))
      );
      result.whenComplete((ignored, failure) -> reportFailure(owner, batch.operationId(), failure));
      return result;
   }

   static CompletableFuture<Void> reconcileCommittedJournal(MinecraftServer server, Path journalDirectory) {
      RecoveryJournalManifest.Manifest manifest;
      try {
         manifest = RecoveryJournalManifest.read(journalDirectory.resolve("manifest.dat"));
      } catch (IOException failure) {
         return CompletableFuture.failedFuture(failure);
      }
      UUID owner = manifest.owner();
      UUID operation = manifest.operationId();
      return enqueue(server, owner, 0L, () -> {
         HistoryBatchStore storage = store(server);
         return storage.verifyBatch(owner, operation).thenCompose(published -> {
            if (!published) {
               return CompletableFuture.failedFuture(new IOException(
                  "Committed journal has no published history batch: " + operation
               ));
            }
            return storage.loadIndex(owner).thenCompose(existing -> {
               HistoryOrderIndex previous = existing.orElse(new HistoryOrderIndex(List.of(), List.of()));
               if (previous.undo().contains(operation) || previous.redo().contains(operation)) {
                  return CompletableFuture.completedFuture(null);
               }
               ArrayList<UUID> undo = new ArrayList<>(Math.min(MAX_INDEX_ENTRIES, previous.undo().size() + 1));
               undo.add(operation);
               for (UUID id : previous.undo()) {
                  if (undo.size() >= MAX_INDEX_ENTRIES) break;
                  undo.add(id);
               }
               return storage.publishIndex(owner, new HistoryOrderIndex(undo, List.of()));
            });
         });
      });
   }

   static CompletableFuture<LoadedHistory> loadSnapshot(
      MinecraftServer server, UUID owner, int maxEntriesPerStack, long maxRetainedBytes
   ) {
      if (server == null || owner == null || maxEntriesPerStack < 1 || maxRetainedBytes < 1L) {
         return CompletableFuture.completedFuture(LoadedHistory.empty());
      }
      var registries = server.registryAccess();
      return enqueueValue(server, owner, 0L, () -> {
         HistoryBatchStore storage = store(server);
         return storage.loadIndex(owner).thenCompose(optional -> {
            if (optional.isEmpty()) return CompletableFuture.completedFuture(LoadedHistory.empty());
            HistoryOrderIndex index = optional.orElseThrow();
            LoadAccumulator accumulator = new LoadAccumulator(maxRetainedBytes);
            return loadBatches(storage, owner, index.undo(), maxEntriesPerStack, registries, accumulator)
               .thenCompose(undo -> loadBatches(
                  storage, owner, index.redo(), maxEntriesPerStack, registries, accumulator
               ).thenApply(redo -> new LoadedHistory(undo, redo, index.undo(), index.redo())));
         });
      });
   }

   static CompletableFuture<List<WorldChangeBatch>> loadPage(
      MinecraftServer server,
      UUID owner,
      List<UUID> ids,
      int maxEntries,
      long maxRetainedBytes
   ) {
      if (server == null || owner == null || ids == null || ids.isEmpty()
         || maxEntries < 1 || maxRetainedBytes < 1L) {
         return CompletableFuture.completedFuture(List.of());
      }
      List<UUID> ownedIds = List.copyOf(ids);
      var registries = server.registryAccess();
      return enqueueValue(server, owner, 0L, () -> loadBatches(
         store(server), owner, ownedIds, maxEntries, registries,
         new LoadAccumulator(maxRetainedBytes)
      ));
   }

   private static CompletableFuture<List<WorldChangeBatch>> loadBatches(
      HistoryBatchStore storage,
      UUID owner,
      List<UUID> ids,
      int maxEntries,
      net.minecraft.core.HolderLookup.Provider registries,
      LoadAccumulator accumulator
   ) {
      CompletableFuture<ArrayList<WorldChangeBatch>> chain = CompletableFuture.completedFuture(new ArrayList<>());
      for (UUID id : ids) {
         chain = chain.thenCompose(loaded -> {
            if (loaded.size() >= maxEntries || accumulator.full) {
               return CompletableFuture.completedFuture(loaded);
            }
            return storage.loadBatch(owner, id).thenApply(payload -> {
               if (payload.isEmpty()) {
                  throw new java.util.concurrent.CompletionException(
                     new IOException("History index references a missing batch: " + id)
                  );
               }
               WorldChangeBatch batch;
               try {
                  batch = WorldHistoryBatchCodec.decode(registries, payload.orElseThrow(), MAX_DECODED_BATCH_BYTES);
               } catch (IOException failure) {
                  throw new java.util.concurrent.CompletionException(failure);
               }
               long bytes = batch.estimatedBytes();
               if (!loaded.isEmpty() && bytes > accumulator.remainingBytes) {
                  accumulator.full = true;
                  return loaded;
               }
               loaded.add(batch);
               accumulator.remainingBytes = Math.max(0L, accumulator.remainingBytes - bytes);
               accumulator.full = accumulator.remainingBytes == 0L;
               return loaded;
            });
         });
      }
      return chain.thenApply(List::copyOf);
   }

   record LoadedHistory(
      List<WorldChangeBatch> undo,
      List<WorldChangeBatch> redo,
      List<UUID> undoOrder,
      List<UUID> redoOrder
   ) {
      LoadedHistory {
         undo = List.copyOf(undo);
         redo = List.copyOf(redo);
         undoOrder = List.copyOf(undoOrder);
         redoOrder = List.copyOf(redoOrder);
      }

      static LoadedHistory empty() {
         return new LoadedHistory(List.of(), List.of(), List.of(), List.of());
      }
   }

   private static final class LoadAccumulator {
      private long remainingBytes;
      private boolean full;

      private LoadAccumulator(long remainingBytes) {
         this.remainingBytes = remainingBytes;
      }
   }

   private static synchronized CompletableFuture<Void> enqueue(
      MinecraftServer server, UUID owner, long retainedBytes, Supplier<CompletableFuture<Void>> operation
   ) {
      return enqueueValue(server, owner, retainedBytes, operation);
   }

   private static synchronized <T> CompletableFuture<T> enqueueValue(
      MinecraftServer server, UUID owner, long retainedBytes, Supplier<CompletableFuture<T>> operation
   ) {
      if (retainedBytes < 0L
         || pendingOperations >= MAX_PENDING_OPERATIONS
         || retainedBytes > MAX_PENDING_ENCODE_BYTES - pendingEncodeBytes) {
         return CompletableFuture.failedFuture(new IOException("History persistence queue is full"));
      }
      pendingOperations++;
      pendingEncodeBytes += retainedBytes;
      Map<UUID, CompletableFuture<Void>> chains = OWNER_CHAINS.computeIfAbsent(server, ignored -> new java.util.HashMap<>());
      CompletableFuture<Void> previous = chains.getOrDefault(owner, CompletableFuture.completedFuture(null));
      try {
         CompletableFuture<T> result = previous.handle((ignored, failure) -> null)
            .thenComposeAsync(ignored -> operation.get(), executor());
         CompletableFuture<Void> next = result.handle((ignored, failure) -> null);
         chains.put(owner, next);
         result.whenComplete((ignored, failure) -> complete(server, owner, next, retainedBytes));
         return result.copy();
      } catch (RuntimeException | Error failure) {
         pendingOperations--;
         pendingEncodeBytes -= retainedBytes;
         if (chains.isEmpty()) OWNER_CHAINS.remove(server);
         throw failure;
      }
   }

   private static synchronized void complete(
      MinecraftServer server, UUID owner, CompletableFuture<Void> completed, long retainedBytes
   ) {
      pendingOperations--;
      pendingEncodeBytes -= retainedBytes;
      Map<UUID, CompletableFuture<Void>> chains = OWNER_CHAINS.get(server);
      if (chains == null || chains.get(owner) != completed) return;
      chains.remove(owner);
      if (chains.isEmpty()) OWNER_CHAINS.remove(server);
   }

   private static byte[] encode(WorldChangeBatch batch) {
      try {
         return WorldHistoryBatchCodec.encode(batch, MAX_BATCH_BYTES);
      } catch (IOException failure) {
         throw new java.io.UncheckedIOException(failure);
      }
   }

   private static CompletableFuture<Void> ensureBatch(
      MinecraftServer server, UUID owner, WorldChangeBatch batch, CompletableFuture<Void> previous
   ) {
      return previous.thenCompose(ignored -> store(server).verifyBatch(owner, batch.operationId()).thenCompose(existing ->
         existing
            ? CompletableFuture.completedFuture(null)
            : store(server).publishBatch(owner, batch.operationId(), encode(batch))
      ));
   }

   private static List<UUID> operationIds(List<WorldChangeBatch> batches) {
      return batches.stream().map(WorldChangeBatch::operationId).toList();
   }

   private static CompletableFuture<Void> replaceIndex(MinecraftServer server, UUID owner, HistoryOrderIndex next) {
      HistoryBatchStore storage = store(server);
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

   private static long estimatedBytes(List<WorldChangeBatch> batches) {
      long total = 0L;
      for (WorldChangeBatch batch : batches) total = Math.addExact(total, batch.estimatedBytes());
      return total;
   }

   private static synchronized HistoryBatchStore store(MinecraftServer server) {
      return STORES.computeIfAbsent(server, current -> new HistoryBatchStore(
         historyRoot(current),
         executor(),
         FORMAT_VERSION,
         MAX_BATCH_BYTES,
         HistoryStorageConfig.totalDiskBytes(),
         MAX_QUEUE_BYTES,
         MAX_QUEUE_OPERATIONS,
         MAX_INDEX_ENTRIES,
         HistoryStorageConfig.ownerDiskBytes()
      ));
   }

   private static Path historyRoot(MinecraftServer server) {
      return server.getWorldPath(LevelResource.ROOT).resolve("fastformer-history");
   }

   static java.util.concurrent.Executor executor() {
      return IO_EXECUTOR;
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
