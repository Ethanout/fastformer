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
