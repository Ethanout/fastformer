package io.github.fastformer.fastplace.world;

import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;

/** Immutable identity and ordering contract shared by recovery segments. */
final class RecoveryJournalManifest {
   private static final int VERSION = 1;
   private static final int MAX_SEGMENTS = 1_000_000;

   private RecoveryJournalManifest() {
   }

   static void write(Path file, UUID operationId, UUID owner, String dimension, int segmentLimit)
      throws IOException {
      if (file == null || operationId == null || owner == null || dimension == null || dimension.isBlank()
         || ResourceLocation.tryParse(dimension) == null
         || segmentLimit <= 0 || segmentLimit > MAX_SEGMENTS) {
         throw new IllegalArgumentException("Invalid recovery manifest");
      }
      CompoundTag root = new CompoundTag();
      root.putInt("Version", VERSION);
      root.putString("OperationId", operationId.toString());
      root.putString("Owner", owner.toString());
      root.putString("Dimension", dimension);
      root.putInt("SegmentLimit", segmentLimit);
      atomicWrite(file, root);
   }

   static Manifest read(Path file) throws IOException {
      if (file == null) {
         throw new IOException("Invalid recovery manifest path");
      }
      CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.create(1024L * 1024L));
      if (root.getInt("Version") != VERSION) {
         throw new IOException("Unsupported recovery manifest version");
      }
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
