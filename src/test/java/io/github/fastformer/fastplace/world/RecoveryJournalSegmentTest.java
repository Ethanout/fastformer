package io.github.fastformer.fastplace.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecoveryJournalSegmentTest {
   @TempDir
   Path temporaryDirectory;

   @Test
   void roundTripPreservesPayloadAndSequence() throws Exception {
      Path file = this.temporaryDirectory.resolve("segment-000001.dat");
      UUID operation = UUID.randomUUID();
      CompoundTag payload = new CompoundTag();
      payload.putInt("Count", 4);

      RecoveryJournalSegment.write(file, operation, 1, payload);

      assertEquals(4, RecoveryJournalSegment.read(file, operation, 1).getInt("Count"));
   }

   @Test
   void wrongSequenceOrOperationIsRejected() throws Exception {
      Path file = this.temporaryDirectory.resolve("segment.dat");
      UUID operation = UUID.randomUUID();
      RecoveryJournalSegment.write(file, operation, 2, new CompoundTag());

      assertThrows(java.io.IOException.class, () -> RecoveryJournalSegment.read(file, operation, 1));
      assertThrows(java.io.IOException.class, () -> RecoveryJournalSegment.read(file, UUID.randomUUID(), 2));
   }

   @Test
   void rejectsSequenceOutsideSegmentProtocol() {
      assertThrows(IllegalArgumentException.class, () ->
         RecoveryJournalSegment.write(
            this.temporaryDirectory.resolve("segment-1000000.dat"),
            UUID.randomUUID(), 1_000_000, new CompoundTag()
         )
      );
   }
}
