package io.github.fastformer.core.persist;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;

/** Enforces the serialized-byte budget before forwarding writes to a payload or compression stream. */
public final class PayloadOutputStream extends FilterOutputStream {
   private final long limit;
   private long written;

   public PayloadOutputStream(OutputStream output, long limit) {
      super(Objects.requireNonNull(output, "output"));
      if (limit < 0) throw new IllegalArgumentException("Invalid serialized payload budget");
      this.limit = limit;
   }

   @Override public void write(int value) throws IOException {
      requireCapacity(1);
      out.write(value);
   }

   @Override public void write(byte[] data, int offset, int length) throws IOException {
      Objects.checkFromIndexSize(offset, length, data.length);
      requireCapacity(length);
      out.write(data, offset, length);
   }

   private void requireCapacity(int length) throws IOException {
      if (length > limit - written) throw new IOException("Serialized payload exceeds its byte budget");
      written += length;
   }
}
