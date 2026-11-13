package io.github.fastformer.server.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.fastformer.core.persist.Envelope;
import io.github.fastformer.core.persist.EnvelopeFile;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.UUID;
import java.nio.file.Files;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
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

   @Test
   void sharedEnvelopeChecksumsTheStoredIdentityAndPayload() throws Exception {
      Path file = temporaryDirectory.resolve("segment-000000.dat");
      UUID operation = UUID.randomUUID();
      CompoundTag payload = new CompoundTag();
      payload.putString("Text", "binary payload");
      RecoveryJournalSegment.write(file, operation, 0, payload);
      byte[] bytes = EnvelopeFile.read(file, RecoveryJournalSegment.format(), RecoveryJournalSegment.MAX_BYTES);
      assertEquals(Envelope.checksum(RecoveryJournalSegment.format(), bytes),
         RecoveryJournalSegment.readVerified(file, operation, 0).checksum());
      bytes = Files.readAllBytes(file);
      bytes[bytes.length - 1] ^= 1;
      Files.write(file, bytes);
      assertThrows(java.io.IOException.class, () -> RecoveryJournalSegment.read(file, operation, 0));
   }

   @Test
   void previousSegmentFormatIsRejected() throws Exception {
      UUID operation = UUID.randomUUID();
      CompoundTag payload = new CompoundTag();
      payload.putInt("Count", 9);
      CompoundTag root = new CompoundTag();
      root.putInt("Version", 1);
      root.putString("OperationId", operation.toString());
      root.putInt("Sequence", 0);
      root.put("Payload", payload);
      root.putLong("PayloadCrc", 0L);
      NbtIo.writeCompressed(root, temporaryDirectory.resolve("segment-000000.dat"));
      assertThrows(java.io.IOException.class, () ->
         RecoveryJournalSegment.read(temporaryDirectory.resolve("segment-000000.dat"), operation, 0));
   }

   @Test
   void validChecksumCannotHideTrailingPayloadBytes() throws Exception {
      Path file = temporaryDirectory.resolve("segment.dat");
      UUID operation = UUID.randomUUID();
      RecoveryJournalSegment.write(file, operation, 0, new CompoundTag());
      byte[] compressed = EnvelopeFile.read(file, RecoveryJournalSegment.format(), RecoveryJournalSegment.MAX_BYTES);
      byte[] original;
      try (var input = new GZIPInputStream(new ByteArrayInputStream(compressed))) { original = input.readAllBytes(); }
      var bytes = new ByteArrayOutputStream();
      try (var output = new GZIPOutputStream(bytes)) {
         output.write(original);
         output.write(1);
      }
      EnvelopeFile.write(file, RecoveryJournalSegment.format(), bytes.toByteArray());
      assertThrows(java.io.IOException.class, () -> RecoveryJournalSegment.read(file, operation, 0));
   }
}
