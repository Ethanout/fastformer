package io.github.fastformer.fastplace.world;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

/** Final marker proving that every prepared segment belongs to one operation. */
final class RecoveryJournalSeal {
   private static final int VERSION = 1;
   private static final int MAX_SEGMENTS = 1_000_000;

   private RecoveryJournalSeal() {
   }

   static void write(Path file, UUID operationId, int segmentCount, long digest) throws IOException {
      if (file == null || operationId == null || segmentCount <= 0) {
         throw new IllegalArgumentException("Invalid recovery seal");
      }
      CompoundTag root = new CompoundTag();
      root.putInt("Version", VERSION);
      root.putString("OperationId", operationId.toString());
      root.putInt("SegmentCount", segmentCount);
      root.putLong("Digest", digest);
      PersistentRecoveryJournal.atomicWriteCompressed(file, root);
   }

   static Seal read(Path file, UUID operationId) throws IOException {
      if (file == null || operationId == null) {
         throw new IOException("Invalid recovery seal identity");
      }
      CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.create(1024L * 1024L));
      if (root.getInt("Version") != VERSION || !operationId.toString().equals(root.getString("OperationId"))) {
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
