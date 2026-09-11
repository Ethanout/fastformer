package io.github.fastformer.fastplace.world;

import com.mojang.logging.LogUtils;
import io.github.fastformer.fastplace.history.HistoryBatchStore;
import io.github.fastformer.fastplace.history.HistoryOrderIndex;
import io.github.fastformer.fastplace.history.WorldHistoryBatchCodec;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
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
   private static final long MAX_STORE_BYTES = 8L * 1024L * 1024L * 1024L;
   private static final long MAX_QUEUE_BYTES = MAX_BATCH_BYTES + 20L;
   private static final int MAX_QUEUE_OPERATIONS = 512;
   private static final long MAX_PENDING_ENCODE_BYTES = 512L * 1024L * 1024L;
   private static final int MAX_PENDING_OPERATIONS = 512;
   // Completed history must not queue behind or ahead of the first journal write.
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
      if (server == null || owner == null) return CompletableFuture.completedFuture(null);
      List<WorldChangeBatch> ownedUndo = List.copyOf(undo);
      List<WorldChangeBatch> ownedRedo = List.copyOf(redo);
      long retainedBytes = Math.addExact(estimatedBytes(ownedUndo), estimatedBytes(ownedRedo));
      HistoryOrderIndex index = new HistoryOrderIndex(operationIds(ownedUndo), operationIds(ownedRedo));
      CompletableFuture<Void> result = enqueue(server, owner, retainedBytes, () -> {
         CompletableFuture<Void> batches = CompletableFuture.completedFuture(null);
         for (WorldChangeBatch batch : ownedUndo) batches = ensureBatch(server, owner, batch, batches);
         for (WorldChangeBatch batch : ownedRedo) batches = ensureBatch(server, owner, batch, batches);
         return batches.thenCompose(ignored -> replaceIndex(server, owner, index));
      });
      result.whenComplete((ignored, failure) -> reportFailure(owner, null, failure));
      return result;
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
         return storage.publishIndex(owner, next).thenCompose(ignored -> {
            if (retired.isEmpty()) return CompletableFuture.completedFuture(null);
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
         MAX_STORE_BYTES,
         MAX_QUEUE_BYTES,
         MAX_QUEUE_OPERATIONS,
         MAX_INDEX_ENTRIES
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
