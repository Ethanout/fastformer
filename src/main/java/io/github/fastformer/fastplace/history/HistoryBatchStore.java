package io.github.fastformer.fastplace.history;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
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
   private static final String RETIRED_FILE = "retired.dat";

   private final Path root;
   private final Executor executor;
   private final int formatVersion;
   private final long maxBatchPayloadBytes;
   private final long maxTotalStoredBytes;
   private final long maxOwnerStoredBytes;
   private final long maxQueuedBytes;
   private final int maxQueuedOperations;
   private final int maxIndexEntries;
   private CompletableFuture<Void> tail = CompletableFuture.completedFuture(null);
   private long queuedBytes;
   private int queuedOperations;
   // Only the serialized I/O queue reads and changes these retry candidates.
   private final java.util.Map<UUID, Set<UUID>> pendingRetirements = new java.util.HashMap<>();

   public HistoryBatchStore(
         Path root,
         Executor executor,
         int formatVersion,
         long maxBatchPayloadBytes,
         long maxTotalStoredBytes,
         long maxQueuedBytes,
         int maxQueuedOperations,
         int maxIndexEntries) {
      this(root, executor, formatVersion, maxBatchPayloadBytes, maxTotalStoredBytes,
         maxQueuedBytes, maxQueuedOperations, maxIndexEntries, maxTotalStoredBytes);
   }

   public HistoryBatchStore(
         Path root, Executor executor, int formatVersion, long maxBatchPayloadBytes,
         long maxTotalStoredBytes, long maxQueuedBytes, int maxQueuedOperations,
         int maxIndexEntries, long maxOwnerStoredBytes) {
      this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
      this.executor = Objects.requireNonNull(executor, "executor");
      if (formatVersion < 1 || maxBatchPayloadBytes < 0 || maxTotalStoredBytes < ENVELOPE_BYTES
            || maxQueuedBytes < 0 || maxQueuedOperations < 1 || maxIndexEntries < 0
            || maxOwnerStoredBytes < ENVELOPE_BYTES) {
         throw new IllegalArgumentException("Invalid history batch store configuration");
      }
      this.formatVersion = formatVersion;
      this.maxBatchPayloadBytes = maxBatchPayloadBytes;
      this.maxTotalStoredBytes = maxTotalStoredBytes;
      this.maxOwnerStoredBytes = maxOwnerStoredBytes;
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
            requireCapacity(ownerId, payloadLength + ENVELOPE_BYTES);
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
            return Optional.of(HistoryEnvelopeFile.read(target, formatVersion, maxBatchPayloadBytes));
         } catch (IOException ex) {
            throw new UncheckedIOException(ex);
         }
      });
   }

   /** Checks a stored batch with a small streaming buffer, without loading its payload. */
   public CompletableFuture<Boolean> verifyBatch(UUID ownerId, UUID batchId) {
      Path target = batchFile(ownerId, batchId);
      return enqueueRead(() -> {
         if (!Files.isRegularFile(target)) return false;
         try {
            HistoryEnvelopeFile.verify(target, formatVersion, maxBatchPayloadBytes);
            return true;
         } catch (IOException failure) {
            throw new UncheckedIOException(failure);
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
            requireCapacity(ownerId, payload.length + ENVELOPE_BYTES - previousBytes);
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
            byte[] payload = HistoryEnvelopeFile.read(target, formatVersion, maximumBytes);
            HistoryOrderIndex index = HistoryOrderIndex.decode(payload, maxIndexEntries);
            requireUniqueIds(index);
            requirePublishedBatches(ownerId, index);
            return Optional.of(index);
         } catch (IOException ex) {
            throw new UncheckedIOException(ex);
         }
      });
   }

   /** Removes only caller-retired batches absent from the durable index; pending publications are not candidates. */
   public CompletableFuture<Integer> cleanupUnreferencedBatches(UUID ownerId, Set<UUID> retiredBatches) {
      Objects.requireNonNull(ownerId, "ownerId");
      Set<UUID> candidates = Set.copyOf(retiredBatches);
      return enqueueRead(() -> cleanupRetired(ownerId, candidates));
   }

   /** Removes unreferenced batch files older than {@code cutoff}. Referenced
    * batches are always retained, even when they exceed the retention age. */
   public CompletableFuture<Integer> cleanupExpiredBatches(UUID ownerId, Instant cutoff) {
      Objects.requireNonNull(ownerId, "ownerId");
      Objects.requireNonNull(cutoff, "cutoff");
      return enqueueRead(() -> cleanupExpired(ownerId, cutoff));
   }

   private int cleanupExpired(UUID ownerId, Instant cutoff) {
      Path indexPath = indexFile(ownerId);
      Path batches = ownerDirectory(ownerId).resolve("batches");
      try {
         Set<UUID> referenced = new HashSet<>();
         if (Files.isRegularFile(indexPath, LinkOption.NOFOLLOW_LINKS)) {
            byte[] payload = HistoryEnvelopeFile.read(indexPath, formatVersion,
               HistoryOrderIndex.maximumEncodedBytes(maxIndexEntries));
            HistoryOrderIndex index = HistoryOrderIndex.decode(payload, maxIndexEntries);
            requireUniqueIds(index);
            requirePublishedBatches(ownerId, index);
            referenced.addAll(index.undo());
            referenced.addAll(index.redo());
         }
         if (!Files.isDirectory(batches, LinkOption.NOFOLLOW_LINKS)) return 0;
         int deleted = 0;
         try (Stream<Path> paths = Files.list(batches)) {
            for (Path path : paths.toList()) {
               Optional<UUID> id = batchIdFromFile(path);
               if (id.isEmpty() || referenced.contains(id.get())) continue;
               FileTime modified = Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS);
               if (modified.toInstant().isBefore(cutoff) && Files.deleteIfExists(path)) deleted++;
            }
         }
         return deleted;
      } catch (IOException failure) {
         throw new UncheckedIOException(failure);
      }
   }

   private int cleanupRetired(UUID ownerId, Set<UUID> candidates) {
         Set<UUID> pending = pendingRetirements.computeIfAbsent(ownerId, ignored -> new HashSet<>());
         pending.addAll(readRetirements(ownerId));
         pending.addAll(candidates);
         if (pending.isEmpty()) {
            pendingRetirements.remove(ownerId);
            return 0;
         }
         int deleted = cleanupUnreferenced(ownerId, pending);
         try {
            Files.deleteIfExists(ownerDirectory(ownerId).resolve(RETIRED_FILE));
         } catch (IOException failure) {
            throw new UncheckedIOException(failure);
         }
         pendingRetirements.remove(ownerId);
         return deleted;
   }

   public CompletableFuture<CleanupSummary> resumeRetiredCleanup() {
      return enqueueRead(() -> {
         int owners = 0;
         int deleted = 0;
         int failed = 0;
         if (!Files.isDirectory(root)) return new CleanupSummary(0, 0, 0);
         try (var directories = Files.newDirectoryStream(root)) {
            for (Path directory : directories) {
               if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) continue;
               UUID owner;
               try {
                  owner = UUID.fromString(directory.getFileName().toString());
                  if (!owner.toString().equals(directory.getFileName().toString())) continue;
               } catch (IllegalArgumentException ignored) {
                  continue;
               }
               if (!Files.exists(directory.resolve(RETIRED_FILE))) continue;
               owners++;
               try {
                  deleted += cleanupRetired(owner, Set.of());
               } catch (UncheckedIOException failure) {
                  failed++;
                  com.mojang.logging.LogUtils.getLogger().warn("Could not resume history cleanup for {}", owner, failure);
               }
            }
         } catch (IOException failure) {
            throw new UncheckedIOException(failure);
         }
         return new CleanupSummary(owners, deleted, failed);
      });
   }

   public record CleanupSummary(int owners, int deletedBatches, int failedOwners) {}

   /** Persists cleanup intent before an ordering index can stop referencing the batches. */
   public CompletableFuture<Void> stageRetirements(UUID ownerId, Set<UUID> retiredBatches) {
      Objects.requireNonNull(ownerId, "ownerId");
      Set<UUID> supplied = Set.copyOf(retiredBatches);
      return enqueueRead(() -> {
         Set<UUID> combined = new HashSet<>(readRetirements(ownerId));
         combined.addAll(supplied);
         if (combined.isEmpty()) return null;
         if (combined.size() > maxIndexEntries) throw new UncheckedIOException(new IOException("History cleanup index exceeds its entry quota"));
         byte[] payload = HistoryOrderIndex.encode(new HistoryOrderIndex(combined.stream().sorted().toList(), java.util.List.of()));
         Path target = ownerDirectory(ownerId).resolve(RETIRED_FILE);
         try {
            long previousBytes = Files.isRegularFile(target) ? Files.size(target) : 0L;
            requireCapacity(ownerId, payload.length + ENVELOPE_BYTES - previousBytes);
            HistoryEnvelopeFile.write(target, formatVersion, payload);
            return null;
         } catch (IOException failure) {
            throw new UncheckedIOException(failure);
         }
      });
   }

   private Set<UUID> readRetirements(UUID ownerId) {
      Path path = ownerDirectory(ownerId).resolve(RETIRED_FILE);
      if (!Files.exists(path)) return Set.of();
      try {
         var index = HistoryOrderIndex.decode(HistoryEnvelopeFile.read(path, formatVersion,
            HistoryOrderIndex.maximumEncodedBytes(maxIndexEntries)), maxIndexEntries);
         if (!index.redo().isEmpty()) throw new IOException("Invalid history cleanup index");
         requireUniqueIds(index);
         return Set.copyOf(index.undo());
      } catch (IOException failure) {
         throw new UncheckedIOException(failure);
      }
   }

   private int cleanupUnreferenced(UUID ownerId, Set<UUID> candidates) {
      Path indexPath = indexFile(ownerId);
      try {
         if (!Files.isRegularFile(indexPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Cannot clean history batches without a durable index");
         }
         long maximumBytes = HistoryOrderIndex.maximumEncodedBytes(maxIndexEntries);
         if (Files.size(indexPath) > maximumBytes + ENVELOPE_BYTES) {
            throw new IOException("History index exceeds its entry quota");
         }
         byte[] payload = HistoryEnvelopeFile.read(indexPath, formatVersion, maximumBytes);
         HistoryOrderIndex index = HistoryOrderIndex.decode(payload, maxIndexEntries);
         requireUniqueIds(index);
         requirePublishedBatches(ownerId, index);

         Set<UUID> referenced = new HashSet<>(index.undo());
         referenced.addAll(index.redo());
         Path batches = ownerDirectory(ownerId).resolve("batches");
         if (!Files.isDirectory(batches, LinkOption.NOFOLLOW_LINKS)) return 0;
         int deleted = 0;
         try (Stream<Path> paths = Files.list(batches)) {
            for (Path path : paths.toList()) {
               Optional<UUID> batchId = batchIdFromFile(path);
               if (batchId.isPresent() && candidates.contains(batchId.get()) && !referenced.contains(batchId.get())) {
                  Files.delete(path);
                  deleted++;
               }
            }
         }
         return deleted;
      } catch (IOException ex) {
         throw new UncheckedIOException(ex);
      }
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

   private void requireCapacity(UUID ownerId, long additionalBytes) throws IOException {
      if (additionalBytes <= 0) return;
      if (Math.addExact(storedBytes(ownerDirectory(ownerId), 3), additionalBytes) > maxOwnerStoredBytes) {
         throw new IOException("Player history exceeds its total disk quota");
      }
      if (Math.addExact(storedBytes(root, 4), additionalBytes) > maxTotalStoredBytes) {
         throw new IOException("History storage exceeds its global disk quota");
      }
   }

   private static long storedBytes(Path directory, int depth) throws IOException {
      long used = 0L;
      if (Files.isDirectory(directory)) {
         try (Stream<Path> paths = Files.walk(directory, depth)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) used = Math.addExact(used, Files.size(path));
         }
      }
      return used;
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

   private static Optional<UUID> batchIdFromFile(Path path) {
      if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
      String name = path.getFileName().toString();
      if (!name.endsWith(".dat")) return Optional.empty();
      String candidate = name.substring(0, name.length() - ".dat".length());
      try {
         UUID id = UUID.fromString(candidate);
         return (id + ".dat").equals(name) ? Optional.of(id) : Optional.empty();
      } catch (IllegalArgumentException ignored) {
         return Optional.empty();
      }
   }

   private static <T> CompletableFuture<T> failed(String message) {
      return CompletableFuture.failedFuture(new IOException(message));
   }
}
