package io.github.fastformer.server.recovery;

import io.github.fastformer.server.history.HistoryRecoveryReconciler;

import io.github.fastformer.FastFormer;
import io.github.fastformer.common.persistence.MinecraftDataVersion;
import io.github.fastformer.common.persistence.NbtEnvelopeFile;
import io.github.fastformer.server.worldwrite.snapshot.ReversibleBlockSnapshot;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(FastFormer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class JournalRecoveryGameTests {
   private JournalRecoveryGameTests() {
   }

   @GameTest(template = "fastformergametests.empty", timeoutTicks = 200, batch = "startup_version_quarantine")
   public static void incompatibleDataIsQuarantinedBeforeAnyWorldReplay(GameTestHelper helper) throws Exception {
      var level = helper.getLevel();
      BlockPos position = helper.absolutePos(new BlockPos(1, 1, 1));
      var before = ReversibleBlockSnapshot.capture(level, position).orElseThrow();
      level.setBlock(position, Blocks.GOLD_BLOCK.defaultBlockState(), 2);
      var after = ReversibleBlockSnapshot.capture(level, position).orElseThrow();
      Path root = recoveryDirectory(helper);
      Path quarantine = root.resolve("quarantine");
      var journals = new java.util.ArrayList<Path>();
      try {
         for (Integer version : java.util.Arrays.asList(null, MinecraftDataVersion.current() - 1,
            MinecraftDataVersion.current() + 1)) {
            UUID operation = UUID.randomUUID();
            Path directory = root.resolve("00000000000000000006-" + operation);
            journals.add(directory);
            Files.createDirectories(directory);
            RecoveryJournalManifest.write(directory.resolve("manifest.dat"), operation, UUID.randomUUID(),
               level.dimension().location().toString(), 1);
            var payload = RecoveryJournalCodec.encodePrepared(level.dimension(), List.of(before), List.of(after));
            if (version == null) payload.remove("DataVersion");
            else payload.putInt("DataVersion", version);
            RecoveryJournalSegment.write(directory.resolve("segment-000000.dat"), operation, 0, payload);
         }
         helper.assertTrue(RecoverySystem.of(level.getServer()).recoverAll(new HistoryRecoveryReconciler(io.github.fastformer.server.history.HistorySystem.of(level.getServer()))),
            "known version incompatibility blocked all operations");
         helper.assertTrue(after.matches(level, position), "incompatible journal mutated the world");
         helper.assertTrue(RecoverySystem.of(helper.getLevel().getServer()).writeGate().writesAllowed(), "quarantined versions left writes blocked");
         for (Path journal : journals) {
            helper.assertTrue(!Files.exists(journal), "incompatible journal remained in the active namespace");
            try (var entries = Files.list(quarantine)) {
               var retained = entries.filter(path -> path.getFileName().toString()
                  .startsWith(journal.getFileName().toString() + "-")).toList();
               helper.assertTrue(retained.size() == 1, "incompatible journal was not retained exactly once");
               helper.assertTrue(Files.isRegularFile(retained.getFirst().resolve("reason.txt")),
                  "quarantine lost its rejection reason");
               helper.assertTrue(Files.isRegularFile(retained.getFirst().resolve("segment-000000.dat")),
                  "quarantine lost its original segment");
            }
         }
         helper.succeed();
      } finally {
         before.restore(level, 2);
         for (Path journal : journals) {
            deleteJournalDirectory(journal);
            if (Files.isDirectory(quarantine)) try (var entries = Files.list(quarantine)) {
               for (Path retained : entries.filter(path -> path.getFileName().toString()
                  .startsWith(journal.getFileName().toString() + "-")).toList()) deleteJournalDirectory(retained);
            }
         }
         deleteDirectoryIfEmpty(quarantine);
         deleteDirectoryIfEmpty(root);
         RecoveryJournalTestAccess.resetWriteGate(helper.getLevel().getServer());
      }
   }

   @GameTest(template = "fastformergametests.empty", timeoutTicks = 100, batch = "startup_journal_recovery")
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
            RecoveryJournalCodec.encodePrepared(level.dimension(), List.of(beforeFirst), List.of(afterFirst)));
         RecoveryJournalSegment.write(directory.resolve("segment-000001.dat"), operation, 1,
            RecoveryJournalCodec.encodePrepared(level.dimension(), List.of(beforeSecond), List.of(afterSecond)));
         RecoveryJournalSeal.write(directory.resolve("seal.done"), operation, 2,
            RecoveryJournalSegments.digest(RecoveryJournalSegments.readComplete(directory, operation, 2)));

         helper.assertTrue(JournalReplayer.restore(level.getServer(), directory), "sealed replay failed");
         helper.assertTrue(afterFirst.matches(level, first) && afterSecond.matches(level, second), "sealed replay lost contents");
         level.setBlock(first, Blocks.EMERALD_BLOCK.defaultBlockState(), 2);
         helper.assertTrue(JournalReplayer.restore(level.getServer(), directory), "repeated replay failed");
         helper.assertTrue(level.getBlockState(first).is(Blocks.EMERALD_BLOCK), "repeated replay overwrote an external change");
         helper.assertTrue(afterSecond.matches(level, second), "repeated replay changed an already restored block");
         helper.assertTrue(Files.exists(directory.resolve("seal.done")), "replay deleted journal before durable save");
         Files.delete(directory.resolve("seal.done"));
         helper.assertTrue(JournalReplayer.restore(level.getServer(), directory), "unsealed rollback failed");
         helper.assertTrue(level.getBlockState(first).is(Blocks.EMERALD_BLOCK), "rollback overwrote external change");
         helper.assertTrue(beforeSecond.matches(level, second), "unsealed rollback did not restore before");
         helper.assertTrue(JournalReplayer.restore(level.getServer(), directory), "repeated rollback failed");
         helper.succeed();
      } finally {
         try (var files = Files.list(directory)) {
            for (Path file : files.toList()) Files.delete(file);
         }
         Files.delete(directory);
      }
   }

   @GameTest(template = "fastformergametests.empty", timeoutTicks = 100, batch = "startup_journal_recovery")
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
      Path directory = Files.createTempDirectory("fastformer-segment-corrections-");
      var journal = PersistentRecoveryJournal.writeSegmentedJournal(
         directory, owner, UUID.randomUUID(), level.dimension(), before, predicted);
      try {
         level.setBlock(first, Blocks.GOLD_BLOCK.defaultBlockState(), 2);
         level.setBlock(second, Blocks.DIAMOND_BLOCK.defaultBlockState(), 2);
         var actual = Map.of(
            first, ReversibleBlockSnapshot.capture(level, first).orElseThrow(),
            second, ReversibleBlockSnapshot.capture(level, second).orElseThrow()
         );
         helper.assertTrue(journal.finalizeAfter(actual).join(), "could not write journal corrections");
         Path correction = directory.resolve("correction.delta");
         var validCorrection = RecoveryJournalCodec.readCorrection(correction);
         for (String field : List.of("OperationId", "BaseCount", "BaseDigest")) {
            var mismatched = validCorrection.copy();
            switch (field) {
               case "OperationId" -> mismatched.putString(field, UUID.randomUUID().toString());
               case "BaseCount" -> mismatched.putInt(field, validCorrection.getInt(field) + 1);
               case "BaseDigest" -> mismatched.putLong(field, validCorrection.getLong(field) ^ 1L);
            }
            NbtEnvelopeFile.write(correction, RecoveryJournalCodec.correctionFormat(), mismatched, 1024 * 1024);
            helper.assertTrue(!JournalReplayer.restore(level.getServer(), directory), "mismatched correction was accepted: " + field);
            for (var snapshot : actual.values()) {
               helper.assertTrue(snapshot.matches(level, snapshot.pos()), "mismatched correction changed the world: " + field);
            }
         }
         var invalidCorrection = validCorrection.copy();
         invalidCorrection.putLongArray("Positions", new long[] {second.asLong(), first.asLong()});
         NbtEnvelopeFile.write(correction, RecoveryJournalCodec.correctionFormat(), invalidCorrection, 1024 * 1024);
         helper.assertTrue(!JournalReplayer.restore(level.getServer(), directory), "unordered correction was accepted");
         for (var snapshot : actual.values()) {
            helper.assertTrue(snapshot.matches(level, snapshot.pos()), "invalid journal changed the world before rejection");
         }
         NbtEnvelopeFile.write(correction, RecoveryJournalCodec.correctionFormat(), validCorrection, 1024 * 1024);
         helper.assertTrue(JournalReplayer.restore(level.getServer(), directory), "journal rollback failed");
         for (var snapshot : before) {
            helper.assertTrue(snapshot.matches(level, snapshot.pos()), "rollback did not restore before state");
         }
         helper.assertTrue(journal.completeFinalized(), "could not seal corrected journal");
         helper.assertTrue(JournalReplayer.restore(level.getServer(), directory), "journal replay failed");
         for (var snapshot : actual.values()) {
            helper.assertTrue(snapshot.matches(level, snapshot.pos()), "replay did not restore corrected after state");
         }
         helper.succeed();
      } finally {
         deleteJournalDirectory(directory);
      }
   }

   @GameTest(template = "fastformergametests.empty", timeoutTicks = 200, batch = "startup_history_reconciliation")
   public static void failedHistoryReconciliationRetainsSealedJournalUntilRetry(GameTestHelper helper) throws Exception {
      var level = helper.getLevel();
      BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
      var before = ReversibleBlockSnapshot.capture(level, pos).orElseThrow();
      level.setBlock(pos, Blocks.GOLD_BLOCK.defaultBlockState(), 2);
      var after = ReversibleBlockSnapshot.capture(level, pos).orElseThrow();
      UUID owner = UUID.randomUUID();
      UUID operation = UUID.randomUUID();
      Path root = recoveryDirectory(helper);
      Path directory = root.resolve("00000000000000000007-" + operation);
      PersistentRecoveryJournal.writeSegmentedJournal(
         directory, owner, operation, level.dimension(), List.of(before), List.of(after));
      var journal = new PersistentRecoveryJournal(directory, level.dimension());
      var identities = new java.util.ArrayList<List<UUID>>();
      int[] saves = {0};
      try {
         helper.assertTrue(journal.finalizeAfter(Map.of()).join(), "journal finalization failed");
         helper.assertTrue(journal.sealFinalizedForHistory(), "journal sealing failed");
         before.restore(level, 2);
         boolean recovered = RecoverySystem.of(level.getServer()).recoverAll((player, entry) -> {
            if (!entry.equals(operation)) return new HistoryRecoveryReconciler(io.github.fastformer.server.history.HistorySystem.of(level.getServer())).reconcile(player, entry);
            identities.add(List.of(player, entry));
            return java.util.concurrent.CompletableFuture.failedFuture(new java.io.IOException("index unavailable"));
         }, () -> { saves[0]++; return true; });
         helper.assertFalse(recovered, "failed history reconciliation was accepted");
         helper.assertTrue(after.matches(level, pos), "sealed world changes were not replayed");
         helper.assertTrue(Files.exists(directory.resolve("seal.done")), "failed reconciliation lost the journal");
         helper.assertFalse(RecoverySystem.of(helper.getLevel().getServer()).writeGate().writesAllowed(), "failed reconciliation admitted new writes");
         int previousSaves = saves[0];
         recovered = RecoverySystem.of(level.getServer()).recoverAll((player, entry) -> {
            if (!entry.equals(operation)) return new HistoryRecoveryReconciler(io.github.fastformer.server.history.HistorySystem.of(level.getServer())).reconcile(player, entry);
            identities.add(List.of(player, entry));
            return java.util.concurrent.CompletableFuture.completedFuture(null);
         }, () -> { saves[0]++; return true; });
         helper.assertTrue(recovered, "history reconciliation did not retry");
         helper.assertTrue(identities.equals(List.of(List.of(owner, operation), List.of(owner, operation))),
            "reconciliation used the wrong owner or operation");
         helper.assertTrue(saves[0] == previousSaves + 1, "successful reconciliation skipped durable world save");
         helper.assertFalse(Files.exists(directory), "reconciled journal was not cleaned up");
         helper.assertTrue(RecoverySystem.of(helper.getLevel().getServer()).writeGate().writesAllowed(), "successful retry left writes blocked");
         helper.succeed();
      } finally {
         before.restore(level, 2);
         deleteJournalDirectory(directory);
         deleteDirectoryIfEmpty(root);
         RecoveryJournalTestAccess.resetWriteGate(helper.getLevel().getServer());
      }
   }

   @GameTest(template = "fastformergametests.empty", timeoutTicks = 200, batch = "startup_journal_recovery")
   public static void startupScanRestoresPreparedJournalBeforeCleanup(GameTestHelper helper) throws Exception {
      var level = helper.getLevel();
      UUID preparedOperation = UUID.randomUUID();
      BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
      var before = ReversibleBlockSnapshot.capture(level, pos).orElseThrow();
      level.setBlock(pos, Blocks.GOLD_BLOCK.defaultBlockState(), 2);
      var after = ReversibleBlockSnapshot.capture(level, pos).orElseThrow();
      Path directory = recoveryDirectory(helper);
      Path journal = directory.resolve("00000000000000000001-" + UUID.randomUUID());
      Files.createDirectories(directory);
      PersistentRecoveryJournal.writeSegmentedJournal(
         journal, UUID.randomUUID(), preparedOperation, level.dimension(), List.of(before), List.of(after));

      try {
         helper.assertTrue(RecoverySystem.of(level.getServer()).recoverAll((owner, operation) -> {
            if (preparedOperation.equals(operation)) {
               throw new AssertionError("rolled-back journal must not publish committed history");
            }
            return new HistoryRecoveryReconciler(io.github.fastformer.server.history.HistorySystem.of(level.getServer()))
               .reconcile(owner, operation);
         }), "startup recovery scan failed");
         helper.assertTrue(before.matches(level, pos), "startup scan did not roll back the prepared journal");
         helper.assertTrue(!Files.exists(journal), "startup scan retained a journal after its durable save");
         helper.assertTrue(RecoverySystem.of(helper.getLevel().getServer()).writeGate().writesAllowed(), "successful startup recovery left writes blocked");
         helper.succeed();
      } finally {
         before.restore(level, 2);
         deleteJournalDirectory(journal);
         deleteDirectoryIfEmpty(directory);
         RecoveryJournalTestAccess.resetWriteGate(helper.getLevel().getServer());
      }
   }

   @GameTest(template = "fastformergametests.empty", timeoutTicks = 200, batch = "startup_journal_recovery")
   public static void startupScanRetainsJournalUntilDurableSaveSucceeds(GameTestHelper helper) throws Exception {
      var level = helper.getLevel();
      BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
      var before = ReversibleBlockSnapshot.capture(level, pos).orElseThrow();
      level.setBlock(pos, Blocks.DIAMOND_BLOCK.defaultBlockState(), 2);
      var after = ReversibleBlockSnapshot.capture(level, pos).orElseThrow();
      Path directory = recoveryDirectory(helper);
      Path journal = directory.resolve("00000000000000000004-" + UUID.randomUUID());
      Files.createDirectories(directory);
      PersistentRecoveryJournal.writeSegmentedJournal(
         journal, UUID.randomUUID(), UUID.randomUUID(), level.dimension(), List.of(before), List.of(after));

      try {
         helper.assertTrue(
            !RecoverySystem.of(level.getServer()).recoverAll(new HistoryRecoveryReconciler(io.github.fastformer.server.history.HistorySystem.of(level.getServer())), () -> false),
            "startup recovery ignored a failed durable save"
         );
         helper.assertTrue(before.matches(level, pos), "startup recovery did not apply before the save attempt");
         helper.assertTrue(Files.exists(journal), "startup recovery deleted the journal after a failed save");
         helper.assertTrue(!RecoverySystem.of(helper.getLevel().getServer()).writeGate().writesAllowed(), "failed durable save did not block writes");

         helper.assertTrue(RecoverySystem.of(level.getServer()).recoverAll(new HistoryRecoveryReconciler(io.github.fastformer.server.history.HistorySystem.of(level.getServer()))), "startup recovery retry failed");
         helper.assertTrue(before.matches(level, pos), "startup recovery retry changed the restored state");
         helper.assertTrue(!Files.exists(journal), "successful retry did not clean up the journal");
         helper.assertTrue(RecoverySystem.of(helper.getLevel().getServer()).writeGate().writesAllowed(), "successful retry left writes blocked");
         helper.succeed();
      } finally {
         before.restore(level, 2);
         deleteJournalDirectory(journal);
         deleteDirectoryIfEmpty(directory);
         RecoveryJournalTestAccess.resetWriteGate(helper.getLevel().getServer());
      }
   }

   @GameTest(template = "fastformergametests.empty", timeoutTicks = 200, batch = "startup_journal_recovery")
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
         RecoveryJournalCodec.encodePrepared(level.dimension(), List.of(before), List.of(after))
      );

      try {
         helper.assertTrue(RecoverySystem.of(level.getServer()).recoverAll(new HistoryRecoveryReconciler(io.github.fastformer.server.history.HistorySystem.of(level.getServer()))), "segmented startup recovery failed");
         helper.assertTrue(before.matches(level, pos), "segmented startup recovery did not roll back the world");
         helper.assertTrue(!Files.exists(journal), "segmented startup journal was not cleaned up after save");
         helper.assertTrue(RecoverySystem.of(helper.getLevel().getServer()).writeGate().writesAllowed(), "segmented recovery left writes blocked");
         helper.succeed();
      } finally {
         before.restore(level, 2);
         deleteJournalDirectory(journal);
         deleteDirectoryIfEmpty(recoveryDirectory);
         RecoveryJournalTestAccess.resetWriteGate(helper.getLevel().getServer());
      }
   }

   @GameTest(template = "fastformergametests.empty", timeoutTicks = 100, batch = "startup_journal_recovery")
   public static void startupScanRetainsUnsafeJournalsAndBlocksWrites(GameTestHelper helper) throws Exception {
      var level = helper.getLevel();
      BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
      var unchanged = ReversibleBlockSnapshot.capture(level, pos).orElseThrow();
      Path directory = recoveryDirectory(helper);
      String identity = UUID.randomUUID().toString();
      Path corrupt = directory.resolve("00000000000000000002-" + identity);
      Path unknownDimension = directory.resolve("00000000000000000003-" + identity);
      Files.createDirectories(directory);
      Files.createDirectories(corrupt);
      Files.write(corrupt.resolve("manifest.dat"), new byte[] {0x01, 0x02, 0x03});
      PersistentRecoveryJournal.writeSegmentedJournal(
         unknownDimension, UUID.randomUUID(), UUID.randomUUID(),
         net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
            net.minecraft.resources.ResourceLocation.parse("fastformer:missing_dimension")),
         List.of(unchanged), List.of(unchanged));

      try {
         helper.assertTrue(!RecoverySystem.of(level.getServer()).recoverAll(new HistoryRecoveryReconciler(io.github.fastformer.server.history.HistorySystem.of(level.getServer()))), "unsafe startup journals were accepted");
         helper.assertTrue(!RecoverySystem.of(helper.getLevel().getServer()).writeGate().writesAllowed(), "unsafe startup journals did not block new writes");
         helper.assertTrue(Files.exists(corrupt), "corrupt startup journal was deleted");
         helper.assertTrue(Files.exists(unknownDimension), "unknown-dimension journal was deleted");
         helper.assertTrue(unchanged.matches(level, pos), "rejected startup journals changed the world");
         helper.succeed();
      } finally {
         deleteJournalDirectory(corrupt);
         deleteJournalDirectory(unknownDimension);
         deleteDirectoryIfEmpty(directory);
         RecoveryJournalTestAccess.resetWriteGate(helper.getLevel().getServer());
      }
   }

   @GameTest(template = "fastformergametests.empty", timeoutTicks = 200, batch = "startup_journal_recovery")
   public static void finalCorrectionsCoverLaterSegments(GameTestHelper helper) throws Exception {
      var level = helper.getLevel();
      BlockPos first = helper.absolutePos(new BlockPos(1, 1, 1));
      BlockPos second = helper.absolutePos(new BlockPos(2, 1, 1));
      var beforeFirst = ReversibleBlockSnapshot.capture(level, first).orElseThrow();
      var beforeSecond = ReversibleBlockSnapshot.capture(level, second).orElseThrow();
      level.setBlock(first, Blocks.STONE.defaultBlockState(), 2);
      level.setBlock(second, Blocks.STONE.defaultBlockState(), 2);
      var predictedFirst = ReversibleBlockSnapshot.capture(level, first).orElseThrow();
      var predictedSecond = ReversibleBlockSnapshot.capture(level, second).orElseThrow();
      level.setBlock(first, Blocks.GOLD_BLOCK.defaultBlockState(), 2);
      level.setBlock(second, Blocks.DIAMOND_BLOCK.defaultBlockState(), 2);
      var actualFirst = ReversibleBlockSnapshot.capture(level, first).orElseThrow();
      var actualSecond = ReversibleBlockSnapshot.capture(level, second).orElseThrow();
      Path directory = Files.createTempDirectory("fastformer-final-segment-");
      UUID operation = UUID.randomUUID();
      try {
         RecoveryJournalManifest.write(directory.resolve("manifest.dat"), operation, UUID.randomUUID(),
            level.dimension().location().toString(), 2);
         RecoveryJournalSegment.write(directory.resolve("segment-000000.dat"), operation, 0,
            RecoveryJournalCodec.encodePrepared(level.dimension(), List.of(beforeFirst), List.of(predictedFirst)));
         var journal = new PersistentRecoveryJournal(directory, level.dimension());
         helper.assertTrue(journal.appendSegment(List.of(beforeSecond), List.of(predictedSecond)), "append failed");
         helper.assertTrue(journal.finalizeAfter(Map.of(first, actualFirst, second, actualSecond)).join(),
            "finalization rejected the second segment");
         Path secondFile = directory.resolve("segment-000001.dat");
         var originalSecond = RecoveryJournalSegment.read(secondFile, operation, 1);
         var replacedSecond = originalSecond.copy();
         replacedSecond.putString("IntegrityProbe", "different verified segment");
         RecoveryJournalSegment.write(secondFile, operation, 1, replacedSecond);
         helper.assertTrue(!JournalReplayer.restore(level.getServer(), directory), "correction accepted a replaced later segment");
         helper.assertTrue(actualFirst.matches(level, first) && actualSecond.matches(level, second),
            "replaced segment changed the world before correction rejection");
         RecoveryJournalSegment.write(secondFile, operation, 1, originalSecond);
         var segments = RecoveryJournalSegments.inspectComplete(directory, operation, 2);
         RecoveryJournalSeal.write(directory.resolve("seal.done"), operation, segments.count(), segments.digest());
         beforeFirst.restore(level, 2);
         beforeSecond.restore(level, 2);
         helper.assertTrue(JournalReplayer.restore(level.getServer(), directory), "replay failed");
         helper.assertTrue(actualFirst.matches(level, first), "first correction was lost");
         helper.assertTrue(actualSecond.matches(level, second), "later-segment correction was lost");
         helper.succeed();
      } finally {
         deleteJournalDirectory(directory);
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
