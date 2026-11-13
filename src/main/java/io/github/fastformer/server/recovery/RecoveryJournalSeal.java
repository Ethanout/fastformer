package io.github.fastformer.server.recovery;

import io.github.fastformer.common.persistence.NbtEnvelopeFile;
import io.github.fastformer.core.persist.Envelope;
import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/** Final marker proving that every prepared segment belongs to one operation. */
final class RecoveryJournalSeal {
   private static final int MAGIC = 0x4646524C; // FFRL
   private static final int MAX_SEGMENTS = 1_000_000;

   private RecoveryJournalSeal() {
   }

   static Envelope.Format format() { return RecoveryJournalCodec.fileFormat(MAGIC); }

   static void write(Path file, UUID operationId, int segmentCount, long digest) throws IOException {
      if (file == null || operationId == null || segmentCount <= 0 || segmentCount > MAX_SEGMENTS) {
         throw new IllegalArgumentException("Invalid recovery seal");
      }
      CompoundTag root = new CompoundTag();
      root.putString("OperationId", operationId.toString());
      root.putInt("SegmentCount", segmentCount);
      root.putLong("Digest", digest);
      NbtEnvelopeFile.write(file, format(), root, 1024L * 1024L);
   }

   static Seal read(Path file, UUID operationId) throws IOException {
      if (file == null || operationId == null) {
         throw new IOException("Invalid recovery seal identity");
      }
      CompoundTag root = NbtEnvelopeFile.read(file, format(), 1024L * 1024L);
      if (!operationId.toString().equals(root.getString("OperationId")) || !root.contains("Digest", Tag.TAG_LONG)) {
         throw new IOException("Recovery seal identity check failed: " + file);
      }
      int count = root.getInt("SegmentCount");
      if (count <= 0 || count > MAX_SEGMENTS) {
         throw new IOException("Recovery seal has invalid segment count");
      }
      return new Seal(count, root.getLong("Digest"));
   }

   record Seal(int segmentCount, long digest) {
   }
}
