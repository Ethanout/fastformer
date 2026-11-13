package io.github.fastformer.server.recovery;

import io.github.fastformer.common.persistence.NbtEnvelopeFile;
import io.github.fastformer.core.persist.Envelope;
import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/**
 * Durable envelope for one write-ahead segment. The world writer owns the
 * segment contents; this class only enforces ordering and integrity metadata.
 */
final class RecoveryJournalSegment {
   private static final int MAGIC = 0x46465253; // FFRS
   private static final int MAX_SEGMENTS = 1_000_000;
   static final long MAX_BYTES = 64L * 1024L * 1024L;

   private RecoveryJournalSegment() {
   }

   static Envelope.Format format() { return RecoveryJournalCodec.fileFormat(MAGIC); }

   static void write(Path file, UUID operationId, int sequence, CompoundTag payload) throws IOException {
      if (file == null || operationId == null || sequence < 0 || sequence >= MAX_SEGMENTS || payload == null) {
         throw new IllegalArgumentException("Invalid recovery segment");
      }
      CompoundTag root = new CompoundTag();
      root.putString("OperationId", operationId.toString());
      root.putInt("Sequence", sequence);
      root.put("Payload", payload);
      NbtEnvelopeFile.write(file, format(), root, MAX_BYTES);
   }

   static CompoundTag read(Path file, UUID operationId, int expectedSequence) throws IOException {
      return readVerified(file, operationId, expectedSequence).payload();
   }

   static VerifiedSegment readVerified(Path file, UUID operationId, int expectedSequence) throws IOException {
      if (file == null || operationId == null || expectedSequence < 0 || expectedSequence >= MAX_SEGMENTS) {
         throw new IllegalArgumentException("Invalid recovery segment");
      }
      var verified = NbtEnvelopeFile.readVerified(file, format(), MAX_BYTES);
      CompoundTag root = verified.root();
      if (!operationId.toString().equals(root.getString("OperationId"))
         || !root.contains("Sequence", Tag.TAG_INT)
         || root.getInt("Sequence") != expectedSequence
         || !root.contains("Payload", Tag.TAG_COMPOUND)) {
         throw new IOException("Recovery segment integrity check failed: " + file);
      }
      return new VerifiedSegment(root.getCompound("Payload"), verified.checksum());
   }

   record VerifiedSegment(CompoundTag payload, long checksum) { }

}
