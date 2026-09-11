package io.github.fastformer.fastplace.history;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Stream;

/** Asynchronous, quota-bounded storage for one opaque history snapshot per owner. */
public final class HistoryStore {
   private static final String FILE_NAME = "history.dat";
   private static final long ENVELOPE_BYTES = Integer.BYTES * 3L + Long.BYTES;

   private final Path root;
   private final Executor executor;
   private final int formatVersion;
   private final long maxOwnerPayloadBytes;
   private final long maxTotalStoredBytes;
   private final Object fileLock = new Object();

   public HistoryStore(
         Path root,
         Executor executor,
         int formatVersion,
         long maxOwnerPayloadBytes,
         long maxTotalStoredBytes) {
      this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
      this.executor = Objects.requireNonNull(executor, "executor");
      if (formatVersion < 1) throw new IllegalArgumentException("Invalid history format version");
      if (maxOwnerPayloadBytes < 0 || maxTotalStoredBytes < ENVELOPE_BYTES) {
         throw new IllegalArgumentException("Invalid history storage quota");
      }
      this.formatVersion = formatVersion;
      this.maxOwnerPayloadBytes = maxOwnerPayloadBytes;
      this.maxTotalStoredBytes = maxTotalStoredBytes;
   }

   public CompletableFuture<Void> save(UUID ownerId, byte[] payload) {
      Objects.requireNonNull(ownerId, "ownerId");
      Objects.requireNonNull(payload, "payload");
      byte[] ownedPayload = payload.clone();
      if (ownedPayload.length > maxOwnerPayloadBytes) {
         return CompletableFuture.failedFuture(new IOException("Player history exceeds its disk quota"));
      }
      return CompletableFuture.runAsync(() -> write(ownerId, ownedPayload), executor);
   }

   public CompletableFuture<Optional<byte[]>> load(UUID ownerId) {
      Objects.requireNonNull(ownerId, "ownerId");
      return CompletableFuture.supplyAsync(() -> read(ownerId), executor);
   }

   private void write(UUID ownerId, byte[] payload) {
      synchronized (fileLock) {
         Path target = ownerFile(ownerId);
         try {
            long replacementBytes = Math.addExact(payload.length, ENVELOPE_BYTES);
            long previousBytes = Files.isRegularFile(target) ? Files.size(target) : 0L;
            long resultingBytes = Math.addExact(Math.subtractExact(storedBytes(), previousBytes), replacementBytes);
            if (resultingBytes > maxTotalStoredBytes) {
               throw new IOException("History storage exceeds its global disk quota");
            }
            HistoryEnvelopeFile.write(target, formatVersion, payload);
         } catch (IOException ex) {
            throw new UncheckedIOException(ex);
         } catch (ArithmeticException ex) {
            throw new IllegalStateException("History storage size overflow", ex);
         }
      }
   }

   private Optional<byte[]> read(UUID ownerId) {
      synchronized (fileLock) {
         Path target = ownerFile(ownerId);
         if (!Files.isRegularFile(target)) return Optional.empty();
         try {
            long maximumFileBytes = Math.addExact(maxOwnerPayloadBytes, ENVELOPE_BYTES);
            if (Files.size(target) > maximumFileBytes) {
               throw new IOException("Player history exceeds its disk quota");
            }
            return Optional.of(VersionedHistoryEnvelope.decode(Files.readAllBytes(target), formatVersion).payload());
         } catch (IOException ex) {
            throw new UncheckedIOException(ex);
         } catch (ArithmeticException ex) {
            throw new IllegalStateException("History storage size overflow", ex);
         }
      }
   }

   private long storedBytes() throws IOException {
      if (!Files.isDirectory(root)) return 0L;
      try (Stream<Path> files = Files.walk(root, 2)) {
         long total = 0L;
         for (Path file : files.filter(path -> path.getFileName().toString().equals(FILE_NAME)).toList()) {
            if (Files.isRegularFile(file)) total = Math.addExact(total, Files.size(file));
         }
         return total;
      } catch (ArithmeticException ex) {
         throw new IOException("History storage size overflow", ex);
      }
   }

   private Path ownerFile(UUID ownerId) {
      return root.resolve(ownerId.toString()).resolve(FILE_NAME);
   }
}
