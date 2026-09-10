package io.github.fastformer.fastplace.world;

import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.zip.CRC32;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

/**
 * Durable envelope for one write-ahead segment. The world writer owns the
 * segment contents; this class only enforces ordering and integrity metadata.
 */
final class RecoveryJournalSegment {
   private static final int VERSION = 1;
   private static final int MAX_SEGMENTS = 1_000_000;
   private static final long MAX_BYTES = 64L * 1024L * 1024L;

   private RecoveryJournalSegment() {
   }

   static void write(Path file, UUID operationId, int sequence, CompoundTag payload) throws IOException {
      if (file == null || operationId == null || sequence < 0 || sequence >= MAX_SEGMENTS || payload == null) {
         throw new IllegalArgumentException("Invalid recovery segment");
      }
      CompoundTag root = new CompoundTag();
      root.putInt("Version", VERSION);
      root.putString("OperationId", operationId.toString());
      root.putInt("Sequence", sequence);
      root.put("Payload", payload.copy());
      root.putLong("PayloadCrc", checksum(payload));
      atomicWrite(file, root);
   }

   static CompoundTag read(Path file, UUID operationId, int expectedSequence) throws IOException {
      if (file == null || operationId == null || expectedSequence < 0 || expectedSequence >= MAX_SEGMENTS) {
         throw new IllegalArgumentException("Invalid recovery segment");
      }
      CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.create(MAX_BYTES));
      if (root.getInt("Version") != VERSION
         || !operationId.toString().equals(root.getString("OperationId"))
         || root.getInt("Sequence") != expectedSequence
         || !root.contains("Payload", 10)
         || root.getLong("PayloadCrc") != checksum(root.getCompound("Payload"))) {
         throw new IOException("Recovery segment integrity check failed: " + file);
      }
      return root.getCompound("Payload").copy();
   }

   static long checksum(CompoundTag payload) {
      return checksumValue(payload);
   }

   private static long checksumValue(CompoundTag payload) {
      CRC32 crc = new CRC32();
      byte[] bytes = payload.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
      crc.update(bytes, 0, bytes.length);
      return crc.getValue();
   }

   private static void atomicWrite(Path file, CompoundTag root) throws IOException {
      Files.createDirectories(file.getParent());
      Path temporary = file.resolveSibling(file.getFileName() + ".tmp-" + UUID.randomUUID());
      try {
         try (FileOutputStream output = new FileOutputStream(temporary.toFile())) {
            NbtIo.writeCompressed(root, new FilterOutputStream(output) {
               @Override
               public void close() throws IOException {
                  flush();
               }
            });
            output.getChannel().force(true);
         }
         try {
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
         } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
         }
      } finally {
         Files.deleteIfExists(temporary);
      }
   }
}
