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

   private final Path file;
   private final ResourceKey<Level> dimension;
   private final UUID operationId;
   private final long preparedDecodedBytes;
   private boolean committed;
   private volatile boolean finalAfterPrepared;
   private volatile boolean correctionRequired;

   PersistentRecoveryJournal(Path file) {
      this(file, null, null, MAX_DECOMPRESSED_BYTES);
   }

   private PersistentRecoveryJournal(Path file, ResourceKey<Level> dimension) {
      this(file, dimension, null, MAX_DECOMPRESSED_BYTES);
   }

   private PersistentRecoveryJournal(
      Path file, ResourceKey<Level> dimension, UUID operationId, long preparedDecodedBytes
   ) {
      this.file = file;
      this.dimension = dimension;
      this.operationId = operationId;
      this.preparedDecodedBytes = Math.max(1L, Math.min(MAX_DECOMPRESSED_BYTES, preparedDecodedBytes));
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
      Path file = directory.resolve(String.format("%020d-%s.dat", sequence, owner));
      try {
         long preparedDecodedBytes = writePrepared(file, dimension, before, after, operationId);
         return Optional.of(new PersistentRecoveryJournal(file, dimension, operationId, preparedDecodedBytes));
      } catch (IOException | RuntimeException exception) {
         LOGGER.error("Could not create FastFormer recovery journal for {}", owner, exception);
         return Optional.empty();
      }
   }

   /** Atomically marks a successful operation committed; cleanup waits for a durable level save. */
   synchronized boolean complete() {
      if (this.committed) {
         return true;
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
         if (this.committed || Files.exists(committedPath(this.file))) {
            return false;
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
      Path committed = committedPath(this.file);
      try {
         Files.deleteIfExists(this.file);
         Files.deleteIfExists(committed);
         Files.deleteIfExists(correctionPath(this.file));
         COMMITTED.remove(committed);
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
         this.finalAfterPrepared = true;
         this.correctionRequired = false;
         return CompletableFuture.completedFuture(true);
      }
      return CompletableFuture.supplyAsync(() -> this.writeCorrections(actualAfter), IO_EXECUTOR);
   }

   void deleteOrphanCorrection() {
      try {
         Path committed = committedPath(this.file);
         if (!Files.exists(this.file) && !Files.exists(committed)) {
            Files.deleteIfExists(correctionPath(this.file));
         }
      } catch (IOException | RuntimeException exception) {
         LOGGER.warn("Could not delete orphan FastFormer journal correction for {}", this.file, exception);
      }
   }

   /** Runs synchronously during ServerStartedEvent, before player operations. */
   public static boolean recoverAll(MinecraftServer server) {
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
         if (!saveDurably(server)) {
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
               var segments = RecoveryJournalSegments.readUnsealed(directory, manifest.operationId(), manifest.segmentLimit());
               ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(manifest.dimension()));
               for (var segment : segments) validateHeader(segment.payload(), dimension);
               continue;
            }
            RecoveryJournalSeal.Seal sealData = RecoveryJournalSeal.read(seal, manifest.operationId());
            if (sealData.segmentCount() > manifest.segmentLimit()) {
               throw new IOException("Segmented recovery operation exceeds manifest segment limit");
            }
            List<RecoveryJournalSegments.CompoundSegment> segments = RecoveryJournalSegments.readComplete(
               directory, manifest.operationId(), sealData.segmentCount()
            );
            if (RecoveryJournalSegments.digest(segments) != sealData.digest()) {
               throw new IOException("Segmented recovery operation digest mismatch");
            }
            ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(manifest.dimension()));
            for (var segment : segments) {
               validateHeader(segment.payload(), dimension);
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
            ? RecoveryJournalSegments.readComplete(directory, manifest.operationId(),
               RecoveryJournalSeal.read(directory.resolve("seal.done"), manifest.operationId()).segmentCount())
            : RecoveryJournalSegments.readUnsealed(directory, manifest.operationId(), manifest.segmentLimit());
         ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(manifest.dimension()));
         ServerLevel level = server.getLevel(dimension);
         if (level == null) throw new IOException("Journal dimension is not loaded: " + manifest.dimension());
         List<DecodedJournal> decoded = new ArrayList<>(segments.size());
         for (var segment : segments) {
            DecodedJournal journal = decode(level, segment.payload());
            // Reject invalid palette references before the first world write.
            for (int index = 0; index < journal.size(); index++) journal.pair(index);
            decoded.add(journal);
         }
         int restored = 0;
         int preserved = 0;
         for (int segmentIndex = 0; segmentIndex < decoded.size(); segmentIndex++) {
            var journal = decoded.get(sealed ? segmentIndex : decoded.size() - 1 - segmentIndex);
            for (int cell = 0; cell < journal.size(); cell++) {
               int index = sealed ? cell : journal.size() - 1 - cell;
               JournalPair pair = journal.pair(index);
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
         if (!Files.exists(this.file) || Files.exists(committedPath(this.file))) {
            return false;
         }
         CompoundTag root = NbtIo.readCompressed(
            this.file, NbtAccounter.create(correctionDecodeLimit(this.preparedDecodedBytes))
         );
         validateHeader(root, this.dimension);
         PaletteLayout layout = correctionLayout(root, actualAfter);
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
         correctionRoot.putByteArray("BaseSha256", sha256(this.file));
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
      Path correction = correctionPath(baseFile);
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

   private static Path preparedPath(Path file) {
      String name = file.getFileName().toString();
      return file.resolveSibling(name.substring(0, name.length() - 5) + ".dat");
   }

   private static Path correctionPath(Path file) {
      String name = file.getFileName().toString();
      int extension = name.lastIndexOf('.');
      String stem = extension < 0 ? name : name.substring(0, extension);
      return file.resolveSibling(stem + ".delta");
   }

   private static Path journalDirectory(MinecraftServer server) {
      return server.getWorldPath(LevelResource.ROOT).resolve(DIRECTORY);
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
            return name.endsWith(suffix + ".dat");
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
