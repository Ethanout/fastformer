package io.github.fastformer.server.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.fastformer.common.persistence.NbtEnvelopeFile;
import io.github.fastformer.fastplace.world.snapshot.BlockEntitySnapshot;
import io.github.fastformer.fastplace.world.snapshot.ReversibleBlockSnapshot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PersistentRecoveryJournalTest {
   @TempDir
   Path temporaryDirectory;

   @Test
   void repeatedBlockContentsUseOnePaletteEntry() throws IOException {
      RecoveryJournalCodec.PaletteLayout layout = RecoveryJournalCodec.paletteLayout(List.of(
         snapshot(new BlockPos(0, 64, 0), null),
         snapshot(new BlockPos(1, 64, 0), null),
         snapshot(new BlockPos(2, 64, 0), null)
      ));

      assertEquals(3, layout.positions().length);
      assertEquals(1, layout.palette().size());
   }

   @Test
   void blockEntityContentsParticipateInThePaletteKey() throws IOException {
      CompoundTag first = new CompoundTag();
      first.putString("CustomName", "first");
      CompoundTag second = new CompoundTag();
      second.putString("CustomName", "second");

      RecoveryJournalCodec.PaletteLayout layout = RecoveryJournalCodec.paletteLayout(List.of(
         snapshot(new BlockPos(0, 64, 0), first),
         snapshot(new BlockPos(1, 64, 0), second)
      ));

      assertEquals(2, layout.palette().size());
      assertEquals(2, layout.paletteIds().length);
   }

   @Test
   void blockEntityCoordinatesDoNotDefeatPaletteCompression() throws IOException {
      CompoundTag first = new CompoundTag();
      first.putString("id", "test:entity");
      first.putInt("x", 0);
      first.putInt("y", 64);
      first.putInt("z", 0);
      CompoundTag second = first.copy();
      second.putInt("x", 100);
      second.putInt("z", -50);

      RecoveryJournalCodec.PaletteLayout layout = RecoveryJournalCodec.paletteLayout(List.of(
         snapshot(new BlockPos(0, 64, 0), first),
         snapshot(new BlockPos(100, 64, -50), second)
      ));

      assertEquals(1, layout.palette().size());
   }

   @Test
   void overlappingCrashJournalsRecoverNewestFirst() {
      List<Path> ordered = JournalStartupRecovery.recoveryOrder(List.of(
         Path.of("00000000000000000001-first.dat"),
         Path.of("00000000000000000003-third.dat"),
         Path.of("00000000000000000002-second.dat")
      ));

      assertEquals("00000000000000000003-third.dat", ordered.get(0).getFileName().toString());
      assertEquals("00000000000000000001-first.dat", ordered.get(2).getFileName().toString());
   }






   @Test
   void segmentBatchSizeKeepsTheFirstSegmentSmall() {
      assertEquals(1, PersistentRecoveryJournal.segmentBatchSize(0, 1));
      assertEquals(256, PersistentRecoveryJournal.segmentBatchSize(0, 10_000));
      assertEquals(256, PersistentRecoveryJournal.segmentBatchSize(0, 256));
      assertEquals(4096, PersistentRecoveryJournal.segmentBatchSize(256, 10_000));
      assertEquals(12, PersistentRecoveryJournal.segmentBatchSize(256, 12));
      assertEquals(0, PersistentRecoveryJournal.segmentBatchSize(0, 0));
   }

   @Test
   void appendPayloadWritesTheNextSequentialSegment() throws Exception {
      UUID owner = UUID.randomUUID();
      UUID operation = UUID.randomUUID();
      Path directory = segmentedDirectory(owner, operation);
      PersistentRecoveryJournal journal = new PersistentRecoveryJournal(directory);
      CompoundTag payload = new CompoundTag();
      payload.putInt("Count", 2);

      journal.appendPayload(payload);

      assertEquals(2, journal.nextSegmentIndex());
      assertEquals(true, Files.exists(directory.resolve("segment-000001.dat")));
      assertEquals(false, Files.exists(directory.resolve("seal.done")));
      assertEquals(2, RecoveryJournalSegments.readUnsealed(directory, operation, 8).size());
   }

   @Test
   void unusedSegmentedJournalIsDiscardedWithoutASeal() throws Exception {
      UUID owner = UUID.randomUUID();
      UUID operation = UUID.randomUUID();
      Path directory = segmentedDirectory(owner, operation);
      PersistentRecoveryJournal journal = new PersistentRecoveryJournal(directory);

      assertEquals(true, journal.discardUnused());
      assertEquals(false, Files.exists(directory));
   }

   @Test
   void segmentedJournalWritesSealOnFinalizedCommit() throws Exception {
      UUID owner = UUID.randomUUID();
      UUID operation = UUID.randomUUID();
      Path directory = segmentedDirectory(owner, operation);
      PersistentRecoveryJournal journal = new PersistentRecoveryJournal(directory);

      assertEquals(false, journal.completeFinalized());
      assertEquals(true, journal.finalizeAfter(java.util.Map.of()).join());
      assertEquals(true, journal.completeFinalized());
      assertEquals(true, Files.exists(directory.resolve("seal.done")));
      assertEquals(true, Files.exists(directory.resolve("manifest.dat")));
      assertEquals(true, Files.exists(directory.resolve("segment-000000.dat")));
   }

   @Test
   void smallMatchingFinalStatesSkipCorrectionFileReading() throws Exception {
      for (int size : List.of(1, 3, 16)) {
         Path prepared = Files.createDirectory(this.temporaryDirectory.resolve("matching-" + size));
         List<ReversibleBlockSnapshot> after = snapshots(size, "predicted");
         PersistentRecoveryJournal journal = new PersistentRecoveryJournal(prepared);
         journal.rememberInitialPredictedAfter(after);

         assertEquals(size, journal.cachedPredictedAfterCount());
         assertEquals(true, journal.finalizeAfter(byPosition(after)).join());
         assertEquals(0, journal.cachedPredictedAfterCount());
         assertEquals(false, Files.exists(prepared.resolve("correction.delta")));
      }
   }

   @Test
   void changedFinalStateFallsBackToJournalReading() throws Exception {
      Path prepared = Files.createDirectory(this.temporaryDirectory.resolve("changed-final-state"));
      List<ReversibleBlockSnapshot> predicted = snapshots(3, "predicted");
      PersistentRecoveryJournal journal = new PersistentRecoveryJournal(prepared);
      journal.rememberInitialPredictedAfter(predicted);
      Map<BlockPos, ReversibleBlockSnapshot> actual = byPosition(predicted);
      BlockPos changed = new BlockPos(1, 64, 0);
      actual.put(changed, snapshot(changed, marker("changed")));

      assertEquals(false, journal.finalizeAfter(actual).join());

      assertEquals(0, journal.cachedPredictedAfterCount());
   }

   @Test
   void predictionCacheIsReleasedWhenJournalExceedsFirstSegment() throws Exception {
      PersistentRecoveryJournal journal = new PersistentRecoveryJournal(
         this.temporaryDirectory.resolve("large-operation.dat")
      );
      journal.rememberInitialPredictedAfter(snapshots(256, "predicted"));

      assertEquals(256, journal.cachedPredictedAfterCount());
      journal.rememberAppendedPredictedAfter(List.of(snapshot(new BlockPos(256, 64, 0), marker("predicted"))));
      assertEquals(0, journal.cachedPredictedAfterCount());
   }

   @Test
   void unsealedSegmentedDirectoryBlocksANewOwnerJournal() throws Exception {
      UUID owner = UUID.randomUUID();
      segmentedDirectory(owner, UUID.randomUUID());

      assertEquals(true, PersistentRecoveryJournal.hasOwnerJournal(this.temporaryDirectory, owner));
   }

   @Test
   void sealedSegmentedDirectoryDoesNotBlockANewOwnerJournal() throws Exception {
      UUID owner = UUID.randomUUID();
      UUID operation = UUID.randomUUID();
      Path directory = segmentedDirectory(owner, operation);
      PersistentRecoveryJournal journal = new PersistentRecoveryJournal(directory);
      assertEquals(true, journal.finalizeAfter(java.util.Map.of()).join());
      assertEquals(true, journal.completeFinalized());
      assertEquals(true, journal.completeFinalized());
      assertEquals(false, journal.discardUnused());

      assertEquals(false, PersistentRecoveryJournal.hasOwnerJournal(this.temporaryDirectory, owner));
   }

   @Test
   void sealedSegmentedDirectoryWithMissingSegmentIsRejected() throws Exception {
      UUID owner = UUID.randomUUID();
      UUID operation = UUID.randomUUID();
      Path directory = segmentedDirectory(owner, operation);
      RecoveryJournalSegments.CompoundSegment segment = RecoveryJournalSegments.read(
         directory, operation, 0
      );
      RecoveryJournalSeal.write(
         directory.resolve("seal.done"), operation, 1,
         RecoveryJournalSegments.digest(List.of(segment))
      );
      Files.delete(directory.resolve("segment-000000.dat"));

      assertEquals(false, RecoveryJournalSegments.validateSegmentDirectories(List.of(directory)));
   }


   @Test
   void committedMarkerBelongsOnlyToItsWorldRecoveryDirectory() throws IOException {
      Path firstWorld = this.temporaryDirectory.resolve("first-world").resolve("fastformer-recovery");
      Path secondWorld = this.temporaryDirectory.resolve("second-world").resolve("fastformer-recovery");
      Files.createDirectories(firstWorld);
      Files.createDirectories(secondWorld);
      Path marker = firstWorld.resolve("00000000000000000004-owner.done");

      assertEquals(true, PersistentRecoveryJournal.belongsToDirectory(marker, firstWorld));
      assertEquals(false, PersistentRecoveryJournal.belongsToDirectory(marker, secondWorld));
      assertEquals(false, PersistentRecoveryJournal.belongsToDirectory(marker, firstWorld.getParent()));
   }

   @Test
   void startupRecoveryRestoresOnlyJournalOwnedAfterStates() {
      assertEquals(
         JournalReplayer.StartupRecoveryAction.ALREADY_RESTORED,
         JournalReplayer.startupRecoveryAction(true, false)
      );
      assertEquals(
         JournalReplayer.StartupRecoveryAction.RESTORE,
         JournalReplayer.startupRecoveryAction(false, true)
      );
      assertEquals(
         JournalReplayer.StartupRecoveryAction.PRESERVE_EXTERNAL,
         JournalReplayer.startupRecoveryAction(false, false)
      );
   }


   @Test
   void recoveryDecodeBudgetRetainsHeapHeadroomAndNeverExceedsTheFormatCap() {
      long gibibyte = 1024L * 1024L * 1024L;

      assertEquals(
         512L * 1024L * 1024L,
         RecoveryJournalCodec.recoveryDecodeLimit(8L * gibibyte, 2L * gibibyte, gibibyte)
      );
      assertEquals(
         128L * 1024L * 1024L,
         RecoveryJournalCodec.recoveryDecodeLimit(gibibyte, gibibyte, 512L * 1024L * 1024L)
      );
      assertEquals(1L, RecoveryJournalCodec.recoveryDecodeLimit(512L, 512L, 0L));
   }


   @Test
   void ioIdleBarrierWaitsForPreviouslyQueuedJournalWork() {
      AtomicBoolean completed = new AtomicBoolean();
      try (var workers = new io.github.fastformer.core.async.IoExecutors("journal-barrier-test", ignored -> {})) {
         var executor = workers.serial("recovery", 4);
         CompletableFuture.runAsync(() -> completed.set(true), executor);
         assertEquals(true, PersistentRecoveryJournal.awaitIoIdle(executor));
         assertEquals(true, completed.get());
      }
   }

   @Test
   void compressedJournalIsDurableBeforeItsAtomicRename() throws IOException {
      Path journal = this.temporaryDirectory.resolve("00000000000000000008-owner.dat");
      CompoundTag expected = new CompoundTag();
      expected.putString("Probe", "complete compressed payload");

      NbtEnvelopeFile.write(journal, RecoveryJournalCodec.correctionFormat(), expected, 1024L * 1024L);

      CompoundTag actual = RecoveryJournalCodec.readCorrection(journal);
      assertEquals(expected, actual);
      try (var children = Files.list(this.temporaryDirectory)) {
         assertEquals(0L, children.filter(path -> path.getFileName().toString().contains(".tmp-")).count());
      }
   }

   private Path segmentedDirectory(UUID owner, UUID operation) throws Exception {
      Path directory = this.temporaryDirectory.resolve(
         String.format("00000000000000000009-%s-%s", owner, operation)
      );
      Files.createDirectories(directory);
      RecoveryJournalManifest.write(
         directory.resolve("manifest.dat"), operation, owner, "minecraft:overworld", 4
      );
      RecoveryJournalSegment.write(
         directory.resolve("segment-000000.dat"), operation, 0, new CompoundTag()
      );
      return directory;
   }

   private static ReversibleBlockSnapshot snapshot(BlockPos pos, CompoundTag blockEntity) {
      return new ReversibleBlockSnapshot(
         pos,
         null,
         null,
         blockEntity == null ? null : new BlockEntitySnapshot(blockEntity)
      );
   }

   private static List<ReversibleBlockSnapshot> snapshots(int count, String value) {
      return java.util.stream.IntStream.range(0, count)
         .mapToObj(index -> snapshot(new BlockPos(index, 64, 0), marker(value)))
         .toList();
   }

   private static CompoundTag marker(String value) {
      CompoundTag marker = new CompoundTag();
      marker.putString("marker", value);
      return marker;
   }

   private static Map<BlockPos, ReversibleBlockSnapshot> byPosition(
      List<ReversibleBlockSnapshot> snapshots
   ) {
      Map<BlockPos, ReversibleBlockSnapshot> result = new LinkedHashMap<>();
      for (ReversibleBlockSnapshot snapshot : snapshots) {
         result.put(snapshot.pos(), snapshot);
      }
      return result;
   }

}
