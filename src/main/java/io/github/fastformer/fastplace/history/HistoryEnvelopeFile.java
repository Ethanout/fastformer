package io.github.fastformer.fastplace.history;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** Publishes a complete envelope without replacing the previous file on write failure. */
public final class HistoryEnvelopeFile {
   private HistoryEnvelopeFile() {}

   /** Reads the opened file within the supplied limit, including if its size changes. */
   public static byte[] read(Path source, int version, long maxPayloadBytes) throws IOException {
      if (maxPayloadBytes < 0) throw new IllegalArgumentException("Invalid history payload budget");
      long headerBytes = Integer.BYTES * 3L + Long.BYTES;
      long maximumBytes = Math.min(maxPayloadBytes, VersionedHistoryEnvelope.MAX_PAYLOAD) + headerBytes;
      try (FileChannel channel = FileChannel.open(source, StandardOpenOption.READ)) {
         long size = channel.size();
         if (size < headerBytes || size > maximumBytes) {
            throw new IOException("History file size is outside its allowed range");
         }
         ByteBuffer buffer = ByteBuffer.allocate(Math.toIntExact(size));
         while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) throw new IOException("Truncated history file");
         }
         if (channel.read(ByteBuffer.allocate(1)) != -1) throw new IOException("History file grew during reading");
         return VersionedHistoryEnvelope.decode(buffer.array(), version).payload();
      }
   }

   public static void write(Path target, int version, byte[] payload) throws IOException {
      byte[] encoded = VersionedHistoryEnvelope.encode(version, payload);
      Path absolute = target.toAbsolutePath();
      Files.createDirectories(absolute.getParent());
      Path temporary = Files.createTempFile(absolute.getParent(), ".history-", ".tmp");
      try {
         try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(encoded);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
         }
         // Fail rather than silently falling back to a non-atomic replacement.
         Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } finally {
         Files.deleteIfExists(temporary);
      }
   }
}
