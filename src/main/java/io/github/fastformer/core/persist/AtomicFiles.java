package io.github.fastformer.core.persist;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/** Publishes complete files using a forced temporary file and an atomic rename. */
public final class AtomicFiles {
   private static final Logger LOGGER = Logger.getLogger(AtomicFiles.class.getName());
   private static final AtomicBoolean DIRECTORY_SYNC_WARNING = new AtomicBoolean();

   private AtomicFiles() {}

   /** A writer may close its stream; the file descriptor remains owned by this utility. */
   @FunctionalInterface
   public interface Writer {
      void write(OutputStream output) throws IOException;
   }

   /**
    * Writes in the target directory and never falls back to a non-atomic replacement.
    * On Windows, the JDK provider cannot open directory handles for fsync; the file is
    * still forced before the atomic rename, and that platform limitation is reported.
    */
   public static void write(Path target, Writer writer) throws IOException {
      try (PreparedWrite prepared = prepare(target, writer)) {
         prepared.commit();
      }
   }

   /**
    * Forces a complete temporary file before returning it to the caller. The caller may
    * validate a generation under its own lock, then commit without holding that lock
    * during serialization. Closing without a commit discards only the temporary file.
    */
   public static PreparedWrite prepare(Path target, Writer writer) throws IOException {
      Objects.requireNonNull(writer, "writer");
      Path absolute = Objects.requireNonNull(target, "target").toAbsolutePath().normalize();
      Files.createDirectories(absolute.getParent());
      Path temporary = Files.createTempFile(absolute.getParent(), ".fastformer-", ".tmp");
      try {
         try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
            OutputStream output = new FilterOutputStream(Channels.newOutputStream(channel)) {
               @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                  out.write(bytes, offset, length);
               }
               @Override public void close() throws IOException { flush(); }
            };
            writer.write(output);
            output.flush();
            channel.force(true);
         }
         return new PreparedWrite(temporary, absolute);
      } catch (IOException | RuntimeException | Error failure) {
         try { Files.deleteIfExists(temporary); }
         catch (IOException cleanup) { failure.addSuppressed(cleanup); }
         throw failure;
      }
   }

   /** A single-owner replacement; commit and close must not run concurrently. */
   public static final class PreparedWrite implements AutoCloseable {
      private final Path temporary;
      private final Path target;
      private boolean closed;

      private PreparedWrite(Path temporary, Path target) {
         this.temporary = temporary;
         this.target = target;
      }

      /** Publishes once using the same atomic rename and directory sync as write(). */
      public void commit() throws IOException {
         if (closed) throw new IllegalStateException("Prepared file is already closed");
         move(temporary, target);
         closed = true;
      }

      @Override public void close() throws IOException {
         if (closed) return;
         Files.deleteIfExists(temporary);
         closed = true;
      }
   }

   /** Atomically renames an existing durable file and forces the changed directory entries. */
   public static void move(Path source, Path target) throws IOException {
      Path from = Objects.requireNonNull(source, "source").toAbsolutePath().normalize();
      Path to = Objects.requireNonNull(target, "target").toAbsolutePath().normalize();
      Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      forceDirectory(to.getParent());
      if (!from.getParent().equals(to.getParent())) forceDirectory(from.getParent());
   }

   private static void forceDirectory(Path directory) throws IOException {
      try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
         channel.force(true);
      } catch (AccessDeniedException | UnsupportedOperationException failure) {
         if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) {
            if (failure instanceof IOException io) throw io;
            throw new IOException("Directory fsync is unsupported: " + directory, failure);
         }
         if (DIRECTORY_SYNC_WARNING.compareAndSet(false, true)) {
            LOGGER.warning("The Windows JDK filesystem provider does not support directory fsync; "
               + "FastFormer uses a forced file followed by an atomic rename.");
         }
      }
   }
}
