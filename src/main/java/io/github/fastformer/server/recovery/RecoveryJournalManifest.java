package io.github.fastformer.server.recovery;

import io.github.fastformer.common.persistence.NbtEnvelopeFile;
import io.github.fastformer.core.persist.Envelope;
import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

/** Immutable identity and ordering contract shared by recovery segments. */
final class RecoveryJournalManifest {
   private static final int MAGIC = 0x4646524D; // FFRM
   private static final int MAX_SEGMENTS = 1_000_000;

   private RecoveryJournalManifest() {
   }

   static Envelope.Format format() { return RecoveryJournalCodec.fileFormat(MAGIC); }

   static void write(Path file, UUID operationId, UUID owner, String dimension, int segmentLimit)
      throws IOException {
      if (file == null || operationId == null || owner == null || dimension == null || dimension.isBlank()
         || ResourceLocation.tryParse(dimension) == null
         || segmentLimit <= 0 || segmentLimit > MAX_SEGMENTS) {
         throw new IllegalArgumentException("Invalid recovery manifest");
      }
      CompoundTag root = new CompoundTag();
      root.putString("OperationId", operationId.toString());
      root.putString("Owner", owner.toString());
      root.putString("Dimension", dimension);
      root.putInt("SegmentLimit", segmentLimit);
      NbtEnvelopeFile.write(file, format(), root, 1024L * 1024L);
   }

   static Manifest read(Path file) throws IOException {
      if (file == null) {
         throw new IOException("Invalid recovery manifest path");
      }
      CompoundTag root = NbtEnvelopeFile.read(file, format(), 1024L * 1024L);
      try {
         UUID operation = UUID.fromString(root.getString("OperationId"));
         UUID owner = UUID.fromString(root.getString("Owner"));
         String dimension = root.getString("Dimension");
         int segmentLimit = root.getInt("SegmentLimit");
         if (dimension.isBlank() || ResourceLocation.tryParse(dimension) == null
            || segmentLimit <= 0 || segmentLimit > MAX_SEGMENTS) {
            throw new IllegalArgumentException();
         }
         return new Manifest(operation, owner, dimension, segmentLimit);
      } catch (IllegalArgumentException exception) {
         throw new IOException("Invalid recovery manifest identity", exception);
      }
   }

   record Manifest(UUID operationId, UUID owner, String dimension, int segmentLimit) {
   }

}
