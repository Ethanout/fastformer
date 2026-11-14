package io.github.fastformer.client.operation.clipboard;

import io.github.fastformer.core.persist.AtomicFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

/** Durable compressed storage for the client operation clipboard. */
public final class OperationClipboardStore {
   private static final long MAX_DECODE_BYTES = 256L * 1024L * 1024L;
   private static final java.util.Map<Path, Object> WRITES = new java.util.HashMap<>();

   public static synchronized Object reserve(Path file) {
      Object ticket = new Object();
      WRITES.put(file.toAbsolutePath().normalize(), ticket);
      return ticket;
   }

   public static synchronized boolean current(Path file, Object ticket) {
      return WRITES.get(file.toAbsolutePath().normalize()) == ticket;
   }

   public static synchronized void delete(Path file) throws IOException {
      WRITES.remove(file.toAbsolutePath().normalize());
      Files.deleteIfExists(file);
   }

   private OperationClipboardStore() {
   }

   public static void save(Path file, CompoundTag root) throws IOException {
      save(file, root, reserve(file));
   }

   public static void save(Path file, CompoundTag root, Object ticket) throws IOException {
      if (!current(file, ticket)) throw new IOException("Stale file write");
      try (var prepared = AtomicFiles.prepare(file, output -> NbtIo.writeCompressed(root, output))) {
         synchronized (OperationClipboardStore.class) {
            if (!current(file, ticket)) throw new IOException("Stale file write");
            prepared.commit();
         }
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
