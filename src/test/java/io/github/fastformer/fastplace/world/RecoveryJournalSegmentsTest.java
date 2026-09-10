package io.github.fastformer.fastplace.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecoveryJournalSegmentsTest {
   @TempDir
   Path temporaryDirectory;

   @Test
   void readsSegmentsInSequenceOrder() throws Exception {
      UUID operation = UUID.randomUUID();
      for (int sequence = 0; sequence < 2; sequence++) {
         CompoundTag payload = new CompoundTag();
         payload.putInt("Sequence", sequence);
         RecoveryJournalSegment.write(
            this.temporaryDirectory.resolve(String.format("segment-%06d.dat", sequence)),
            operation,
            sequence,
            payload
         );
      }

      var segments = RecoveryJournalSegments.readComplete(this.temporaryDirectory, operation, 2);
      assertEquals(0, segments.get(0).sequence());
      assertEquals(1, segments.get(1).payload().getInt("Sequence"));
   }

   @Test
   void missingSegmentBlocksRecovery() throws Exception {
      UUID operation = UUID.randomUUID();
      RecoveryJournalSegment.write(
         this.temporaryDirectory.resolve("segment-000000.dat"), operation, 0, new CompoundTag()
      );

      assertThrows(IOException.class, () ->
         RecoveryJournalSegments.readComplete(this.temporaryDirectory, operation, 2)
      );
   }

   @Test
   void temporarySegmentBlocksRecovery() throws Exception {
      UUID operation = UUID.randomUUID();
      RecoveryJournalSegment.write(
         this.temporaryDirectory.resolve("segment-000000.dat"), operation, 0, new CompoundTag()
      );
      Files.createFile(this.temporaryDirectory.resolve("segment-000001.dat.tmp-crash"));

      assertThrows(IOException.class, () ->
         RecoveryJournalSegments.readComplete(this.temporaryDirectory, operation, 1)
      );
   }

   @Test
   void digestChangesWhenSegmentPayloadChanges() throws Exception {
      UUID operation = UUID.randomUUID();
      CompoundTag first = new CompoundTag();
      first.putInt("Value", 1);
      RecoveryJournalSegment.write(
         this.temporaryDirectory.resolve("segment-000000.dat"), operation, 0, first
      );
      var original = RecoveryJournalSegments.readComplete(this.temporaryDirectory, operation, 1);
      CompoundTag changed = new CompoundTag();
      changed.putInt("Value", 2);
      RecoveryJournalSegment.write(
         this.temporaryDirectory.resolve("segment-000000.dat"), operation, 0, changed
      );
      var replacement = RecoveryJournalSegments.readComplete(this.temporaryDirectory, operation, 1);
      org.junit.jupiter.api.Assertions.assertNotEquals(
         RecoveryJournalSegments.digest(original), RecoveryJournalSegments.digest(replacement)
      );
   }

   @Test
   void segmentDirectoryWithoutManifestIsRecognizedAsInvalid() throws Exception {
      Files.createFile(this.temporaryDirectory.resolve("segment-000000.dat"));
      assertThrows(IOException.class, () ->
         RecoveryJournalSegments.readComplete(this.temporaryDirectory, UUID.randomUUID(), 1)
      );
   }

   @Test
   void correctionDeltaIsAllowedBesideSegments() throws Exception {
      UUID operation = UUID.randomUUID();
      RecoveryJournalSegment.write(
         this.temporaryDirectory.resolve("segment-000000.dat"), operation, 0, new CompoundTag()
      );
      Files.createFile(this.temporaryDirectory.resolve("correction.delta"));

      var segments = RecoveryJournalSegments.readComplete(this.temporaryDirectory, operation, 1);
      assertEquals(1, segments.size());
   }

   @Test
   void unknownDirectoryFileBlocksRecovery() throws Exception {
      UUID operation = UUID.randomUUID();
      RecoveryJournalSegment.write(
         this.temporaryDirectory.resolve("segment-000000.dat"), operation, 0, new CompoundTag()
      );
      Files.createFile(this.temporaryDirectory.resolve("unexpected.dat"));

      assertThrows(IOException.class, () ->
         RecoveryJournalSegments.readComplete(this.temporaryDirectory, operation, 1)
      );
   }

   @Test
   void excessiveExpectedCountBlocksRecovery() {
      assertThrows(IOException.class, () ->
         RecoveryJournalSegments.readComplete(this.temporaryDirectory, UUID.randomUUID(), 1_000_001)
      );
   }

   @Test
   void emptyDigestInputIsRejected() {
      assertThrows(IllegalArgumentException.class, () -> RecoveryJournalSegments.digest(List.of()));
   }
}
