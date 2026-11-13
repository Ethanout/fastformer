package io.github.fastformer.core.persist;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Atomic publication and bounded reads for every envelope-backed file kind. */
public final class EnvelopeFile {
   private EnvelopeFile() {}

   public static void verify(Path source, Envelope.Format format, long maxPayloadBytes) throws IOException {
      try (var input = new DataInputStream(Files.newInputStream(source))) {
         Envelope.verify(input, format, maxPayloadBytes);
      }
   }

   public static byte[] read(Path source, Envelope.Format format, long maxPayloadBytes) throws IOException {
      if (maxPayloadBytes < 0) throw new IllegalArgumentException("Invalid envelope payload budget");
      try (var channel = FileChannel.open(source, StandardOpenOption.READ)) {
         long size = channel.size();
         long maximum = Math.min(maxPayloadBytes, Envelope.MAX_PAYLOAD_BYTES);
         if (size < Envelope.HEADER_BYTES || size > maximum + Envelope.HEADER_BYTES) {
            throw new IOException("Envelope file size is outside its budget");
         }
         return Envelope.read(new DataInputStream(Channels.newInputStream(channel)), format,
            Math.min(maximum, size - Envelope.HEADER_BYTES));
      }
   }

   public static void write(Path target, Envelope.Format format, byte[] payload) throws IOException {
      byte[] header = Envelope.header(format, payload);
      AtomicFiles.write(target, output -> {
         output.write(header);
         output.write(payload);
      });
   }
}
