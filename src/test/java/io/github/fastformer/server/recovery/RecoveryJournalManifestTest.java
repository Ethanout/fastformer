package io.github.fastformer.server.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.fastformer.common.persistence.NbtEnvelopeFile;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecoveryJournalManifestTest {
   @TempDir
   Path temporaryDirectory;

   @Test
   void roundTripPreservesOperationIdentityAndLimits() throws Exception {
      Path file = this.temporaryDirectory.resolve("manifest.dat");
      UUID operation = UUID.randomUUID();
      UUID owner = UUID.randomUUID();
      RecoveryJournalManifest.write(file, operation, owner, "minecraft:overworld", 64);

      RecoveryJournalManifest.Manifest manifest = RecoveryJournalManifest.read(file);
      assertEquals(operation, manifest.operationId());
      assertEquals(owner, manifest.owner());
      assertEquals("minecraft:overworld", manifest.dimension());
      assertEquals(64, manifest.segmentLimit());
   }

   @Test
   void malformedManifestIsRejected() throws Exception {
      Path file = this.temporaryDirectory.resolve("manifest.dat");
      RecoveryJournalManifest.write(file, UUID.randomUUID(), UUID.randomUUID(), "minecraft:overworld", 1);
      var root = NbtEnvelopeFile.read(file, RecoveryJournalManifest.format(), 1024L * 1024L);
      root.putString("OperationId", "invalid");
      NbtEnvelopeFile.write(file, RecoveryJournalManifest.format(), root, 1024L * 1024L);

      assertThrows(java.io.IOException.class, () -> RecoveryJournalManifest.read(file));
   }

   @Test
   void invalidDimensionIsRejected() throws Exception {
      Path file = this.temporaryDirectory.resolve("manifest.dat");
      org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () ->
         RecoveryJournalManifest.write(file, UUID.randomUUID(), UUID.randomUUID(), "not a dimension", 1)
      );
   }

   @Test
   void excessiveSegmentLimitIsRejected() {
      assertThrows(IllegalArgumentException.class, () ->
         RecoveryJournalManifest.write(
            this.temporaryDirectory.resolve("manifest.dat"),
            UUID.randomUUID(), UUID.randomUUID(), "minecraft:overworld", 1_000_001
         )
      );
   }

}
