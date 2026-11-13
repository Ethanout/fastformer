package io.github.fastformer.server.recovery;

import static io.github.fastformer.core.persist.StoragePaths.JOURNAL_CORRECTION;
import static io.github.fastformer.core.persist.StoragePaths.JOURNAL_MANIFEST;
import static io.github.fastformer.core.persist.StoragePaths.JOURNAL_SEAL;
import static io.github.fastformer.server.recovery.RecoveryJournalCodec.MAX_DECOMPRESSED_BYTES;
import static io.github.fastformer.server.recovery.RecoveryJournalCodec.VERSION;
import static io.github.fastformer.server.recovery.RecoveryJournalCodec.decodeEncodedPalette;
import static io.github.fastformer.server.recovery.RecoveryJournalCodec.encodePrepared;
import static io.github.fastformer.server.recovery.RecoveryJournalCodec.encoded;
import static io.github.fastformer.server.recovery.RecoveryJournalCodec.encodedSnapshot;
import static io.github.fastformer.server.recovery.RecoveryJournalCodec.paletteIds;
import static io.github.fastformer.server.recovery.RecoveryJournalCodec.paletteLayout;
import static io.github.fastformer.server.recovery.RecoveryJournalCodec.putPalette;
import static io.github.fastformer.server.recovery.RecoveryJournalCodec.validateHeader;

import com.mojang.logging.LogUtils;
import io.github.fastformer.core.async.AsyncTasks;
import io.github.fastformer.common.persistence.NbtEnvelopeFile;
import io.github.fastformer.core.persist.StoragePaths;
import io.github.fastformer.fastplace.world.snapshot.ReversibleBlockSnapshot;
import io.github.fastformer.server.lifecycle.ServerBackgroundTasks;
import io.github.fastformer.server.recovery.RecoveryJournalCodec.EncodedSnapshotKey;
import io.github.fastformer.server.recovery.RecoveryJournalCodec.PaletteLayout;
import io.github.fastformer.server.recovery.spi.HistoryReconciler;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.common.IOUtilities;
import org.slf4j.Logger;

/**
 * A compressed write-ahead journal and final-state correction for one world operation.
 *
 * <p>The complete original state is fsynced before the first block write.
 * After writes settle, a delta bound to the verified segment set records only actual states that
 * differ from prediction. A successful operation seals the verified segment set.
 * Startup resolves every remaining operation before accepting another write.</p>
 */
public final class PersistentRecoveryJournal {
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final AtomicLong SEQUENCE = new AtomicLong(System.currentTimeMillis() * 1000L);
   private static final int IO_CAPACITY = 512;
   private static volatile boolean startupRecoveryBlocked;
   private static final Map<Path, ResourceKey<Level>> COMMITTED = new ConcurrentHashMap<>();
   private static final java.util.Set<Path> CLEANUP_PENDING = ConcurrentHashMap.newKeySet();

   private static final int SEGMENTED_LIMIT = 65_536;
   private static final int FIRST_SEGMENT_CELLS = 256;
   private static final int NEXT_SEGMENT_CELLS = 4_096;

   private final Path file;
   private final ResourceKey<Level> dimension;
   private final UUID operationId;
   private Executor ioExecutor = Runnable::run;
   private int nextSegmentIndex;
   private boolean committed;
   private volatile boolean finalAfterPrepared;
   private volatile boolean correctionRequired;
   private List<ReversibleBlockSnapshot> cachedPredictedAfter = List.of();
   private boolean predictedAfterCacheComplete;

   PersistentRecoveryJournal(Path file) {
      this(file, null, null);
   }

   PersistentRecoveryJournal(Path file, ResourceKey<Level> dimension) {
      this(file, dimension, null);
   }

   private PersistentRecoveryJournal(
      Path file, ResourceKey<Level> dimension, UUID operationId
   ) {
      this.file = file;
      this.dimension = dimension;
      UUID resolvedOperation = operationId;
      int nextSegment = 0;
      if (file != null && Files.isDirectory(file)) {
         nextSegment = countExistingSegments(file);
         if (resolvedOperation == null) {
            resolvedOperation = readManifestOperationId(file);
         }
      }
      this.operationId = resolvedOperation;
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
         PersistentRecoveryJournal journal = writeSegmentedJournal(operationDirectory, owner, resolvedOperation, dimension, before, after);
         journal.ioExecutor = executor(server);
         return Optional.of(journal);
      } catch (IOException | RuntimeException exception) {
         LOGGER.error("Could not create FastFormer recovery journal for {}", owner, exception);
         deleteDirectoryQuietly(operationDirectory);
         return Optional.empty();
      }
   }

   /** Atomically marks a successful operation committed; cleanup waits for a durable level save. */
   public synchronized boolean complete() {
      if (this.committed) {
         return true;
      }
      return completeSegmented();
   }

   private boolean completeSegmented() {
      Path seal = this.file.resolve(JOURNAL_SEAL);
      try {
         if (Files.exists(seal)) {
            markSegmentedCommitted();
            return true;
         }
         if (!Files.isDirectory(this.file)) {
            LOGGER.error("FastFormer segmented journal disappeared before seal: {}", this.file);
            return false;
         }
         RecoveryJournalManifest.Manifest manifest = RecoveryJournalManifest.read(this.file.resolve(JOURNAL_MANIFEST));
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

   public synchronized boolean completeFinalized() {
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

   public synchronized boolean sealFinalizedForHistory() {
      if (!this.completeFinalized()) {
         return false;
      }
      COMMITTED.remove(this.file);
      return true;
   }

   public synchronized void historyPublished() {
      if (!this.committed || this.dimension == null) {
         return;
      }
      COMMITTED.put(this.file, this.dimension);
   }

   /** Removes a prepared journal for a task that was cancelled before any world write. */
   public synchronized boolean discardUnused() {
      try {
         if (this.committed || Files.exists(committedMarker(this.file))) {
            return false;
         }
         deleteDirectoryQuietly(this.file);
         return !Files.exists(this.file);
      } catch (RuntimeException exception) {
         LOGGER.error("Could not discard unused FastFormer journal {}", this.file, exception);
         return false;
      }
   }

   /**
    * Resolves a journal after the world has already been restored to before.
    * If the file disappeared unexpectedly, a forced world save is the only
    * remaining durable proof that it is safe to release the recovery lock.
    */
   public synchronized boolean resolveAfterRollback(MinecraftServer server) {
      if (!JournalStartupRecovery.saveDurably(server)) {
         LOGGER.error("Could not durably save the world after FastFormer rollback: {}", this.file);
         return false;
      }
      try {
         deleteDirectoryQuietly(this.file);
         if (Files.exists(this.file)) return false;
         COMMITTED.remove(this.file);
      } catch (RuntimeException exception) {
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
      try {
         executor(level.getServer()).execute(() -> {
         try {
            IOUtilities.waitUntilIOWorkerComplete();
            for (Path path : ready) {
               try {
                  deleteDirectoryQuietly(path);
                  if (!Files.exists(path)) COMMITTED.remove(path, savedDimension);
               } catch (RuntimeException exception) {
                  LOGGER.warn("Committed FastFormer journal will be replayed on next startup: {}", path, exception);
               }
            }
         } catch (RuntimeException exception) {
            LOGGER.error("Could not confirm durable chunk IO for FastFormer committed journals", exception);
         } finally {
            CLEANUP_PENDING.removeAll(ready);
         }
         });
      } catch (RuntimeException failure) {
         CLEANUP_PENDING.removeAll(ready);
         LOGGER.warn("Committed journals retained because cleanup could not be queued", failure);
      }
   }

   public CompletableFuture<Boolean> finalizeAfter(
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
      try {
         return AsyncTasks.submit(ioExecutor, () -> this.writeCorrections(actualAfter));
      } catch (RuntimeException failure) {
         return CompletableFuture.failedFuture(failure);
      }
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

   public void deleteOrphanCorrection() {
      try {
         Path committed = committedMarker(this.file);
         if (!Files.exists(this.file) && !Files.exists(committed)) {
            Files.deleteIfExists(correctionPath(this.file));
         }
      } catch (IOException | RuntimeException exception) {
         LOGGER.warn("Could not delete orphan FastFormer journal correction for {}", this.file, exception);
      }
   }

   /** Replays startup journals with the history implementation supplied by server assembly. */
   public static boolean recoverAll(MinecraftServer server, HistoryReconciler history) {
      return recoverAll(server, history, () -> JournalStartupRecovery.saveDurably(server));
   }

   static boolean recoverAll(MinecraftServer server, HistoryReconciler history, BooleanSupplier durableSave) {
      startupRecoveryBlocked = false;
      COMMITTED.clear();
      CLEANUP_PENDING.clear();
      boolean success = JournalStartupRecovery.recoverAll(server, journalDirectory(server), history, durableSave);
      startupRecoveryBlocked = !success;
      return success;
   }

   public static boolean writesAllowed() {
      return !startupRecoveryBlocked;
   }

   public static void blockNewWrites() {
      startupRecoveryBlocked = true;
   }

   static void resetWriteGateForTest() {
      startupRecoveryBlocked = false;
   }

   public static Executor executor(MinecraftServer server) {
      return ServerBackgroundTasks.serial(server, "recovery", IO_CAPACITY);
   }

   /** Waits until every journal action queued before this call has finished. */
   public static boolean awaitIoIdle(MinecraftServer server) {
      return awaitIoIdle(executor(server));
   }

   static boolean awaitIoIdle(Executor executor) {
      try {
         AsyncTasks.run(executor, () -> {}).get(30, java.util.concurrent.TimeUnit.SECONDS);
         return true;
      } catch (InterruptedException exception) {
         Thread.currentThread().interrupt();
         startupRecoveryBlocked = true;
         LOGGER.error("Interrupted while waiting for recovery journal work", exception);
         return false;
      } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException | RuntimeException | OutOfMemoryError exception) {
         LOGGER.error("FastFormer recovery journal queue did not reach an idle boundary", exception);
         startupRecoveryBlocked = true;
         return false;
      }
   }

   static boolean belongsToDirectory(Path file, Path directory) {
      if (file == null || directory == null) {
         return false;
      }
      Path parent = file.toAbsolutePath().normalize().getParent();
      return directory.toAbsolutePath().normalize().equals(parent);
   }

   private boolean writeCorrections(
      Map<BlockPos, ReversibleBlockSnapshot> actualAfter
   ) {
      Path correction = correctionPath(this.file);
      try {
         if (!Files.exists(this.file) || Files.exists(committedMarker(this.file))) {
            return false;
         }
         CorrectionPlan plan = segmentedCorrectionLayout(actualAfter);
         PaletteLayout layout = plan.layout();
         if (layout.positions().length == 0) {
            Files.deleteIfExists(correction);
            this.correctionRequired = false;
            this.finalAfterPrepared = true;
            return true;
         }
         CompoundTag correctionRoot = new CompoundTag();
         correctionRoot.putInt("Version", VERSION);
         correctionRoot.putString("Dimension", this.dimension.location().toString());
         correctionRoot.putString("OperationId", this.operationId.toString());
         correctionRoot.putInt("BaseCount", plan.base().count());
         correctionRoot.putLong("BaseDigest", plan.base().digest());
         correctionRoot.putLongArray("Positions", layout.positions());
         putPalette(correctionRoot, "After", layout);
         correctionRoot = NbtUtils.addCurrentDataVersion(correctionRoot);
         if (correctionRoot.sizeInBytes() > MAX_DECOMPRESSED_BYTES) {
            throw new IOException("Recovery correction exceeds the safe decoded-size limit");
         }
         NbtEnvelopeFile.write(correction, RecoveryJournalCodec.correctionFormat(), correctionRoot, MAX_DECOMPRESSED_BYTES);
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

   private CorrectionPlan segmentedCorrectionLayout(Map<BlockPos, ReversibleBlockSnapshot> actualAfter) throws IOException {
      var manifest = RecoveryJournalManifest.read(this.file.resolve(JOURNAL_MANIFEST));
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
      PaletteLayout layout = corrections.isEmpty()
         ? new PaletteLayout(new long[0], null, List.of())
         : paletteLayout(corrections);
      return new CorrectionPlan(layout, segments);
   }

   private record CorrectionPlan(PaletteLayout layout, RecoveryJournalSegments.SegmentSet base) {}

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
         operationDirectory.resolve(JOURNAL_MANIFEST),
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
         operationDirectory, dimension, operationId
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
      return !this.committed
         && this.operationId != null
         && this.file != null
         && Files.isDirectory(this.file)
         && this.nextSegmentIndex >= 0
         && this.nextSegmentIndex < SEGMENTED_LIMIT
         && !Files.exists(this.file.resolve(JOURNAL_SEAL));
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


   private static Path committedMarker(Path file) {
      return file.resolve(JOURNAL_SEAL);
   }

   private static Path correctionPath(Path file) {
      return file.resolve(JOURNAL_CORRECTION);
   }

   private static Path journalDirectory(MinecraftServer server) {
      return StoragePaths.recovery(server.getWorldPath(LevelResource.ROOT));
   }

   private static Path segmentPath(Path directory, int sequence) {
      return StoragePaths.journalSegment(directory, sequence);
   }

   private static int countExistingSegments(Path directory) {
      try (var files = Files.list(directory)) {
         long count = files
            .filter(path -> path.getFileName().toString().matches(StoragePaths.JOURNAL_SEGMENT_PATTERN))
            .count();
         return count > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) count;
      } catch (IOException | RuntimeException exception) {
         LOGGER.error("Could not count FastFormer recovery segments in {}", directory, exception);
         return 0;
      }
   }

   private static UUID readManifestOperationId(Path directory) {
      try {
         return RecoveryJournalManifest.read(directory.resolve(JOURNAL_MANIFEST)).operationId();
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
            return Files.isDirectory(path)
               && name.contains(suffix + "-")
               && !Files.exists(path.resolve(JOURNAL_SEAL));
         });
      } catch (IOException | RuntimeException exception) {
         LOGGER.error("Could not check existing FastFormer journals for {}", owner, exception);
         return true;
      }
   }

}
