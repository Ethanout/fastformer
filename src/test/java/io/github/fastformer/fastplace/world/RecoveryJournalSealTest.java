package io.github.fastformer.fastplace.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
   void sealRejectsExcessiveSegmentCountOnRead() throws Exception {
      Path file = this.temporaryDirectory.resolve("seal.done");
      UUID operation = UUID.randomUUID();
      RecoveryJournalSeal.write(file, operation, 1_000_000, 0L);

      net.minecraft.nbt.CompoundTag root = net.minecraft.nbt.NbtIo.readCompressed(
         file, net.minecraft.nbt.NbtAccounter.unlimitedHeap()
      );
      root.putInt("SegmentCount", 1_000_001);
      io.github.fastformer.fastplace.world.PersistentRecoveryJournal.atomicWriteCompressed(file, root);
      assertThrows(IOException.class, () -> RecoveryJournalSeal.read(file, operation));
   }
}
