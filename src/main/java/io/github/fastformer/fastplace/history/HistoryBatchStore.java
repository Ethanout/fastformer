package io.github.fastformer.fastplace.history;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

/** Stores immutable batch payloads separately from an atomically replaced ordering index. */
public final class HistoryBatchStore {
   private static final long ENVELOPE_BYTES = Integer.BYTES * 3L + Long.BYTES;
   private static final String INDEX_FILE = "index.dat";

   private final Path root;
   private final Executor executor;
   private final int formatVersion;
   private final long maxBatchPayloadBytes;
   private final long maxTotalStoredBytes;
   private final long maxQueuedBytes;
   private final int maxQueuedOperations;
   private final int maxIndexEntries;
   private CompletableFuture<Void> tail = CompletableFuture.completedFuture(null);
   private long queuedBytes;
   private int queuedOperations;

   public HistoryBatchStore(
         Path root,
         Executor executor,
         int formatVersion,
         long maxBatchPayloadBytes,
         long maxTotalStoredBytes,
         long maxQueuedBytes,
         int maxQueuedOperations,
         int maxIndexEntries) {
      this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
      this.executor = Objects.requireNonNull(executor, "executor");
      if (formatVersion < 1 || maxBatchPayloadBytes < 0 || maxTotalStoredBytes < ENVELOPE_BYTES
            || maxQueuedBytes < 0 || maxQueuedOperations < 1 || maxIndexEntries < 0) {
         throw new IllegalArgumentException("Invalid history batch store configuration");
      }
      this.formatVersion = formatVersion;
      this.maxBatchPayloadBytes = maxBatchPayloadBytes;
      this.maxTotalStoredBytes = maxTotalStoredBytes;
      this.maxQueuedBytes = maxQueuedBytes;
      this.maxQueuedOperations = maxQueuedOperations;
      this.maxIndexEntries = maxIndexEntries;
   }

   public CompletableFuture<Void> publishBatch(UUID ownerId, UUID batchId, byte[] payload) {
      Objects.requireNonNull(ownerId, "ownerId");
      Objects.requireNonNull(batchId, "batchId");
      Objects.requireNonNull(payload, "payload");
      if (payload.length > maxBatchPayloadBytes) return failed("History batch exceeds its disk quota");
      int payloadLength = payload.length;
      return enqueueCopied(payloadLength + ENVELOPE_BYTES, payload, ownedPayload -> {
         Path target = batchFile(ownerId, batchId);
         try {
            if (Files.exists(target)) throw new IOException("History batch is already published: " + batchId);
            requireTotalCapacity(payloadLength + ENVELOPE_BYTES);
            HistoryEnvelopeFile.write(target, formatVersion, ownedPayload);
         } catch (IOException ex) {
            throw new UncheckedIOException(ex);
         }
      });
   }

   public CompletableFuture<Optional<byte[]>> loadBatch(UUID ownerId, UUID batchId) {
      Path target = batchFile(ownerId, batchId);
      return enqueueRead(() -> {
         if (!Files.isRegularFile(target)) return Optional.empty();
         try {
            if (Files.size(target) > Math.addExact(maxBatchPayloadBytes, ENVELOPE_BYTES)) {
               throw new IOException("History batch exceeds its disk quota");
            }
            return Optional.of(VersionedHistoryEnvelope.decode(Files.readAllBytes(target), formatVersion).payload());
         } catch (IOException ex) {
            throw new UncheckedIOException(ex);
         }
      });
   }

   public CompletableFuture<Void> publishIndex(UUID ownerId, HistoryOrderIndex index) {
      Objects.requireNonNull(ownerId, "ownerId");
      Objects.requireNonNull(index, "index");
      HistoryOrderIndex ownedIndex = new HistoryOrderIndex(index.undo(), index.redo());
      int entries = Math.addExact(ownedIndex.undo().size(), ownedIndex.redo().size());
      if (entries > maxIndexEntries) return failed("History index exceeds its entry quota");
      byte[] payload = HistoryOrderIndex.encode(ownedIndex);
      return enqueue(payload.length + ENVELOPE_BYTES, () -> {
         try {
            requireUniqueIds(ownedIndex);
            requirePublishedBatches(ownerId, ownedIndex);
            Path target = indexFile(ownerId);
            long previousBytes = Files.isRegularFile(target) ? Files.size(target) : 0L;
            requireTotalCapacity(payload.length + ENVELOPE_BYTES - previousBytes);
            HistoryEnvelopeFile.write(target, formatVersion, payload);
         } catch (IOException ex) {
            throw new UncheckedIOException(ex);
         }
      });
   }

   public CompletableFuture<Optional<HistoryOrderIndex>> loadIndex(UUID ownerId) {
      Path target = indexFile(ownerId);
      return enqueueRead(() -> {
         if (!Files.isRegularFile(target)) return Optional.empty();
         try {
            long maximumBytes = HistoryOrderIndex.maximumEncodedBytes(maxIndexEntries);
            if (Files.size(target) > maximumBytes + ENVELOPE_BYTES) throw new IOException("History index exceeds its entry quota");
            byte[] payload = VersionedHistoryEnvelope.decode(Files.readAllBytes(target), formatVersion).payload();
            HistoryOrderIndex index = HistoryOrderIndex.decode(payload, maxIndexEntries);
            requireUniqueIds(index);
            requirePublishedBatches(ownerId, index);
            return Optional.of(index);
         } catch (IOException ex) {
            throw new UncheckedIOException(ex);
         }
      });
   }

   private synchronized CompletableFuture<Void> enqueue(long bytes, Runnable operation) {
      if (queuedOperations >= maxQueuedOperations || bytes > maxQueuedBytes - queuedBytes) {
         return failed("History I/O queue is full");
      }
      queuedBytes += bytes;
      queuedOperations++;
      try {
         CompletableFuture<Void> result = tail.thenRunAsync(operation, executor);
         tail = result.handle((ignored, failure) -> {
            release(bytes, 1);
            return null;
         });
         return result.copy();
      } catch (RuntimeException | Error failure) {
         queuedBytes -= bytes;
         queuedOperations--;
         throw failure;
      }
   }

   private synchronized CompletableFuture<Void> enqueueCopied(
         long bytes, byte[] source, Consumer<byte[]> operation) {
      if (queuedOperations >= maxQueuedOperations || bytes > maxQueuedBytes - queuedBytes) {
         return failed("History I/O queue is full");
      }
      queuedBytes += bytes;
      queuedOperations++;
      try {
         byte[] ownedPayload = source.clone();
         CompletableFuture<Void> result = tail.thenRunAsync(() -> operation.accept(ownedPayload), executor);
         tail = result.handle((ignored, failure) -> {
            release(bytes, 1);
            return null;
         });
         return result.copy();
      } catch (RuntimeException | Error failure) {
         queuedBytes -= bytes;
         queuedOperations--;
         throw failure;
      }
   }

   private synchronized <T> CompletableFuture<T> enqueueRead(Supplier<T> operation) {
      if (queuedOperations >= maxQueuedOperations) {
         return CompletableFuture.failedFuture(new IOException("History I/O queue is full"));
      }
      queuedOperations++;
      try {
         CompletableFuture<T> result = tail.thenApplyAsync(ignored -> operation.get(), executor);
         tail = result.handle((ignored, failure) -> {
            release(0, 1);
            return null;
         });
         return result.copy();
      } catch (RuntimeException | Error failure) {
         queuedOperations--;
         throw failure;
      }
   }

   private synchronized void release(long bytes, int operations) {
      queuedBytes -= bytes;
      queuedOperations -= operations;
   }

   private void requirePublishedBatches(UUID ownerId, HistoryOrderIndex index) throws IOException {
      for (UUID batchId : index.undo()) requirePublishedBatch(ownerId, batchId);
      for (UUID batchId : index.redo()) requirePublishedBatch(ownerId, batchId);
   }

   private void requirePublishedBatch(UUID ownerId, UUID batchId) throws IOException {
      if (!Files.isRegularFile(batchFile(ownerId, batchId))) {
         throw new IOException("History index references an unpublished batch: " + batchId);
      }
   }

   private static void requireUniqueIds(HistoryOrderIndex index) throws IOException {
      Set<UUID> ids = new HashSet<>();
      for (UUID id : index.undo()) if (!ids.add(id)) throw new IOException("Duplicate history batch in index: " + id);
      for (UUID id : index.redo()) if (!ids.add(id)) throw new IOException("Duplicate history batch in index: " + id);
   }

   private void requireTotalCapacity(long additionalBytes) throws IOException {
      if (additionalBytes <= 0) return;
      long used = 0L;
      if (Files.isDirectory(root)) {
         try (Stream<Path> paths = Files.walk(root, 4)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) used = Math.addExact(used, Files.size(path));
         }
      }
      if (Math.addExact(used, additionalBytes) > maxTotalStoredBytes) {
         throw new IOException("History storage exceeds its global disk quota");
      }
   }

   private Path batchFile(UUID ownerId, UUID batchId) {
      Objects.requireNonNull(ownerId, "ownerId");
      Objects.requireNonNull(batchId, "batchId");
      return ownerDirectory(ownerId).resolve("batches").resolve(batchId + ".dat");
   }

   private Path indexFile(UUID ownerId) {
      Objects.requireNonNull(ownerId, "ownerId");
      return ownerDirectory(ownerId).resolve(INDEX_FILE);
   }

   private Path ownerDirectory(UUID ownerId) {
      return root.resolve(ownerId.toString());
   }

   private static <T> CompletableFuture<T> failed(String message) {
      return CompletableFuture.failedFuture(new IOException(message));
   }
}
