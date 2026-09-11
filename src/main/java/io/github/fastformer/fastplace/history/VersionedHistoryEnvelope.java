package io.github.fastformer.fastplace.history;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.Objects;
import java.util.zip.CRC32;

/** Small, registry-independent envelope for persisted history payloads. */
public final class VersionedHistoryEnvelope {
   private static final int MAGIC = 0x46464831; // FFH1
   static final int MAX_PAYLOAD = 256 * 1024 * 1024;

   private VersionedHistoryEnvelope() {}

   static void verify(java.io.DataInputStream input, int expectedVersion, long maxPayloadBytes) throws IOException {
      if (expectedVersion < 1 || maxPayloadBytes < 0) throw new IllegalArgumentException("Invalid history read budget");
      try {
         if (input.readInt() != MAGIC) throw new IOException("Unknown history envelope");
         int version = input.readInt();
         int length = input.readInt();
         long checksum = input.readLong();
         if (version != expectedVersion) throw new IOException("Unsupported history version: " + version);
         if (length < 0 || length > Math.min(MAX_PAYLOAD, maxPayloadBytes)) {
            throw new IOException("Invalid history payload length");
         }
         CRC32 crc = new CRC32();
         byte[] buffer = new byte[Math.min(length, 8192)];
         int remaining = length;
         while (remaining > 0) {
            int count = Math.min(remaining, buffer.length);
            input.readFully(buffer, 0, count);
            crc.update(buffer, 0, count);
            remaining -= count;
         }
         if (input.read() != -1) throw new IOException("Trailing history envelope data");
         if (crc.getValue() != checksum) throw new IOException("History payload checksum mismatch");
      } catch (EOFException failure) {
         throw new IOException("Truncated history envelope", failure);
      }
   }

   static byte[] header(int version, byte[] payload) {
      if (version < 1 || payload == null || payload.length > MAX_PAYLOAD) {
         throw new IllegalArgumentException("Invalid history envelope");
      }
      CRC32 crc = new CRC32();
      crc.update(payload);
      return java.nio.ByteBuffer.allocate(20)
         .putInt(MAGIC).putInt(version).putInt(payload.length).putLong(crc.getValue()).array();
   }

   static byte[] read(java.io.DataInputStream input, int expectedVersion, long maxPayloadBytes) throws IOException {
      if (expectedVersion < 1 || maxPayloadBytes < 0) throw new IllegalArgumentException("Invalid history read budget");
      try {
         if (input.readInt() != MAGIC) throw new IOException("Unknown history envelope");
         int version = input.readInt();
         int length = input.readInt();
         long checksum = input.readLong();
         if (version != expectedVersion) throw new IOException("Unsupported history version: " + version);
         if (length < 0 || length > Math.min(MAX_PAYLOAD, maxPayloadBytes)) {
            throw new IOException("Invalid history payload length");
         }
         byte[] payload = new byte[length];
         input.readFully(payload);
         if (input.read() != -1) throw new IOException("Trailing history envelope data");
         CRC32 crc = new CRC32();
         crc.update(payload);
         if (crc.getValue() != checksum) throw new IOException("History payload checksum mismatch");
         return payload;
      } catch (EOFException failure) {
         throw new IOException("Truncated history envelope", failure);
      }
   }

   public static byte[] encode(int version, byte[] payload) {
      if (version < 1 || payload == null || payload.length > MAX_PAYLOAD) {
         throw new IllegalArgumentException("Invalid history envelope");
      }
      CRC32 crc = new CRC32();
      crc.update(payload);
      try {
         ByteArrayOutputStream bytes = new ByteArrayOutputStream(payload.length + 20);
         DataOutputStream out = new DataOutputStream(bytes);
         out.writeInt(MAGIC);
         out.writeInt(version);
         out.writeInt(payload.length);
         out.writeLong(crc.getValue());
         out.write(payload);
         out.flush();
         return bytes.toByteArray();
      } catch (IOException impossible) {
         throw new AssertionError(impossible);
      }
   }

   public static Decoded decode(byte[] encoded, int expectedVersion) throws IOException {
      Objects.requireNonNull(encoded, "encoded");
      if (expectedVersion < 1) throw new IllegalArgumentException("Invalid expected version");
      try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded))) {
         if (in.readInt() != MAGIC) throw new IOException("Unknown history envelope");
         int version = in.readInt();
         int length = in.readInt();
         long checksum = in.readLong();
         if (version != expectedVersion) throw new IOException("Unsupported history version: " + version);
         if (length < 0 || length > MAX_PAYLOAD || length != in.available()) {
            throw new IOException("Invalid history payload length");
         }
         byte[] payload = in.readNBytes(length);
         CRC32 crc = new CRC32();
         crc.update(payload);
         if (crc.getValue() != checksum) throw new IOException("History payload checksum mismatch");
         return new Decoded(version, payload);
      } catch (EOFException ex) {
         throw new IOException("Truncated history envelope", ex);
      }
   }

   public record Decoded(int version, byte[] payload) {
      public Decoded {
         payload = payload.clone();
      }
      public byte[] payload() { return payload.clone(); }
   }
}
