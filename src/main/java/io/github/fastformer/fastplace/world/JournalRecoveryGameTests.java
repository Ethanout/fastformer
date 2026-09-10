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
      var journal = PersistentRecoveryJournal.begin(
         level.getServer(), owner, level.dimension(), before, predicted, UUID.randomUUID()
      ).orElseThrow();
      try {
         level.setBlock(first, Blocks.GOLD_BLOCK.defaultBlockState(), 2);
         level.setBlock(second, Blocks.DIAMOND_BLOCK.defaultBlockState(), 2);
         var actual = Map.of(
            first, ReversibleBlockSnapshot.capture(level, first).orElseThrow(),
            second, ReversibleBlockSnapshot.capture(level, second).orElseThrow()
         );
         helper.assertTrue(journal.finalizeAfter(actual).join(), "could not write journal corrections");
         Path directory = level.getServer().getWorldPath(LevelResource.ROOT).resolve("fastformer-recovery");
         Path file;
         try (var files = Files.list(directory)) {
            file = files.filter(path -> path.getFileName().toString().endsWith(owner + ".dat"))
               .findFirst().orElseThrow();
         }
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
      }
   }
}
