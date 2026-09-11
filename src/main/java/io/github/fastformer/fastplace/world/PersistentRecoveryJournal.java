package io.github.fastformer.fastplace.world;

import com.mojang.logging.LogUtils;
import io.github.fastformer.fastplace.PlacementUpdateMode;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.Executor;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.common.IOUtilities;
import org.slf4j.Logger;

/**
 * A compressed write-ahead journal and final-state correction for one world operation.
 *
 * <p>The complete original state is fsynced before the first block write.
 * After writes settle, a SHA-256-bound delta records only actual states that
 * differ from prediction. A successful operation atomically renames the base
 * journal to a committed marker; startup resolves every remaining family
 * before FastFormer accepts another write.</p>
 */
public final class PersistentRecoveryJournal {
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final int VERSION = 2;
   private static final long MAX_DECOMPRESSED_BYTES = 512L * 1024L * 1024L;
   private static final long MINIMUM_HEAP_RESERVE = 256L * 1024L * 1024L;
   private static final String DIRECTORY = "fastformer-recovery";
   private static final AtomicLong SEQUENCE = new AtomicLong(System.currentTimeMillis() * 1000L);
   private static final Executor IO_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
      Thread thread = new Thread(runnable, "FastFormer recovery journal");
      thread.setDaemon(true);
      return thread;
   });
   private static volatile boolean startupRecoveryBlocked;
   private static final Map<Path, ResourceKey<Level>> COMMITTED = new ConcurrentHashMap<>();
   private static final java.util.Set<Path> CLEANUP_PENDING = ConcurrentHashMap.newKeySet();

   private static final int SEGMENTED_LIMIT = 65_536;
   private static final int FIRST_SEGMENT_CELLS = 256;
   private static final int NEXT_SEGMENT_CELLS = 4_096;
   private static final String MANIFEST_FILE = "manifest.dat";
   private static final String SEAL_FILE = "seal.done";
   private static final String CORRECTION_FILE = "correction.delta";
   private static final String FIRST_SEGMENT_FILE = "segment-000000.dat";

   private final Path file;
   private final ResourceKey<Level> dimension;
   private final UUID operationId;
   private final long preparedDecodedBytes;
   private final boolean segmented;
   private int nextSegmentIndex;
   private boolean committed;
   private volatile boolean finalAfterPrepared;
   private volatile boolean correctionRequired;
   private List<ReversibleBlockSnapshot> cachedPredictedAfter = List.of();
   private boolean predictedAfterCacheComplete;

   PersistentRecoveryJournal(Path file) {
      this(file, null, null, MAX_DECOMPRESSED_BYTES, Files.isDirectory(file));
   }

   PersistentRecoveryJournal(Path file, ResourceKey<Level> dimension) {
      this(file, dimension, null, MAX_DECOMPRESSED_BYTES, Files.isDirectory(file));
   }

   private PersistentRecoveryJournal(
      Path file, ResourceKey<Level> dimension, UUID operationId, long preparedDecodedBytes
   ) {
      this(file, dimension, operationId, preparedDecodedBytes, Files.isDirectory(file));
   }

   private PersistentRecoveryJournal(
      Path file,
      ResourceKey<Level> dimension,
      UUID operationId,
      long preparedDecodedBytes,
      boolean segmented
   ) {
      this.file = file;
      this.dimension = dimension;
      this.segmented = segmented;
      UUID resolvedOperation = operationId;
      int nextSegment = 0;
      if (segmented && file != null && Files.isDirectory(file)) {
         nextSegment = countExistingSegments(file);
         if (resolvedOperation == null) {
            resolvedOperation = readManifestOperationId(file);
         }
      }
      this.operationId = resolvedOperation;
      this.preparedDecodedBytes = Math.max(1L, Math.min(MAX_DECOMPRESSED_BYTES, preparedDecodedBytes));
      this.nextSegmentIndex = nextSegment;
   }

   public static int segmentBatchSize(int journaledCount, int remaining) {
      if (remaining <= 0) {
         return 0;
      }
      return Math.min(remaining, segmentCapacity(journaledCount));
   }

   public static int segmentCapacity(int journaledCount) {
      return journaledCount <= 0 ? FIRST_SEGMENT_CELLS : NEXT_SEGMENT_CELLS;
   }

   public UUID operationId() {
      return this.operationId;
   }

   public static Optional<PersistentRecoveryJournal> begin(
      ServerPlayer player,
      ResourceKey<Level> dimension,
      Collection<ReversibleBlockSnapshot> before,
      Collection<ReversibleBlockSnapshot> after
   ) {
      if (player == null || player.getServer() == null) {
         return Optional.empty();
      }
      return begin(player.getServer(), player.getUUID(), dimension, before, after);
   }

   public static Optional<PersistentRecoveryJournal> begin(
      MinecraftServer server,
      UUID owner,
      ResourceKey<Level> dimension,
      Collection<ReversibleBlockSnapshot> before,
      Collection<ReversibleBlockSnapshot> after
   ) {
      return begin(server, owner, dimension, before, after, null);
   }

   public static Optional<PersistentRecoveryJournal> begin(
      MinecraftServer server,
      UUID owner,
      ResourceKey<Level> dimension,
      Collection<ReversibleBlockSnapshot> before,
      Collection<ReversibleBlockSnapshot> after,
      UUID operationId
   ) {
      if (server == null || owner == null || dimension == null || before == null || before.isEmpty() || after == null) {
         return Optional.empty();
      }
      if (startupRecoveryBlocked) {
         return Optional.empty();
      }
      Path directory = journalDirectory(server);
      if (hasOwnerJournal(directory, owner)) {
         LOGGER.error("Refusing to replace an existing FastFormer recovery journal for {}", owner);
         startupRecoveryBlocked = true;
         return Optional.empty();
      }
      long sequence = SEQUENCE.updateAndGet(previous -> Math.max(previous + 1L, System.currentTimeMillis() * 1000L));
      UUID resolvedOperation = operationId == null ? UUID.randomUUID() : operationId;
      Path operationDirectory = directory.resolve(String.format("%020d-%s-%s", sequence, owner, resolvedOperation));
      try {
         return Optional.of(writeSegmentedJournal(operationDirectory, owner, resolvedOperation, dimension, before, after));
      } catch (IOException | RuntimeException exception) {
         LOGGER.error("Could not create FastFormer recovery journal for {}", owner, exception);
         deleteDirectoryQuietly(operationDirectory);
         return Optional.empty();
      }
   }

   /** Atomically marks a successful operation committed; cleanup waits for a durable level save. */
   synchronized boolean complete() {
      if (this.committed) {
         return true;
      }
      if (this.segmented) {
         return completeSegmented();
      }
      Path committed = committedPath(this.file);
      try {
         if (!Files.exists(this.file)) {
            if (!Files.exists(committed)) {
               LOGGER.error("FastFormer recovery journal disappeared before commit: {}", this.file);
               return false;
            }
            this.committed = true;
            if (this.dimension != null) {
               COMMITTED.put(committed, this.dimension);
            }
            return true;
         }
         if (Files.exists(committed)) {
            LOGGER.error(
               "FastFormer found both prepared and committed forms of the same recovery journal: {}",
               this.file
            );
            return false;
         }
         atomicMove(this.file, committed);
      } catch (IOException | RuntimeException exception) {
         LOGGER.error("Could not commit FastFormer recovery journal {}", this.file, exception);
         return false;
      }
      this.committed = true;
      if (this.dimension != null) {
         COMMITTED.put(committed, this.dimension);
      }
      return true;
   }

   private boolean completeSegmented() {
      Path seal = this.file.resolve(SEAL_FILE);
      try {
         if (Files.exists(seal)) {
            markSegmentedCommitted();
            return true;
         }
         if (!Files.isDirectory(this.file)) {
            LOGGER.error("FastFormer segmented journal disappeared before seal: {}", this.file);
            return false;
         }
         RecoveryJournalManifest.Manifest manifest = RecoveryJournalManifest.read(this.file.resolve(MANIFEST_FILE));
         var segments = RecoveryJournalSegments.inspectUnsealed(
            this.file, manifest.operationId(), manifest.segmentLimit()
         );
         RecoveryJournalSeal.write(
            seal, manifest.operationId(), segments.count(), segments.digest()
         );
      } catch (IOException | RuntimeException exception) {
         LOGGER.error("Could not seal FastFormer recovery journal {}", this.file, exception);
         return false;
      }
      markSegmentedCommitted();
      return true;
   }

   private void markSegmentedCommitted() {
      this.committed = true;
      if (this.dimension != null) {
         COMMITTED.put(this.file, this.dimension);
      }
   }

   synchronized boolean completeFinalized() {
      if (!this.finalAfterPrepared) {
         LOGGER.error("Refusing to commit FastFormer journal before final after-state preparation: {}", this.file);
         return false;
      }
      if (this.correctionRequired && !Files.exists(correctionPath(this.file))) {
         LOGGER.error("Refusing to commit FastFormer journal without its required correction: {}", this.file);
         return false;
      }
      return this.complete();
   }

   /** Removes a prepared journal for a task that was cancelled before any world write. */
   public synchronized boolean discardUnused() {
      try {
         if (this.committed || Files.exists(committedMarker(this.file))) {
            return false;
         }
         if (this.segmented) {
            deleteDirectoryQuietly(this.file);
            return !Files.exists(this.file);
         }
         Files.deleteIfExists(this.file);
         Files.deleteIfExists(correctionPath(this.file));
         return true;
      } catch (IOException | RuntimeException exception) {
         LOGGER.error("Could not discard unused FastFormer journal {}", this.file, exception);
         return false;
      }
   }

   /**
    * Resolves a journal after the world has already been restored to before.
    * If the file disappeared unexpectedly, a forced world save is the only
    * remaining durable proof that it is safe to release the recovery lock.
    */
   synchronized boolean resolveAfterRollback(MinecraftServer server) {
      if (!saveDurably(server)) {
         LOGGER.error("Could not durably save the world after FastFormer rollback: {}", this.file);
         return false;
      }
      Path committed = committedMarker(this.file);
      try {
         if (this.segmented) {
            deleteDirectoryQuietly(this.file);
         } else {
            Files.deleteIfExists(this.file);
            Files.deleteIfExists(committed);
            Files.deleteIfExists(correctionPath(this.file));
         }
         COMMITTED.remove(this.segmented ? this.file : committed);
      } catch (IOException | RuntimeException exception) {
         LOGGER.error("Could not remove FastFormer journal after durable rollback: {}", this.file, exception);
         return false;
      }
      this.committed = true;
      return true;
   }

   /** Deletes committed markers only after NeoForge's asynchronous chunk IO is durably complete. */
   public static void onLevelSaved(ServerLevel level) {
      Path currentDirectory = journalDirectory(level.getServer()).toAbsolutePath().normalize();
      ResourceKey<Level> savedDimension = level.dimension();
      List<Path> ready = COMMITTED.entrySet().stream()
         .filter(entry -> entry.getValue().equals(savedDimension))
         .filter(entry -> belongsToDirectory(entry.getKey(), currentDirectory))
         .map(Map.Entry::getKey)
         .filter(CLEANUP_PENDING::add)
         .toList();
      if (ready.isEmpty()) {
         return;
      }
      IO_EXECUTOR.execute(() -> {
         try {
            IOUtilities.waitUntilIOWorkerComplete();
            for (Path path : ready) {
               try {
                  if (Files.isDirectory(path)) {
                     deleteDirectoryQuietly(path);
                     COMMITTED.remove(path, savedDimension);
                     continue;
                  }
                  if (Files.exists(preparedPath(path))) {
                     LOGGER.error("Refusing to delete committed journal because its prepared form also exists: {}", path);
                     continue;
                  }
                  Files.deleteIfExists(path);
                  Files.deleteIfExists(correctionPath(path));
                  COMMITTED.remove(path, savedDimension);
               } catch (IOException | RuntimeException exception) {
                  LOGGER.warn("Committed FastFormer journal will be replayed on next startup: {}", path, exception);
               }
            }
         } catch (RuntimeException exception) {
            LOGGER.error("Could not confirm durable chunk IO for FastFormer committed journals", exception);
         } finally {
            CLEANUP_PENDING.removeAll(ready);
         }
      });
   }

   CompletableFuture<Boolean> finalizeAfter(
      Map<BlockPos, ReversibleBlockSnapshot> actualAfter
   ) {
      if (actualAfter == null || actualAfter.isEmpty()) {
         this.clearPredictedAfterCache();
         this.finalAfterPrepared = true;
         this.correctionRequired = false;
         return CompletableFuture.completedFuture(true);
      }
      if (this.matchesCachedPredictedAfter(actualAfter)
         && Files.exists(this.file)
         && !Files.exists(committedMarker(this.file))
         && !Files.exists(correctionPath(this.file))) {
         this.clearPredictedAfterCache();
         this.finalAfterPrepared = true;
         this.correctionRequired = false;
         return CompletableFuture.completedFuture(true);
      }
      this.clearPredictedAfterCache();
      return CompletableFuture.supplyAsync(() -> this.writeCorrections(actualAfter), IO_EXECUTOR);
   }

   private boolean matchesCachedPredictedAfter(Map<BlockPos, ReversibleBlockSnapshot> actualAfter) {
      if (!this.predictedAfterCacheComplete) {
         return false;
      }
      int matched = 0;
      for (ReversibleBlockSnapshot predicted : this.cachedPredictedAfter) {
         ReversibleBlockSnapshot actual = actualAfter.get(predicted.pos());
         if (actual == null) {
            continue;
         }
         matched++;
         if (!predicted.sameContents(actual)) {
            return false;
         }
      }
      return matched == actualAfter.size();
   }

   void rememberInitialPredictedAfter(List<ReversibleBlockSnapshot> predictedAfter) {
      this.cachedPredictedAfter = predictedAfter;
      this.predictedAfterCacheComplete = true;
   }

   void rememberAppendedPredictedAfter(List<ReversibleBlockSnapshot> predictedAfter) {
      if (!this.predictedAfterCacheComplete
         || this.cachedPredictedAfter.size() + predictedAfter.size() > FIRST_SEGMENT_CELLS) {
         this.clearPredictedAfterCache();
         return;
      }
      List<ReversibleBlockSnapshot> combined = new ArrayList<>(
         this.cachedPredictedAfter.size() + predictedAfter.size()
      );
      combined.addAll(this.cachedPredictedAfter);
      combined.addAll(predictedAfter);
      this.cachedPredictedAfter = List.copyOf(combined);
   }

   private void clearPredictedAfterCache() {
      this.cachedPredictedAfter = List.of();
      this.predictedAfterCacheComplete = false;
   }

   int cachedPredictedAfterCount() {
      return this.predictedAfterCacheComplete ? this.cachedPredictedAfter.size() : 0;
   }

   void deleteOrphanCorrection() {
      try {
         Path committed = committedMarker(this.file);
         if (!Files.exists(this.file) && !Files.exists(committed)) {
            Files.deleteIfExists(correctionPath(this.file));
         }
      } catch (IOException | RuntimeException exception) {
         LOGGER.warn("Could not delete orphan FastFormer journal correction for {}", this.file, exception);
      }
   }

   /** Runs synchronously during ServerStartedEvent, before player operations. */
   public static boolean recoverAll(MinecraftServer server) {
      return recoverAll(server, () -> saveDurably(server));
   }

   static boolean recoverAll(MinecraftServer server, BooleanSupplier durableSave) {
      startupRecoveryBlocked = false;
      COMMITTED.clear();
      CLEANUP_PENDING.clear();
      Path directory = journalDirectory(server);
      if (!Files.exists(directory)) {
         return true;
      }
      boolean success = true;
      List<Path> recovered = new ArrayList<>();
      try (var files = Files.list(directory)) {
         List<Path> entries = files.toList();
         if (!validateSegmentDirectories(entries)) {
            success = false;
         }
         java.util.Set<String> conflictingForms = conflictingJournalForms(entries);
         if (!conflictingForms.isEmpty()) {
            success = false;
            for (String stem : conflictingForms) {
               LOGGER.error("FastFormer found both .dat and .done recovery journals for {}; both were left untouched", stem);
            }
         }
         for (Path path : entries) {
            String name = path.getFileName().toString();
            if (name.contains(".tmp-")) {
               try {
                  Files.deleteIfExists(path);
                } catch (IOException | RuntimeException exception) {
                   success = false;
                   LOGGER.error("Could not delete incomplete FastFormer journal temp file {}", path, exception);
                }
            }
         }
         for (Path path : entries) {
            String name = path.getFileName().toString();
            if (name.endsWith(".delta")) {
               Path prepared = path.resolveSibling(name.substring(0, name.length() - 6) + ".dat");
               Path committed = path.resolveSibling(name.substring(0, name.length() - 6) + ".done");
               if (!Files.exists(prepared) && !Files.exists(committed)) {
                  try {
                     Files.deleteIfExists(path);
                  } catch (IOException | RuntimeException exception) {
                     success = false;
                     LOGGER.error("Could not delete orphan FastFormer journal correction {}", path, exception);
                  }
               }
            }
         }
          List<Path> prepared = recoveryOrder(entries.stream()
             .filter(path -> path.getFileName().toString().endsWith(".dat")
                || Files.isDirectory(path) && Files.exists(path.resolve("manifest.dat")) && !Files.exists(path.resolve("seal.done")))
             .filter(path -> !conflictingForms.contains(journalStem(path)))
             .toList());
          List<Path> committed = entries.stream()
             .filter(path -> path.getFileName().toString().endsWith(".done")
                || Files.isDirectory(path) && Files.exists(path.resolve("seal.done")))
             .filter(path -> !conflictingForms.contains(journalStem(path)))
            .sorted(Comparator.comparing(path -> path.getFileName().toString()))
            .toList();
         for (Path path : prepared) {
            if (recoverOne(server, path, false)) {
               recovered.add(path);
            } else {
               success = false;
            }
         }
         for (Path path : committed) {
            if (recoverOne(server, path, true)) {
               recovered.add(path);
            } else {
               success = false;
            }
         }
      } catch (IOException | RuntimeException exception) {
         LOGGER.error("Could not scan FastFormer recovery journals", exception);
         success = false;
      } catch (OutOfMemoryError error) {
         LOGGER.error("FastFormer recovery journal scan exceeded the safe JVM heap budget", error);
         success = false;
      }
      if (!recovered.isEmpty()) {
         // Do not remove the write-ahead log until the restored chunks are
         // durably flushed. A second crash during startup can then retry.
         if (!durableSave.getAsBoolean()) {
            LOGGER.error("FastFormer restored startup journals but the world save did not complete");
            success = false;
         } else {
            for (Path path : recovered) {
               try {
                   boolean segmented = Files.isDirectory(path);
                   if (segmented) {
                      try (var children = Files.list(path)) {
                         for (Path child : children.toList()) {
                            Files.delete(child);
                         }
                      }
                   }
                   Files.deleteIfExists(path);
                   if (!segmented) Files.deleteIfExists(correctionPath(path));
                } catch (IOException | RuntimeException exception) {
                   LOGGER.error("Recovered FastFormer journal will be retried next startup: {}", path, exception);
                   success = false;
                }
            }
         }
      }
      startupRecoveryBlocked = !success;
      return success;
   }

   /**
    * Checks sealed segment files before startup restores their world contents.
    */
   static boolean validateSegmentDirectories(Collection<Path> entries) {
      if (entries == null) {
         return false;
      }
      boolean valid = true;
      for (Path directory : entries) {
         if (!Files.isDirectory(directory)) {
            continue;
         }
         boolean segmentedMarker = Files.exists(directory.resolve("manifest.dat"))
            || Files.exists(directory.resolve("seal.done"));
         if (!segmentedMarker) {
            try (var stream = Files.list(directory)) {
               segmentedMarker = stream.anyMatch(path -> path.getFileName().toString().startsWith("segment-"));
            } catch (IOException | RuntimeException exception) {
               valid = false;
               LOGGER.error("Could not inspect segmented recovery operation: {}", directory, exception);
               continue;
            }
         }
         if (!segmentedMarker) {
            continue;
         }
         try {
            if (!Files.exists(directory.resolve("manifest.dat"))) {
               throw new IOException("Segmented recovery operation has no manifest: " + directory);
            }
            RecoveryJournalManifest.Manifest manifest = RecoveryJournalManifest.read(directory.resolve("manifest.dat"));
            Path seal = directory.resolve("seal.done");
            if (!Files.exists(seal)) {
               var segments = RecoveryJournalSegments.inspectUnsealed(directory, manifest.operationId(), manifest.segmentLimit());
               ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(manifest.dimension()));
               for (int index = 0; index < segments.count(); index++) {
                  validateHeader(RecoveryJournalSegments.read(directory, manifest.operationId(), index).payload(), dimension);
               }
               continue;
            }
            RecoveryJournalSeal.Seal sealData = RecoveryJournalSeal.read(seal, manifest.operationId());
            if (sealData.segmentCount() > manifest.segmentLimit()) {
               throw new IOException("Segmented recovery operation exceeds manifest segment limit");
            }
            RecoveryJournalSegments.SegmentSet segments = RecoveryJournalSegments.inspectComplete(
               directory, manifest.operationId(), sealData.segmentCount()
            );
            if (segments.digest() != sealData.digest()) {
               throw new IOException("Segmented recovery operation digest mismatch");
            }
            ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(manifest.dimension()));
            for (int index = 0; index < segments.count(); index++) {
               validateHeader(RecoveryJournalSegments.read(directory, manifest.operationId(), index).payload(), dimension);
            }
         } catch (IOException | RuntimeException exception) {
            valid = false;
            LOGGER.error("FastFormer found an invalid segmented recovery operation: {}", directory, exception);
         }
      }
      return valid;
   }

   public static boolean writesAllowed() {
      return !startupRecoveryBlocked;
   }

   static void blockNewWrites() {
      startupRecoveryBlocked = true;
   }

   static void resetWriteGateForTest() {
      startupRecoveryBlocked = false;
   }

   public static Executor executor() {
      return IO_EXECUTOR;
   }

   /** Waits until every journal action queued before this call has finished. */
   public static boolean awaitIoIdle() {
      try {
         CompletableFuture.runAsync(() -> {
         }, IO_EXECUTOR).join();
         return true;
      } catch (RuntimeException | OutOfMemoryError exception) {
         LOGGER.error("FastFormer recovery journal queue did not reach an idle boundary", exception);
         startupRecoveryBlocked = true;
         return false;
      }
   }

   static List<Path> recoveryOrder(Collection<Path> files) {
      return files.stream()
         .sorted(Comparator.comparing((Path path) -> path.getFileName().toString()).reversed())
         .toList();
   }

   static boolean belongsToDirectory(Path file, Path directory) {
      if (file == null || directory == null) {
         return false;
      }
      Path parent = file.toAbsolutePath().normalize().getParent();
      return directory.toAbsolutePath().normalize().equals(parent);
   }

   static java.util.Set<String> conflictingJournalForms(Collection<Path> files) {
      java.util.Set<String> prepared = new HashSet<>();
      java.util.Set<String> committed = new HashSet<>();
      for (Path path : files) {
         String name = path.getFileName().toString();
         if (name.endsWith(".dat")) {
            prepared.add(name.substring(0, name.length() - 4));
         } else if (name.endsWith(".done")) {
            committed.add(name.substring(0, name.length() - 5));
         }
      }
      prepared.retainAll(committed);
      return java.util.Set.copyOf(prepared);
   }

   private static String journalStem(Path path) {
      String name = path.getFileName().toString();
      int extension = name.lastIndexOf('.');
      return extension < 0 ? name : name.substring(0, extension);
   }

   private static boolean saveDurably(MinecraftServer server) {
      if (server == null) {
         return false;
      }
      try {
         if (!server.saveAllChunks(true, true, true)) {
            return false;
         }
         IOUtilities.waitUntilIOWorkerComplete();
         return true;
      } catch (RuntimeException exception) {
         LOGGER.error("FastFormer could not durably flush restored chunks", exception);
         return false;
      }
   }

   static StartupRecoveryAction startupRecoveryAction(boolean matchesBefore, boolean matchesAfter) {
      if (matchesBefore) {
         return StartupRecoveryAction.ALREADY_RESTORED;
      }
      return matchesAfter ? StartupRecoveryAction.RESTORE : StartupRecoveryAction.PRESERVE_EXTERNAL;
   }

   static boolean recoverOne(MinecraftServer server, Path file, boolean committed) {
      if (Files.isDirectory(file)) {
         return recoverSealedSegments(server, file);
      }
      UUID operationId = null;
      try {
         CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.create(recoveryDecodeLimit()));
         if (root.getInt("Version") != VERSION) {
            throw new IOException("Unsupported journal version " + root.getInt("Version"));
         }
         int currentDataVersion = SharedConstants.getCurrentVersion().getDataVersion().getVersion();
         if (NbtUtils.getDataVersion(root, -1) != currentDataVersion) {
            throw new IOException("Journal was written by a different Minecraft data version");
         }
          ResourceLocation dimensionId = ResourceLocation.tryParse(root.getString("Dimension"));
         if (dimensionId == null) {
            throw new IOException("Invalid journal dimension");
         }
          ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, dimensionId);
         operationId = readOperationId(root);
         ServerLevel level = server.getLevel(dimension);
         if (level == null) {
            throw new IOException("Journal dimension is not loaded: " + dimensionId);
         }
          DecodedJournal decoded = decode(level, root);
          DecodedCorrections corrections = decodeCorrections(level, file, dimension);
          if (corrections != null) {
             validateCorrectionPositions(decoded.positions(), corrections.positions());
          }
          int correctionIndex = 0;
          int preserved = 0;
          for (int i = 0; i < decoded.size(); i++) {
             JournalPair pair = decoded.pair(i);
             if (corrections != null
                && correctionIndex < corrections.size()
                && corrections.position(correctionIndex) == pair.after().pos().asLong()) {
                pair = new JournalPair(pair.before(), corrections.snapshot(correctionIndex));
                correctionIndex++;
             }
            ReversibleBlockSnapshot target = committed ? pair.after() : pair.before();
            ReversibleBlockSnapshot source = committed ? pair.before() : pair.after();
            StartupRecoveryAction action = startupRecoveryAction(
               target.matches(level, target.pos()),
               source.matches(level, target.pos())
            );
            if (action == StartupRecoveryAction.PRESERVE_EXTERNAL) {
               preserved++;
            } else if (action == StartupRecoveryAction.RESTORE
               && !target.restore(level, PlacementUpdateMode.CLIENT_ONLY.flags())) {
               throw new IOException("Could not restore " + target.pos().toShortString());
          }
         }
         if (corrections != null && correctionIndex != corrections.size()) {
            throw new IOException("Recovery correction positions are not aligned with the base journal");
         }
         LOGGER.warn(
             "{} {} positions from FastFormer journal {} (operation={}); preserved {} external changes",
             committed ? "Replayed" : "Rolled back", decoded.size(), file.getFileName(), operationId, preserved
         );
         return true;
      } catch (IOException | RuntimeException exception) {
          LOGGER.error(
             "FastFormer left recovery journal {} untouched because recovery was not safe (operation={})",
             file,
             operationId,
             exception
          );
         return false;
      } catch (OutOfMemoryError error) {
         LOGGER.error(
            "FastFormer left recovery journal {} untouched because the JVM heap was insufficient (operation={})",
            file,
            operationId,
            error
         );
         return false;
      }
   }

   private static boolean recoverSealedSegments(MinecraftServer server, Path directory) {
      try {
         if (!validateSegmentDirectories(List.of(directory))) return false;
         var manifest = RecoveryJournalManifest.read(directory.resolve("manifest.dat"));
         boolean sealed = Files.exists(directory.resolve("seal.done"));
         var segments = sealed
            ? RecoveryJournalSegments.inspectComplete(directory, manifest.operationId(),
               RecoveryJournalSeal.read(directory.resolve("seal.done"), manifest.operationId()).segmentCount())
            : RecoveryJournalSegments.inspectUnsealed(directory, manifest.operationId(), manifest.segmentLimit());
         if (sealed) {
            var seal = RecoveryJournalSeal.read(directory.resolve("seal.done"), manifest.operationId());
            if (segments.digest() != seal.digest()) throw new IOException("Segmented recovery journal digest mismatch");
         }
         ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(manifest.dimension()));
         ServerLevel level = server.getLevel(dimension);
         if (level == null) throw new IOException("Journal dimension is not loaded: " + manifest.dimension());
         DecodedCorrections corrections = sealed
            ? decodeCorrections(
               level,
               directory.resolve(FIRST_SEGMENT_FILE),
               directory.resolve(CORRECTION_FILE),
               dimension
            )
            : null;
         // Validate all decoded payloads before the first world mutation, but
         // release each decoded segment before reading the next one.
         int validatedCorrections = 0;
         for (int segmentIndex = 0; segmentIndex < segments.count(); segmentIndex++) {
            var segment = RecoveryJournalSegments.read(directory, manifest.operationId(), segmentIndex);
            DecodedJournal journal = decode(level, segment.payload());
            // Reject invalid palette references before the first world write.
            for (int index = 0; index < journal.size(); index++) {
               JournalPair pair = journal.pair(index);
               if (corrections != null
                  && validatedCorrections < corrections.size()
                  && corrections.position(validatedCorrections) == pair.after().pos().asLong()) {
                  validatedCorrections++;
               }
            }
         }
         if (corrections != null && validatedCorrections != corrections.size()) {
            throw new IOException("Recovery correction positions are not aligned with the segmented journal");
         }
         int correctionIndex = 0;
         int restored = 0;
         int preserved = 0;
         for (int segmentOffset = 0; segmentOffset < segments.count(); segmentOffset++) {
            int segmentIndex = sealed ? segmentOffset : segments.count() - 1 - segmentOffset;
            var segment = RecoveryJournalSegments.read(directory, manifest.operationId(), segmentIndex);
            var journal = decode(level, segment.payload());
            for (int cell = 0; cell < journal.size(); cell++) {
               int index = sealed ? cell : journal.size() - 1 - cell;
               JournalPair pair = journal.pair(index);
               if (corrections != null
                  && correctionIndex < corrections.size()
                  && corrections.position(correctionIndex) == pair.after().pos().asLong()) {
                  pair = new JournalPair(pair.before(), corrections.snapshot(correctionIndex));
                  correctionIndex++;
               }
               var target = sealed ? pair.after() : pair.before();
               var source = sealed ? pair.before() : pair.after();
               StartupRecoveryAction action = startupRecoveryAction(
                  target.matches(level, target.pos()), source.matches(level, source.pos())
               );
               if (action == StartupRecoveryAction.PRESERVE_EXTERNAL) {
                  preserved++;
               } else if (action == StartupRecoveryAction.RESTORE) {
                  if (!target.restore(level, PlacementUpdateMode.CLIENT_ONLY.flags())) {
                     throw new IOException("Could not restore " + target.pos().toShortString());
                  }
                  restored++;
               }
            }
         }
         if (corrections != null && correctionIndex != corrections.size()) {
            throw new IOException("Recovery correction positions are not aligned with the segmented journal");
         }
         LOGGER.warn("{} {} positions from segmented journal {} (operation={}); preserved {} external changes",
            sealed ? "Replayed" : "Rolled back", restored, directory, manifest.operationId(), preserved);
         return true;
      } catch (IOException | RuntimeException | OutOfMemoryError exception) {
         LOGGER.error("Could not restore sealed segmented journal {}; files remain for retry", directory, exception);
         return false;
      }
   }

   private boolean writeCorrections(
      Map<BlockPos, ReversibleBlockSnapshot> actualAfter
   ) {
      Path correction = correctionPath(this.file);
      try {
         if (!Files.exists(this.file) || Files.exists(committedMarker(this.file))) {
            return false;
         }
         Path baseFile = this.segmented ? this.file.resolve(FIRST_SEGMENT_FILE) : this.file;
         PaletteLayout layout;
         if (this.segmented) {
            layout = segmentedCorrectionLayout(actualAfter);
         } else {
            CompoundTag root = NbtIo.readCompressed(this.file, NbtAccounter.create(correctionDecodeLimit(this.preparedDecodedBytes)));
            validateHeader(root, this.dimension);
            layout = correctionLayout(root, actualAfter);
         }
         if (layout.positions().length == 0) {
            Files.deleteIfExists(correction);
            this.correctionRequired = false;
            this.finalAfterPrepared = true;
            return true;
         }
         CompoundTag correctionRoot = new CompoundTag();
         correctionRoot.putInt("Version", VERSION);
          correctionRoot.putString("Dimension", this.dimension.location().toString());
          if (this.operationId != null) {
             correctionRoot.putString("OperationId", this.operationId.toString());
          }
         correctionRoot.putByteArray("BaseSha256", sha256(baseFile));
         correctionRoot.putLongArray("Positions", layout.positions());
         putPalette(correctionRoot, "After", layout);
         correctionRoot = NbtUtils.addCurrentDataVersion(correctionRoot);
         if (correctionRoot.sizeInBytes() > MAX_DECOMPRESSED_BYTES) {
            throw new IOException("Recovery correction exceeds the safe decoded-size limit");
         }
         atomicWriteCompressed(correction, correctionRoot);
         this.correctionRequired = true;
         this.finalAfterPrepared = true;
         return true;
      } catch (IOException | RuntimeException exception) {
         LOGGER.error("Could not write final FastFormer recovery state for {}", this.file, exception);
         return false;
      } catch (OutOfMemoryError error) {
         LOGGER.error("Could not write final FastFormer recovery state for {} because the JVM heap was insufficient", this.file, error);
         return false;
      }
   }

   private PaletteLayout segmentedCorrectionLayout(Map<BlockPos, ReversibleBlockSnapshot> actualAfter) throws IOException {
      var manifest = RecoveryJournalManifest.read(this.file.resolve(MANIFEST_FILE));
      var segments = RecoveryJournalSegments.inspectUnsealed(this.file, this.operationId, manifest.segmentLimit());
      List<ReversibleBlockSnapshot> corrections = new ArrayList<>();
      var matched = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
      for (int segmentIndex = 0; segmentIndex < segments.count(); segmentIndex++) {
         CompoundTag root = RecoveryJournalSegments.read(this.file, this.operationId, segmentIndex).payload();
         validateHeader(root, this.dimension);
         long[] positions = root.getLongArray("Positions");
         List<EncodedSnapshotKey> palette = decodeEncodedPalette(root, "After");
         int[] ids = paletteIds(root, "After", positions.length);
         int uniformId = root.getInt("AfterUniformPaletteId");
         for (int index = 0; index < positions.length; index++) {
            EncodedSnapshotKey predicted = encodedSnapshot(palette, ids == null ? uniformId : ids[index]);
            ReversibleBlockSnapshot actual = actualAfter.get(BlockPos.of(positions[index]));
            if (actual == null) {
               continue;
            }
            if (!matched.add(positions[index])) {
               throw new IOException("Final recovery state has a duplicate journal position");
            }
            if (!encoded(actual).equals(predicted)) {
               corrections.add(actual);
            }
         }
      }
      if (matched.size() != actualAfter.size()) {
         throw new IOException("Final recovery state contains a position outside the segmented journal");
      }
      return corrections.isEmpty()
         ? new PaletteLayout(new long[0], null, List.of())
         : paletteLayout(corrections);
   }

   static PaletteLayout correctionLayout(
      CompoundTag root,
      Map<BlockPos, ReversibleBlockSnapshot> actualAfter
   ) throws IOException {
      long[] basePositions = root.getLongArray("Positions");
      if (basePositions.length == 0) {
         throw new IOException("Recovery journal has no base positions");
      }
      List<EncodedSnapshotKey> predictedPalette = decodeEncodedPalette(root, "After");
      int[] predictedIds = paletteIds(root, "After", basePositions.length);
      int uniformPredicted = root.getInt("AfterUniformPaletteId");
      int corrections = 0;
      int matched = 0;
      for (int i = 0; i < basePositions.length; i++) {
         ReversibleBlockSnapshot actual = actualAfter.get(BlockPos.of(basePositions[i]));
         if (actual != null) {
            matched++;
            int predictedId = predictedIds == null ? uniformPredicted : predictedIds[i];
            if (!encoded(actual).equals(encodedSnapshot(predictedPalette, predictedId))) {
               corrections++;
            }
         }
      }
      if (matched != actualAfter.size()) {
         throw new IOException("Final recovery state contains a position outside the base journal");
      }
      if (corrections == 0) {
         return new PaletteLayout(new long[0], null, List.of());
      }

      long[] positions = new long[corrections];
      int[] paletteIds = null;
      List<SnapshotKey> palette = new ArrayList<>();
      Map<SnapshotKey, Integer> paletteIndex = new HashMap<>();
      int output = 0;
      for (int i = 0; i < basePositions.length; i++) {
         ReversibleBlockSnapshot actual = actualAfter.get(BlockPos.of(basePositions[i]));
         int predictedId = predictedIds == null ? uniformPredicted : predictedIds[i];
         if (actual == null || encoded(actual).equals(encodedSnapshot(predictedPalette, predictedId))) {
            continue;
         }
         SnapshotKey key = new SnapshotKey(actual.state(), normalized(actual.blockEntity()));
         int id = paletteIndex.computeIfAbsent(key, ignored -> {
            palette.add(key);
            return palette.size() - 1;
         });
         positions[output] = actual.pos().asLong();
         if (id != 0 && paletteIds == null) {
            paletteIds = new int[corrections];
         }
         if (paletteIds != null) {
            paletteIds[output] = id;
         }
         output++;
      }
      return new PaletteLayout(positions, paletteIds, List.copyOf(palette));
   }

   private static List<EncodedSnapshotKey> decodeEncodedPalette(CompoundTag root, String prefix) throws IOException {
      ListTag paletteTag = root.getList(prefix + "Palette", Tag.TAG_COMPOUND);
      if (paletteTag.isEmpty()) {
         throw new IOException("Missing recovery " + prefix + " palette");
      }
      List<EncodedSnapshotKey> palette = new ArrayList<>(paletteTag.size());
      for (int i = 0; i < paletteTag.size(); i++) {
         CompoundTag entry = paletteTag.getCompound(i);
         if (!entry.contains("State", Tag.TAG_COMPOUND)) {
            throw new IOException("Missing encoded recovery block state");
         }
         CompoundTag state = entry.getCompound("State").copy();
         if (ResourceLocation.tryParse(state.getString("Name")) == null) {
            throw new IOException("Invalid encoded recovery block state");
         }
         CompoundTag blockEntity = entry.contains("BlockEntity", Tag.TAG_COMPOUND)
            ? entry.getCompound("BlockEntity").copy()
            : null;
         palette.add(new EncodedSnapshotKey(state, blockEntity));
      }
      return palette;
   }

   private static EncodedSnapshotKey encoded(ReversibleBlockSnapshot snapshot) {
      BlockEntitySnapshot normalized = normalized(snapshot.blockEntity());
      return new EncodedSnapshotKey(
         NbtUtils.writeBlockState(snapshot.state()),
         normalized == null ? null : normalized.data()
      );
   }

   private static EncodedSnapshotKey encodedSnapshot(
      List<EncodedSnapshotKey> palette,
      int paletteId
   ) throws IOException {
      if (paletteId < 0 || paletteId >= palette.size()) {
         throw new IOException("Invalid encoded recovery palette id");
      }
      return palette.get(paletteId);
   }

   private static DecodedCorrections decodeCorrections(
      ServerLevel level,
      Path baseFile,
      ResourceKey<Level> expectedDimension
   ) throws IOException {
      return decodeCorrections(level, baseFile, correctionPath(baseFile), expectedDimension);
   }

   private static DecodedCorrections decodeCorrections(
      ServerLevel level,
      Path baseFile,
      Path correction,
      ResourceKey<Level> expectedDimension
   ) throws IOException {
      if (!Files.exists(correction)) {
         return null;
      }
      CompoundTag root = NbtIo.readCompressed(correction, NbtAccounter.create(recoveryDecodeLimit()));
      validateHeader(root, expectedDimension);
      byte[] expectedDigest = root.getByteArray("BaseSha256");
      if (expectedDigest.length != 32 || !Arrays.equals(expectedDigest, sha256(baseFile))) {
         throw new IOException("Recovery correction does not match its base journal");
      }
      long[] positions = root.getLongArray("Positions");
      if (positions.length == 0) {
         throw new IOException("Empty recovery correction");
      }
      List<SnapshotKey> palette = decodePalette(level, root, "After");
      int[] ids = paletteIds(root, "After", positions.length);
      return new DecodedCorrections(positions, palette, ids, root.getInt("AfterUniformPaletteId"));
   }

   private static void validateCorrectionPositions(long[] base, long[] corrections) throws IOException {
      int correctionIndex = 0;
      for (long position : base) {
         if (correctionIndex < corrections.length && position == corrections[correctionIndex]) {
            correctionIndex++;
         }
      }
      if (correctionIndex != corrections.length) {
         throw new IOException("Recovery correction positions are not aligned with the base journal");
      }
   }

   private static void validateHeader(CompoundTag root, ResourceKey<Level> expectedDimension) throws IOException {
      if (root.getInt("Version") != VERSION) {
         throw new IOException("Unsupported journal version " + root.getInt("Version"));
      }
      int currentDataVersion = SharedConstants.getCurrentVersion().getDataVersion().getVersion();
      if (NbtUtils.getDataVersion(root, -1) != currentDataVersion) {
         throw new IOException("Journal was written by a different Minecraft data version");
      }
      ResourceLocation dimensionId = ResourceLocation.tryParse(root.getString("Dimension"));
      if (dimensionId == null || !expectedDimension.location().equals(dimensionId)) {
         throw new IOException("Recovery journal dimension does not match its operation");
      }
   }

   private static UUID readOperationId(CompoundTag root) throws IOException {
      if (!root.contains("OperationId")) {
         return null;
      }
      String encoded = root.getString("OperationId");
      try {
         return UUID.fromString(encoded);
      } catch (IllegalArgumentException exception) {
         throw new IOException("Invalid journal operation ID", exception);
      }
   }

   static PersistentRecoveryJournal writeSegmentedJournal(
      Path operationDirectory,
      UUID owner,
      UUID operationId,
      ResourceKey<Level> dimension,
      Collection<ReversibleBlockSnapshot> before,
      Collection<ReversibleBlockSnapshot> after
   ) throws IOException {
      if (operationDirectory == null || owner == null || operationId == null || dimension == null) {
         throw new IllegalArgumentException("Invalid segmented recovery journal identity");
      }
      Files.createDirectories(operationDirectory);
      RecoveryJournalManifest.write(
         operationDirectory.resolve(MANIFEST_FILE),
         operationId,
         owner,
         dimension.location().toString(),
         SEGMENTED_LIMIT
      );
      boolean canRetainPrediction = after.size() <= FIRST_SEGMENT_CELLS;
      List<ReversibleBlockSnapshot> cachedAfter = canRetainPrediction ? List.copyOf(after) : List.of();
      Collection<ReversibleBlockSnapshot> encodedAfter = canRetainPrediction ? cachedAfter : after;
      CompoundTag payload = encodePrepared(dimension, before, encodedAfter);
      payload.putString("OperationId", operationId.toString());
      if (payload.sizeInBytes() > MAX_DECOMPRESSED_BYTES) {
         throw new IOException("Recovery journal exceeds the safe decoded-size limit");
      }
      RecoveryJournalSegment.write(segmentPath(operationDirectory, 0), operationId, 0, payload);
      PersistentRecoveryJournal journal = new PersistentRecoveryJournal(
         operationDirectory, dimension, operationId, payload.sizeInBytes(), true
      );
      if (canRetainPrediction) {
         journal.rememberInitialPredictedAfter(cachedAfter);
      }
      return journal;
   }

   public synchronized boolean appendSegment(
      Collection<ReversibleBlockSnapshot> before,
      Collection<ReversibleBlockSnapshot> after
   ) {
      if (!canAppendSegment() || this.dimension == null || before == null || before.isEmpty() || after == null) {
         return false;
      }
      try {
         boolean canRetainPrediction = this.predictedAfterCacheComplete
            && this.cachedPredictedAfter.size() + after.size() <= FIRST_SEGMENT_CELLS;
         List<ReversibleBlockSnapshot> cachedAfter = canRetainPrediction ? List.copyOf(after) : List.of();
         Collection<ReversibleBlockSnapshot> encodedAfter = canRetainPrediction ? cachedAfter : after;
         CompoundTag payload = encodePrepared(this.dimension, before, encodedAfter);
         payload.putString("OperationId", this.operationId.toString());
         if (payload.sizeInBytes() > MAX_DECOMPRESSED_BYTES) {
            throw new IOException("Recovery journal exceeds the safe decoded-size limit");
         }
         appendPayload(payload);
         if (canRetainPrediction) {
            this.rememberAppendedPredictedAfter(cachedAfter);
         } else {
            this.clearPredictedAfterCache();
         }
         return true;
      } catch (IOException | RuntimeException exception) {
         LOGGER.error("Could not append FastFormer recovery segment for {}", this.file, exception);
         return false;
      }
   }

   synchronized void appendPayload(CompoundTag payload) throws IOException {
      if (!canAppendSegment() || payload == null) {
         throw new IOException("Cannot append recovery segment");
      }
      RecoveryJournalSegment.write(
         segmentPath(this.file, this.nextSegmentIndex), this.operationId, this.nextSegmentIndex, payload
      );
      this.nextSegmentIndex++;
   }

   int nextSegmentIndex() {
      return this.nextSegmentIndex;
   }

   private boolean canAppendSegment() {
      return this.segmented
         && !this.committed
         && this.operationId != null
         && this.file != null
         && Files.isDirectory(this.file)
         && this.nextSegmentIndex >= 0
         && this.nextSegmentIndex < SEGMENTED_LIMIT
         && !Files.exists(this.file.resolve(SEAL_FILE));
   }

   private static void deleteDirectoryQuietly(Path directory) {
      if (directory == null || !Files.exists(directory)) {
         return;
      }
      try {
         if (Files.isDirectory(directory)) {
            try (var children = Files.list(directory)) {
               for (Path child : children.toList()) {
                  Files.deleteIfExists(child);
               }
            }
         }
         Files.deleteIfExists(directory);
      } catch (IOException | RuntimeException exception) {
         LOGGER.warn("Could not delete FastFormer journal directory {}", directory, exception);
      }
   }

   private static long writePrepared(
      Path file,
      ResourceKey<Level> dimension,
      Collection<ReversibleBlockSnapshot> before,
      Collection<ReversibleBlockSnapshot> after,
      UUID operationId
   ) throws IOException {
      CompoundTag encoded = encodePrepared(dimension, before, after);
      if (encoded.sizeInBytes() > MAX_DECOMPRESSED_BYTES) {
         throw new IOException("Recovery journal exceeds the safe decoded-size limit");
      }
      if (operationId != null) {
         encoded.putString("OperationId", operationId.toString());
      }
      long preparedDecodedBytes = encoded.sizeInBytes();
      atomicWriteCompressed(file, encoded);
      return preparedDecodedBytes;
   }

   static CompoundTag encodePrepared(
      ResourceKey<Level> dimension,
      Collection<ReversibleBlockSnapshot> before,
      Collection<ReversibleBlockSnapshot> after
   ) throws IOException {
      PaletteLayout beforeLayout = paletteLayout(before);
      PaletteLayout afterLayout = paletteLayout(after, beforeLayout.positions());
      long[] positions = beforeLayout.positions();

      CompoundTag root = new CompoundTag();
      root.putInt("Version", VERSION);
      root.putString("Dimension", dimension.location().toString());
      root.putLongArray("Positions", positions);
      putPalette(root, "Before", beforeLayout);
      putPalette(root, "After", afterLayout);
      return NbtUtils.addCurrentDataVersion(root);
   }

   private static void putPalette(CompoundTag root, String prefix, PaletteLayout layout) {
      if (layout.palette().size() > 1) {
         root.putIntArray(prefix + "PaletteIds", layout.paletteIds());
      } else {
         root.putInt(prefix + "UniformPaletteId", 0);
      }
      ListTag paletteTag = new ListTag();
      for (SnapshotKey key : layout.palette()) {
         CompoundTag entry = new CompoundTag();
         entry.put("State", NbtUtils.writeBlockState(key.state()));
         if (key.blockEntity() != null) {
            entry.put("BlockEntity", key.blockEntity().data().copy());
         }
         paletteTag.add(entry);
      }
      root.put(prefix + "Palette", paletteTag);
   }

   static PaletteLayout paletteLayout(Collection<ReversibleBlockSnapshot> snapshots) throws IOException {
      return paletteLayout(snapshots, null);
   }

   private static PaletteLayout paletteLayout(
      Collection<ReversibleBlockSnapshot> snapshots,
      long[] expectedPositions
   ) throws IOException {
      if (snapshots == null || snapshots.isEmpty()) {
         throw new IOException("Recovery journal has no snapshots");
      }
      if (expectedPositions != null && expectedPositions.length != snapshots.size()) {
         throw new IOException("Recovery before/after sizes are not aligned");
      }

      // Callers already provide one original snapshot per position. Avoid a
      // second multi-million-entry map while encoding a large structure.
      long[] positions = expectedPositions == null ? new long[snapshots.size()] : expectedPositions;
      int[] paletteIds = null;
      List<SnapshotKey> palette = new ArrayList<>();
      Map<SnapshotKey, Integer> paletteIndex = new HashMap<>();
      int index = 0;
      for (ReversibleBlockSnapshot snapshot : snapshots) {
         if (snapshot == null) {
            throw new IOException("Recovery journal contains a null snapshot");
         }
         SnapshotKey key = new SnapshotKey(snapshot.state(), normalized(snapshot.blockEntity()));
         int id = paletteIndex.computeIfAbsent(key, ignored -> {
            palette.add(key);
            return palette.size() - 1;
         });
         long packedPos = snapshot.pos().asLong();
         if (expectedPositions == null) {
            positions[index] = packedPos;
         } else if (positions[index] != packedPos) {
            throw new IOException("Recovery before/after positions are not aligned");
         }
         if (id != 0 && paletteIds == null) {
            paletteIds = new int[snapshots.size()];
         }
         if (paletteIds != null) {
            paletteIds[index] = id;
         }
         index++;
      }

      return new PaletteLayout(positions, paletteIds, List.copyOf(palette));
   }

   private static BlockEntitySnapshot normalized(BlockEntitySnapshot snapshot) {
      if (snapshot == null) {
         return null;
      }
      CompoundTag data = snapshot.data().copy();
      data.remove("x");
      data.remove("y");
      data.remove("z");
      return new BlockEntitySnapshot(data);
   }

   private static DecodedJournal decode(ServerLevel level, CompoundTag root) throws IOException {
      long[] positions = root.getLongArray("Positions");
      if (positions.length == 0) {
         throw new IOException("Invalid recovery journal arrays");
      }
      List<SnapshotKey> beforePalette = decodePalette(level, root, "Before");
      List<SnapshotKey> afterPalette = decodePalette(level, root, "After");
      int[] beforeIds = paletteIds(root, "Before", positions.length);
      int[] afterIds = paletteIds(root, "After", positions.length);
      return new DecodedJournal(
         positions,
         beforePalette,
         afterPalette,
         beforeIds,
         afterIds,
         root.getInt("BeforeUniformPaletteId"),
         root.getInt("AfterUniformPaletteId")
      );
   }

   private static List<SnapshotKey> decodePalette(ServerLevel level, CompoundTag root, String prefix) throws IOException {
      ListTag paletteTag = root.getList(prefix + "Palette", Tag.TAG_COMPOUND);
      if (paletteTag.isEmpty()) {
         throw new IOException("Missing recovery " + prefix + " palette");
      }
      List<SnapshotKey> palette = new ArrayList<>(paletteTag.size());
      var blocks = level.registryAccess().lookupOrThrow(Registries.BLOCK);
      for (int i = 0; i < paletteTag.size(); i++) {
         CompoundTag entry = paletteTag.getCompound(i);
         CompoundTag stateTag = entry.getCompound("State");
         ResourceLocation blockId = ResourceLocation.tryParse(stateTag.getString("Name"));
         if (blockId == null || blocks.get(ResourceKey.create(Registries.BLOCK, blockId)).isEmpty()) {
            throw new IOException("Missing block from recovery journal: " + stateTag.getString("Name"));
         }
         BlockState state = NbtUtils.readBlockState(blocks, stateTag);
         BlockEntitySnapshot blockEntity = entry.contains("BlockEntity", Tag.TAG_COMPOUND)
            ? new BlockEntitySnapshot(entry.getCompound("BlockEntity"))
            : null;
         palette.add(new SnapshotKey(state, blockEntity));
      }
      return palette;
   }

   private static int[] paletteIds(CompoundTag root, String prefix, int size) throws IOException {
      int[] ids = root.contains(prefix + "PaletteIds") ? root.getIntArray(prefix + "PaletteIds") : null;
      if (ids != null && ids.length != size) {
         throw new IOException("Invalid recovery palette ids");
      }
      if (ids != null) {
         return ids;
      }
      return null;
   }

   private static ReversibleBlockSnapshot snapshot(
      BlockPos pos,
      List<SnapshotKey> palette,
      int paletteId
   ) throws IOException {
      if (paletteId < 0 || paletteId >= palette.size()) {
         throw new IOException("Invalid recovery palette id");
      }
      SnapshotKey key = palette.get(paletteId);
      return new ReversibleBlockSnapshot(pos, key.state(), key.state().getFluidState(), key.blockEntity());
   }

   static void atomicWriteCompressed(Path file, CompoundTag root) throws IOException {
      Files.createDirectories(file.getParent());
      Path temporary = file.resolveSibling(file.getFileName() + ".tmp-" + UUID.randomUUID());
      try {
         try (FileOutputStream output = new FileOutputStream(temporary.toFile())) {
            // NbtIo closes the stream it receives. Keep ownership of the actual file
            // stream here so the completed gzip member can be forced to disk before
            // the descriptor is closed and before the atomic rename publishes it.
            NbtIo.writeCompressed(root, new FilterOutputStream(output) {
               @Override
               public void close() throws IOException {
                  flush();
               }
            });
            output.getChannel().force(true);
         }
         atomicMove(temporary, file);
      } finally {
         Files.deleteIfExists(temporary);
      }
   }

   private static byte[] sha256(Path file) throws IOException {
      final MessageDigest digest;
      try {
         digest = MessageDigest.getInstance("SHA-256");
      } catch (NoSuchAlgorithmException exception) {
         throw new IllegalStateException("SHA-256 is unavailable", exception);
      }
      byte[] buffer = new byte[64 * 1024];
      try (InputStream input = Files.newInputStream(file)) {
         int read;
         while ((read = input.read(buffer)) >= 0) {
            if (read > 0) {
               digest.update(buffer, 0, read);
            }
         }
      }
      return digest.digest();
   }

   private static long recoveryDecodeLimit() {
      Runtime runtime = Runtime.getRuntime();
      return recoveryDecodeLimit(runtime.maxMemory(), runtime.totalMemory(), runtime.freeMemory());
   }

   static long correctionDecodeLimit(long preparedDecodedBytes) {
      return Math.max(1L, Math.min(MAX_DECOMPRESSED_BYTES, preparedDecodedBytes));
   }

   static long recoveryDecodeLimit(long maxMemory, long totalMemory, long freeMemory) {
      long used = Math.max(0L, totalMemory - freeMemory);
      long available = Math.max(0L, maxMemory - used);
      long reserve = Math.max(MINIMUM_HEAP_RESERVE, maxMemory / 8L);
      long usable = Math.max(0L, available - reserve);
      return Math.max(1L, Math.min(MAX_DECOMPRESSED_BYTES, usable / 2L));
   }

   private static void atomicMove(Path source, Path target) throws IOException {
      try {
         Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException exception) {
         Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
      }
   }

   private static Path committedPath(Path file) {
      String name = file.getFileName().toString();
      return file.resolveSibling(name.substring(0, name.length() - 4) + ".done");
   }

   private static Path committedMarker(Path file) {
      return Files.isDirectory(file) ? file.resolve(SEAL_FILE) : committedPath(file);
   }

   private static Path preparedPath(Path file) {
      String name = file.getFileName().toString();
      return file.resolveSibling(name.substring(0, name.length() - 5) + ".dat");
   }

   private static Path correctionPath(Path file) {
      if (Files.isDirectory(file)) {
         return file.resolve(CORRECTION_FILE);
      }
      String name = file.getFileName().toString();
      int extension = name.lastIndexOf('.');
      String stem = extension < 0 ? name : name.substring(0, extension);
      return file.resolveSibling(stem + ".delta");
   }

   private static Path journalDirectory(MinecraftServer server) {
      return server.getWorldPath(LevelResource.ROOT).resolve(DIRECTORY);
   }

   private static Path segmentPath(Path directory, int sequence) {
      return directory.resolve(String.format("segment-%06d.dat", sequence));
   }

   private static int countExistingSegments(Path directory) {
      try (var files = Files.list(directory)) {
         long count = files
            .filter(path -> path.getFileName().toString().matches("segment-[0-9]{6}\\.dat"))
            .count();
         return count > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) count;
      } catch (IOException | RuntimeException exception) {
         LOGGER.error("Could not count FastFormer recovery segments in {}", directory, exception);
         return 0;
      }
   }

   private static UUID readManifestOperationId(Path directory) {
      try {
         return RecoveryJournalManifest.read(directory.resolve(MANIFEST_FILE)).operationId();
      } catch (IOException | RuntimeException exception) {
         return null;
      }
   }

   static boolean hasOwnerJournal(Path directory, UUID owner) {
      if (!Files.exists(directory)) {
         return false;
      }
      String suffix = "-" + owner;
      try (var files = Files.list(directory)) {
         return files.anyMatch(path -> {
            String name = path.getFileName().toString();
            if (name.startsWith(".")) {
               return false;
            }
            // Only a prepared journal owns an unfinished operation. Committed
            // markers remain replayable until the next durable level save, but
            // recovery order already composes them with newer operations.
            if (name.endsWith(suffix + ".dat")) {
               return true;
            }
            return Files.isDirectory(path)
               && name.contains(suffix + "-")
               && !Files.exists(path.resolve(SEAL_FILE));
         });
      } catch (IOException | RuntimeException exception) {
         LOGGER.error("Could not check existing FastFormer journals for {}", owner, exception);
         return true;
      }
   }

   private record SnapshotKey(BlockState state, BlockEntitySnapshot blockEntity) {
   }

   private record EncodedSnapshotKey(CompoundTag state, CompoundTag blockEntity) {
   }

   private record JournalPair(ReversibleBlockSnapshot before, ReversibleBlockSnapshot after) {
   }

   private record DecodedJournal(
      long[] positions,
      List<SnapshotKey> beforePalette,
      List<SnapshotKey> afterPalette,
      int[] beforeIds,
      int[] afterIds,
      int uniformBefore,
      int uniformAfter
   ) {
      int size() {
         return this.positions.length;
      }

      JournalPair pair(int index) throws IOException {
         BlockPos pos = BlockPos.of(this.positions[index]);
         return new JournalPair(
            snapshot(pos, this.beforePalette, this.beforeIds == null ? this.uniformBefore : this.beforeIds[index]),
            snapshot(pos, this.afterPalette, this.afterIds == null ? this.uniformAfter : this.afterIds[index])
         );
      }
   }

   private record DecodedCorrections(
      long[] positions,
      List<SnapshotKey> palette,
      int[] ids,
      int uniformId
   ) {
      int size() {
         return this.positions.length;
      }

      long position(int index) {
         return this.positions[index];
      }

      ReversibleBlockSnapshot snapshot(int index) throws IOException {
         BlockPos pos = BlockPos.of(this.positions[index]);
         int paletteId = this.ids == null ? this.uniformId : this.ids[index];
         return PersistentRecoveryJournal.snapshot(pos, this.palette, paletteId);
      }
   }

   record PaletteLayout(long[] positions, int[] paletteIds, List<SnapshotKey> palette) {
   }

   enum StartupRecoveryAction {
      ALREADY_RESTORED,
      RESTORE,
      PRESERVE_EXTERNAL
   }
}
