package io.github.fastformer.fastplace.world;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PersistentRecoverySegmentValidationTest {
   @TempDir
   Path temporaryDirectory;

   @Test
   void sealedEnvelopeWithInvalidWorldPayloadBlocksWrites() throws Exception {
      Path operationDirectory = Files.createDirectory(this.temporaryDirectory.resolve("operation"));
      UUID operation = UUID.randomUUID();
      UUID owner = UUID.randomUUID();
      RecoveryJournalManifest.write(
         operationDirectory.resolve("manifest.dat"), operation, owner, "minecraft:overworld", 4
      );
      RecoveryJournalSegment.write(
         operationDirectory.resolve("segment-000000.dat"), operation, 0, new CompoundTag()
      );
      var segments = RecoveryJournalSegments.readComplete(operationDirectory, operation, 1);
      RecoveryJournalSeal.write(
         operationDirectory.resolve("seal.done"), operation, 1, RecoveryJournalSegments.digest(segments)
      );

      assertFalse(PersistentRecoveryJournal.validateSegmentDirectories(List.of(operationDirectory)));
      assertTrue(Files.exists(operationDirectory.resolve("manifest.dat")));
      assertTrue(Files.exists(operationDirectory.resolve("segment-000000.dat")));
      assertTrue(Files.exists(operationDirectory.resolve("seal.done")));
   }

   @Test
   void legacyJournalFilesDoNotTriggerSegmentGate() throws Exception {
      Path legacy = Files.createFile(this.temporaryDirectory.resolve("operation.dat"));
      assertTrue(PersistentRecoveryJournal.validateSegmentDirectories(List.of(legacy)));
   }

   @Test
   void missingSealBlocksValidation() throws Exception {
      Path operationDirectory = Files.createDirectory(this.temporaryDirectory.resolve("operation"));
      UUID operation = UUID.randomUUID();
      RecoveryJournalManifest.write(
         operationDirectory.resolve("manifest.dat"), operation, UUID.randomUUID(), "minecraft:overworld", 1
      );
      RecoveryJournalSegment.write(
         operationDirectory.resolve("segment-000000.dat"), operation, 0, new CompoundTag()
      );

      assertFalse(PersistentRecoveryJournal.validateSegmentDirectories(List.of(operationDirectory)));
   }

   @Test
   void digestMismatchBlocksValidation() throws Exception {
      Path operationDirectory = Files.createDirectory(this.temporaryDirectory.resolve("operation"));
      UUID operation = UUID.randomUUID();
      RecoveryJournalManifest.write(
         operationDirectory.resolve("manifest.dat"), operation, UUID.randomUUID(), "minecraft:overworld", 1
      );
      CompoundTag original = new CompoundTag();
      original.putInt("Value", 1);
      RecoveryJournalSegment.write(
         operationDirectory.resolve("segment-000000.dat"), operation, 0, original
      );
      var segments = RecoveryJournalSegments.readComplete(operationDirectory, operation, 1);
      RecoveryJournalSeal.write(
         operationDirectory.resolve("seal.done"), operation, 1, RecoveryJournalSegments.digest(segments)
      );
      CompoundTag replacement = new CompoundTag();
      replacement.putInt("Value", 2);
      RecoveryJournalSegment.write(
         operationDirectory.resolve("segment-000000.dat"), operation, 0, replacement
      );

      assertFalse(PersistentRecoveryJournal.validateSegmentDirectories(List.of(operationDirectory)));
   }

   @Test
   void sealBeyondManifestLimitBlocksValidation() throws Exception {
      Path operationDirectory = Files.createDirectory(this.temporaryDirectory.resolve("operation"));
      UUID operation = UUID.randomUUID();
      RecoveryJournalManifest.write(
         operationDirectory.resolve("manifest.dat"), operation, UUID.randomUUID(), "minecraft:overworld", 1
      );
      RecoveryJournalSegment.write(
         operationDirectory.resolve("segment-000000.dat"), operation, 0, new CompoundTag()
      );
      var segments = RecoveryJournalSegments.readComplete(operationDirectory, operation, 1);
      RecoveryJournalSeal.write(
         operationDirectory.resolve("seal.done"), operation, 2, RecoveryJournalSegments.digest(segments)
      );

      assertFalse(PersistentRecoveryJournal.validateSegmentDirectories(List.of(operationDirectory)));
   }

   @Test
   void manifestOnlyDirectoryBlocksValidationUntilSegmentsAreSealed() throws Exception {
      Path operationDirectory = Files.createDirectory(this.temporaryDirectory.resolve("operation"));
      RecoveryJournalManifest.write(
         operationDirectory.resolve("manifest.dat"), UUID.randomUUID(), UUID.randomUUID(),
         "minecraft:overworld", 1
      );

      assertFalse(PersistentRecoveryJournal.validateSegmentDirectories(List.of(operationDirectory)));
   }

   @Test
   void nullDirectoryCollectionBlocksValidation() {
      assertFalse(PersistentRecoveryJournal.validateSegmentDirectories(null));
   }
}
