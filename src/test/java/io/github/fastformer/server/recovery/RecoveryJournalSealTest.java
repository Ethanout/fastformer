package io.github.fastformer.server.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.fastformer.common.persistence.NbtEnvelopeFile;
import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecoveryJournalSealTest {
   @TempDir
   Path temporaryDirectory;

   @Test
   void sealRoundTripPreservesCountAndDigest() throws Exception {
      Path file = this.temporaryDirectory.resolve("seal.done");
      UUID operation = UUID.randomUUID();
      RecoveryJournalSeal.write(file, operation, 3, 42L);

      RecoveryJournalSeal.Seal seal = RecoveryJournalSeal.read(file, operation);
      assertEquals(3, seal.segmentCount());
      assertEquals(42L, seal.digest());
   }

   @Test
   void sealRejectsWrongOperation() throws Exception {
      Path file = this.temporaryDirectory.resolve("seal.done");
      RecoveryJournalSeal.write(file, UUID.randomUUID(), 1, 0L);

      assertThrows(IOException.class, () -> RecoveryJournalSeal.read(file, UUID.randomUUID()));
   }

   @Test
   void sealRejectsNonPositiveSegmentCount() {
      assertThrows(IllegalArgumentException.class, () ->
         RecoveryJournalSeal.write(
            this.temporaryDirectory.resolve("invalid.done"), UUID.randomUUID(), 0, 0L
         )
      );
   }

   @Test
   void missingDigestCannotMasqueradeAsAValidZeroChecksum() throws Exception {
      Path file = temporaryDirectory.resolve("seal.done");
      UUID operation = UUID.randomUUID();
      RecoveryJournalSeal.write(file, operation, 1, 0L);
      var root = NbtEnvelopeFile.read(file, RecoveryJournalSeal.format(), 1024L * 1024L);
      root.remove("Digest");
      NbtEnvelopeFile.write(file, RecoveryJournalSeal.format(), root, 1024L * 1024L);

      assertThrows(IOException.class, () -> RecoveryJournalSeal.read(file, operation));
   }

   @Test
   void sealRejectsExcessiveSegmentCountOnRead() throws Exception {
      Path file = this.temporaryDirectory.resolve("seal.done");
      UUID operation = UUID.randomUUID();
      RecoveryJournalSeal.write(file, operation, 1_000_000, 0L);

      var root = NbtEnvelopeFile.read(file, RecoveryJournalSeal.format(), 1024L * 1024L);
      root.putInt("SegmentCount", 1_000_001);
      NbtEnvelopeFile.write(file, RecoveryJournalSeal.format(), root, 1024L * 1024L);
      assertThrows(IOException.class, () -> RecoveryJournalSeal.read(file, operation));
   }
}
