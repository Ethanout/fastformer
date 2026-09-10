package io.github.fastformer.fastplace.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
      net.minecraft.nbt.CompoundTag root = net.minecraft.nbt.NbtIo.readCompressed(
         file, net.minecraft.nbt.NbtAccounter.create(1024L * 1024L)
      );
      root.putString("OperationId", "invalid");
      RecoveryJournalSegmentTestHelper.write(file, root);

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

   private static final class RecoveryJournalSegmentTestHelper {
      private RecoveryJournalSegmentTestHelper() {
      }

      static void write(Path file, net.minecraft.nbt.CompoundTag root) throws Exception {
         try (java.io.OutputStream output = java.nio.file.Files.newOutputStream(file)) {
            net.minecraft.nbt.NbtIo.writeCompressed(root, output);
         }
      }
   }
}
