package io.github.fastformer.core.persist;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.zip.CRC32C;

/** Current-format binary envelope. This library knows no game registries or migration rules. */
public final class Envelope {
   public static final int HEADER_BYTES = Integer.BYTES * 4 + Long.BYTES;
   public static final int MAX_PAYLOAD_BYTES = 512 * 1024 * 1024;

   private Envelope() {}

   /** The caller supplies a file-kind magic, its current schema and the current game DataVersion. */
   public record Format(int magic, int version, int dataVersion) {
      public Format {
         if (version < 1 || dataVersion < 0) throw new IllegalArgumentException("Invalid envelope format");
      }
   }

   /** Known identity with an unsupported schema or game version; corruption remains an ordinary IOException. */
   public static final class FormatMismatchException extends IOException {
      public FormatMismatchException(String message) { super(message); }
   }

   public static byte[] header(Format format, byte[] payload) {
      Objects.requireNonNull(format, "format");
      Objects.requireNonNull(payload, "payload");
      if (payload.length > MAX_PAYLOAD_BYTES) throw new IllegalArgumentException("Envelope payload exceeds its hard limit");
      return ByteBuffer.allocate(HEADER_BYTES).putInt(format.magic()).putInt(format.version())
         .putInt(format.dataVersion()).putInt(payload.length).putLong(checksum(format, payload)).array();
   }

   public static long checksum(Format format, byte[] payload) {
      CRC32C crc = metadataChecksum(format.magic(), format.version(), format.dataVersion(), payload.length);
      crc.update(payload, 0, payload.length);
      return crc.getValue();
   }

   /** Verifies in bounded chunks without allocating the stored payload. Does not close the caller's stream. */
   public static void verify(DataInputStream input, Format format, long maxPayloadBytes) throws IOException {
      readPayload(input, format, maxPayloadBytes, false);
   }

   /** Returns only verified bytes; declared length, EOF and caller budget are all enforced. */
   public static byte[] read(DataInputStream input, Format format, long maxPayloadBytes) throws IOException {
      return readPayload(input, format, maxPayloadBytes, true);
   }

   public static byte[] encode(Format format, byte[] payload) {
      byte[] header = header(format, payload);
      try {
         var bytes = new ByteArrayOutputStream(payload.length + HEADER_BYTES);
         bytes.write(header);
         bytes.write(payload);
         return bytes.toByteArray();
      } catch (IOException impossible) {
         throw new AssertionError(impossible);
      }
   }

   public static byte[] decode(byte[] encoded, Format format, long maxPayloadBytes) throws IOException {
      try (var input = new DataInputStream(new ByteArrayInputStream(encoded))) {
         return read(input, format, Math.min(maxPayloadBytes, Math.max(0L, encoded.length - (long)HEADER_BYTES)));
      }
   }

   private static byte[] readPayload(DataInputStream input, Format format, long budget, boolean retain) throws IOException {
      Objects.requireNonNull(input, "input");
      Objects.requireNonNull(format, "format");
      if (budget < 0) throw new IllegalArgumentException("Invalid envelope payload budget");
      try {
         int magic = input.readInt();
         if (magic != format.magic()) throw new IOException("Unknown envelope magic");
         int version = input.readInt();
         int dataVersion = input.readInt();
         int length = input.readInt();
         long expectedChecksum = input.readLong();
         if (length < 0 || length > Math.min(MAX_PAYLOAD_BYTES, budget)) throw new IOException("Envelope payload exceeds its budget");
         byte[] payload = retain ? new byte[length] : null;
         byte[] chunk = retain ? payload : new byte[Math.min(length, 8192)];
         CRC32C crc = metadataChecksum(magic, version, dataVersion, length);
         for (int offset = 0; offset < length;) {
            int count = Math.min(length - offset, retain ? length : chunk.length);
            int target = retain ? offset : 0;
            input.readFully(chunk, target, count);
            crc.update(chunk, target, count);
            offset += count;
         }
         if (input.read() != -1) throw new IOException("Trailing envelope data");
         if (crc.getValue() != expectedChecksum) throw new IOException("Envelope CRC32C mismatch");
         if (version != format.version()) throw new FormatMismatchException("Unsupported envelope version: " + version);
         if (dataVersion != format.dataVersion()) throw new FormatMismatchException("Unsupported envelope DataVersion: " + dataVersion);
         return payload;
      } catch (EOFException truncated) {
         throw new IOException("Truncated envelope", truncated);
      }
   }

   private static CRC32C metadataChecksum(int magic, int version, int dataVersion, int length) {
      byte[] metadata = ByteBuffer.allocate(4 * Integer.BYTES)
         .putInt(magic).putInt(version).putInt(dataVersion).putInt(length).array();
      var crc = new CRC32C();
      crc.update(metadata, 0, metadata.length);
      return crc;
   }
}
