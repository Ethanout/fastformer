package io.github.fastformer.client.operation.clipboard;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

/** Durable compressed storage for the client operation clipboard. */
public final class OperationClipboardStore {
   private static final long MAX_DECODE_BYTES = 256L * 1024L * 1024L;

   private OperationClipboardStore() {
   }

   public static void save(Path file, CompoundTag root) throws IOException {
      Path parent = file.toAbsolutePath().getParent();
      if (parent != null) {
         Files.createDirectories(parent);
      }
      Path temporary = file.resolveSibling(file.getFileName() + ".tmp-" + UUID.randomUUID());
      try {
         try (OutputStream output = Files.newOutputStream(temporary)) {
            NbtIo.writeCompressed(root, output);
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

   public static Optional<CompoundTag> load(Path file) {
      try {
         return loadStrict(file);
      } catch (IOException | RuntimeException exception) {
         return Optional.empty();
      }
   }

   /** Reads compressed NBT while preserving the reason that a durable file is unavailable. */
   public static Optional<CompoundTag> loadStrict(Path file) throws IOException {
      if (file == null || Files.notExists(file)) {
         return Optional.empty();
      }
      return Optional.ofNullable(NbtIo.readCompressed(file, NbtAccounter.create(MAX_DECODE_BYTES)));
   }
}
