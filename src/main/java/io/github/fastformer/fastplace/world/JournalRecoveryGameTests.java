package io.github.fastformer.fastplace.world;

import io.github.fastformer.FastFormer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(FastFormer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class JournalRecoveryGameTests {
   private JournalRecoveryGameTests() {
   }

   @GameTest(template = "fastformergametests.empty", timeoutTicks = 100)
   public static void sealedSegmentsReplayWorldContents(GameTestHelper helper) throws Exception {
      var level = helper.getLevel();
      BlockPos first = helper.absolutePos(new BlockPos(1, 1, 1));
      BlockPos second = helper.absolutePos(new BlockPos(2, 1, 1));
      var beforeFirst = ReversibleBlockSnapshot.capture(level, first).orElseThrow();
      var beforeSecond = ReversibleBlockSnapshot.capture(level, second).orElseThrow();
      level.setBlock(first, Blocks.GOLD_BLOCK.defaultBlockState(), 2);
      level.setBlock(second, Blocks.DIAMOND_BLOCK.defaultBlockState(), 2);
      var afterFirst = ReversibleBlockSnapshot.capture(level, first).orElseThrow();
      var afterSecond = ReversibleBlockSnapshot.capture(level, second).orElseThrow();
      beforeFirst.restore(level, 2);
      beforeSecond.restore(level, 2);
      Path directory = Files.createTempDirectory("fastformer-sealed-recovery-");
      UUID operation = UUID.randomUUID();
      try {
         RecoveryJournalManifest.write(directory.resolve("manifest.dat"), operation, UUID.randomUUID(),
            level.dimension().location().toString(), 2);
         RecoveryJournalSegment.write(directory.resolve("segment-000000.dat"), operation, 0,
            PersistentRecoveryJournal.encodePrepared(level.dimension(), List.of(beforeFirst), List.of(afterFirst)));
         RecoveryJournalSegment.write(directory.resolve("segment-000001.dat"), operation, 1,
            PersistentRecoveryJournal.encodePrepared(level.dimension(), List.of(beforeSecond), List.of(afterSecond)));
         RecoveryJournalSeal.write(directory.resolve("seal.done"), operation, 2,
            RecoveryJournalSegments.digest(RecoveryJournalSegments.readComplete(directory, operation, 2)));

         helper.assertTrue(PersistentRecoveryJournal.recoverOne(level.getServer(), directory, true), "sealed replay failed");
         helper.assertTrue(afterFirst.matches(level, first) && afterSecond.matches(level, second), "sealed replay lost contents");
         level.setBlock(first, Blocks.EMERALD_BLOCK.defaultBlockState(), 2);
         helper.assertTrue(PersistentRecoveryJournal.recoverOne(level.getServer(), directory, true), "repeated replay failed");
         helper.assertTrue(level.getBlockState(first).is(Blocks.EMERALD_BLOCK), "repeated replay overwrote an external change");
         helper.assertTrue(afterSecond.matches(level, second), "repeated replay changed an already restored block");
         helper.assertTrue(Files.exists(directory.resolve("seal.done")), "replay deleted journal before durable save");
         Files.delete(directory.resolve("seal.done"));
         helper.assertTrue(PersistentRecoveryJournal.recoverOne(level.getServer(), directory, false), "unsealed rollback failed");
         helper.assertTrue(level.getBlockState(first).is(Blocks.EMERALD_BLOCK), "rollback overwrote external change");
         helper.assertTrue(beforeSecond.matches(level, second), "unsealed rollback did not restore before");
         helper.assertTrue(PersistentRecoveryJournal.recoverOne(level.getServer(), directory, false), "repeated rollback failed");
         helper.succeed();
      } finally {
         try (var files = Files.list(directory)) {
            for (Path file : files.toList()) Files.delete(file);
         }
         Files.delete(directory);
      }
   }

   @GameTest(template = "fastformergametests.empty", timeoutTicks = 100)
   public static void recoveryConsumesAllCorrections(GameTestHelper helper) throws Exception {
      var level = helper.getLevel();
      BlockPos first = helper.absolutePos(new BlockPos(1, 1, 1));
      BlockPos second = helper.absolutePos(new BlockPos(2, 1, 1));
      var before = List.of(
         ReversibleBlockSnapshot.capture(level, first).orElseThrow(),
         ReversibleBlockSnapshot.capture(level, second).orElseThrow()
      );
      level.setBlock(first, Blocks.STONE.defaultBlockState(), 2);
      level.setBlock(second, Blocks.STONE.defaultBlockState(), 2);
      var predicted = List.of(
         ReversibleBlockSnapshot.capture(level, first).orElseThrow(),
         ReversibleBlockSnapshot.capture(level, second).orElseThrow()
      );
      UUID owner = UUID.randomUUID();
      Path directory = Files.createTempDirectory("fastformer-legacy-corrections-");
      Path file = directory.resolve(owner + ".dat");
      PersistentRecoveryJournal.atomicWriteCompressed(
         file, PersistentRecoveryJournal.encodePrepared(level.dimension(), before, predicted)
      );
      var journal = new PersistentRecoveryJournal(file, level.dimension());
      try {
         level.setBlock(first, Blocks.GOLD_BLOCK.defaultBlockState(), 2);
         level.setBlock(second, Blocks.DIAMOND_BLOCK.defaultBlockState(), 2);
         var actual = Map.of(
            first, ReversibleBlockSnapshot.capture(level, first).orElseThrow(),
            second, ReversibleBlockSnapshot.capture(level, second).orElseThrow()
         );
         helper.assertTrue(journal.finalizeAfter(actual).join(), "could not write journal corrections");
         Path correction = file.resolveSibling(file.getFileName().toString().replace(".dat", ".delta"));
         var validCorrection = NbtIo.readCompressed(correction, NbtAccounter.create(1024 * 1024));
         var invalidCorrection = validCorrection.copy();
         invalidCorrection.putLongArray("Positions", new long[] {second.asLong(), first.asLong()});
         PersistentRecoveryJournal.atomicWriteCompressed(correction, invalidCorrection);
         helper.assertTrue(!PersistentRecoveryJournal.recoverOne(level.getServer(), file, false), "unordered correction was accepted");
         for (var snapshot : actual.values()) {
            helper.assertTrue(snapshot.matches(level, snapshot.pos()), "invalid journal changed the world before rejection");
         }
         PersistentRecoveryJournal.atomicWriteCompressed(correction, validCorrection);
         helper.assertTrue(PersistentRecoveryJournal.recoverOne(level.getServer(), file, false), "journal rollback failed");
         for (var snapshot : before) {
            helper.assertTrue(snapshot.matches(level, snapshot.pos()), "rollback did not restore before state");
         }
         helper.assertTrue(PersistentRecoveryJournal.recoverOne(level.getServer(), file, true), "journal replay failed");
         for (var snapshot : actual.values()) {
            helper.assertTrue(snapshot.matches(level, snapshot.pos()), "replay did not restore corrected after state");
         }
         helper.succeed();
      } finally {
         journal.discardUnused();
         Files.deleteIfExists(directory);
      }
   }

   @GameTest(template = "fastformergametests.empty", timeoutTicks = 200)
   public static void startupScanRestoresPreparedJournalBeforeCleanup(GameTestHelper helper) throws Exception {
      var level = helper.getLevel();
      BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
      var before = ReversibleBlockSnapshot.capture(level, pos).orElseThrow();
      level.setBlock(pos, Blocks.GOLD_BLOCK.defaultBlockState(), 2);
      var after = ReversibleBlockSnapshot.capture(level, pos).orElseThrow();
      Path directory = recoveryDirectory(helper);
      Path journal = directory.resolve("00000000000000000001-" + UUID.randomUUID() + ".dat");
      Files.createDirectories(directory);
      PersistentRecoveryJournal.atomicWriteCompressed(
         journal, PersistentRecoveryJournal.encodePrepared(level.dimension(), List.of(before), List.of(after))
      );

      try {
         helper.assertTrue(PersistentRecoveryJournal.recoverAll(level.getServer()), "startup recovery scan failed");
         helper.assertTrue(before.matches(level, pos), "startup scan did not roll back the prepared journal");
         helper.assertTrue(!Files.exists(journal), "startup scan retained a journal after its durable save");
         helper.assertTrue(PersistentRecoveryJournal.writesAllowed(), "successful startup recovery left writes blocked");
         helper.succeed();
      } finally {
         before.restore(level, 2);
         Files.deleteIfExists(journal);
         deleteDirectoryIfEmpty(directory);
         PersistentRecoveryJournal.resetWriteGateForTest();
      }
   }

   @GameTest(template = "fastformergametests.empty", timeoutTicks = 200)
   public static void startupScanRetainsJournalUntilDurableSaveSucceeds(GameTestHelper helper) throws Exception {
      var level = helper.getLevel();
      BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
      var before = ReversibleBlockSnapshot.capture(level, pos).orElseThrow();
      level.setBlock(pos, Blocks.DIAMOND_BLOCK.defaultBlockState(), 2);
      var after = ReversibleBlockSnapshot.capture(level, pos).orElseThrow();
      Path directory = recoveryDirectory(helper);
      Path journal = directory.resolve("00000000000000000004-" + UUID.randomUUID() + ".dat");
      Files.createDirectories(directory);
      PersistentRecoveryJournal.atomicWriteCompressed(
         journal, PersistentRecoveryJournal.encodePrepared(level.dimension(), List.of(before), List.of(after))
      );

      try {
         helper.assertTrue(
            !PersistentRecoveryJournal.recoverAll(level.getServer(), () -> false),
            "startup recovery ignored a failed durable save"
         );
         helper.assertTrue(before.matches(level, pos), "startup recovery did not apply before the save attempt");
         helper.assertTrue(Files.exists(journal), "startup recovery deleted the journal after a failed save");
         helper.assertTrue(!PersistentRecoveryJournal.writesAllowed(), "failed durable save did not block writes");

         helper.assertTrue(PersistentRecoveryJournal.recoverAll(level.getServer()), "startup recovery retry failed");
         helper.assertTrue(before.matches(level, pos), "startup recovery retry changed the restored state");
         helper.assertTrue(!Files.exists(journal), "successful retry did not clean up the journal");
         helper.assertTrue(PersistentRecoveryJournal.writesAllowed(), "successful retry left writes blocked");
         helper.succeed();
      } finally {
         before.restore(level, 2);
         Files.deleteIfExists(journal);
         deleteDirectoryIfEmpty(directory);
         PersistentRecoveryJournal.resetWriteGateForTest();
      }
   }

   @GameTest(template = "fastformergametests.empty", timeoutTicks = 200)
   public static void startupScanRecoversSegmentedJournal(GameTestHelper helper) throws Exception {
      var level = helper.getLevel();
      BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
      var before = ReversibleBlockSnapshot.capture(level, pos).orElseThrow();
      level.setBlock(pos, Blocks.EMERALD_BLOCK.defaultBlockState(), 2);
      var after = ReversibleBlockSnapshot.capture(level, pos).orElseThrow();
      Path recoveryDirectory = recoveryDirectory(helper);
      UUID operation = UUID.randomUUID();
      UUID owner = UUID.randomUUID();
      Path journal = recoveryDirectory.resolve("00000000000000000005-" + owner + "-" + operation);
      Files.createDirectories(journal);
      RecoveryJournalManifest.write(
         journal.resolve("manifest.dat"), operation, owner, level.dimension().location().toString(), 1
      );
      RecoveryJournalSegment.write(
         journal.resolve("segment-000000.dat"), operation, 0,
         PersistentRecoveryJournal.encodePrepared(level.dimension(), List.of(before), List.of(after))
      );

      try {
         helper.assertTrue(PersistentRecoveryJournal.recoverAll(level.getServer()), "segmented startup recovery failed");
         helper.assertTrue(before.matches(level, pos), "segmented startup recovery did not roll back the world");
         helper.assertTrue(!Files.exists(journal), "segmented startup journal was not cleaned up after save");
         helper.assertTrue(PersistentRecoveryJournal.writesAllowed(), "segmented recovery left writes blocked");
         helper.succeed();
      } finally {
         before.restore(level, 2);
         deleteJournalDirectory(journal);
         deleteDirectoryIfEmpty(recoveryDirectory);
         PersistentRecoveryJournal.resetWriteGateForTest();
      }
   }

   @GameTest(template = "fastformergametests.empty", timeoutTicks = 100)
   public static void startupScanRetainsUnsafeJournalsAndBlocksWrites(GameTestHelper helper) throws Exception {
      var level = helper.getLevel();
      BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
      var unchanged = ReversibleBlockSnapshot.capture(level, pos).orElseThrow();
      Path directory = recoveryDirectory(helper);
      String identity = UUID.randomUUID().toString();
      Path corrupt = directory.resolve("00000000000000000002-" + identity + ".dat");
      Path unknownDimension = directory.resolve("00000000000000000003-" + identity + ".dat");
      Files.createDirectories(directory);
      Files.write(corrupt, new byte[] {0x01, 0x02, 0x03});
      var payload = PersistentRecoveryJournal.encodePrepared(
         level.dimension(), List.of(unchanged), List.of(unchanged)
      );
      payload.putString("Dimension", "fastformer:missing_dimension");
      PersistentRecoveryJournal.atomicWriteCompressed(unknownDimension, payload);

      try {
         helper.assertTrue(!PersistentRecoveryJournal.recoverAll(level.getServer()), "unsafe startup journals were accepted");
         helper.assertTrue(!PersistentRecoveryJournal.writesAllowed(), "unsafe startup journals did not block new writes");
         helper.assertTrue(Files.exists(corrupt), "corrupt startup journal was deleted");
         helper.assertTrue(Files.exists(unknownDimension), "unknown-dimension journal was deleted");
         helper.assertTrue(unchanged.matches(level, pos), "rejected startup journals changed the world");
         helper.succeed();
      } finally {
         Files.deleteIfExists(corrupt);
         Files.deleteIfExists(unknownDimension);
         deleteDirectoryIfEmpty(directory);
         PersistentRecoveryJournal.resetWriteGateForTest();
      }
   }

   private static Path recoveryDirectory(GameTestHelper helper) {
      return helper.getLevel().getServer().getWorldPath(LevelResource.ROOT).resolve("fastformer-recovery");
   }

   private static void deleteDirectoryIfEmpty(Path directory) throws Exception {
      if (!Files.isDirectory(directory)) {
         return;
      }
      try (var files = Files.list(directory)) {
         if (files.findAny().isPresent()) {
            return;
         }
      }
      Files.deleteIfExists(directory);
   }

   private static void deleteJournalDirectory(Path directory) throws Exception {
      if (!Files.isDirectory(directory)) {
         return;
      }
      try (var files = Files.list(directory)) {
         for (Path file : files.toList()) {
            Files.deleteIfExists(file);
         }
      }
      Files.deleteIfExists(directory);
   }
}
