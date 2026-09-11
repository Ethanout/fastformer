package io.github.fastformer.fastplace.history;

import io.github.fastformer.fastplace.world.WorldChangeBatch;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

/** Converts one compact batch to bytes. Callers must reserve memory before encoding or decoding. */
public final class WorldHistoryBatchCodec {
   private WorldHistoryBatchCodec() {
   }

   public static byte[] encode(WorldChangeBatch batch, int maxEncodedBytes) throws IOException {
      if (maxEncodedBytes < 1) throw new IllegalArgumentException("Invalid encoded history budget");
      ByteArrayOutputStream bytes = new ByteArrayOutputStream(Math.min(maxEncodedBytes, 8192));
      try (DataOutputStream output = new DataOutputStream(new LimitedOutput(bytes, maxEncodedBytes))) {
         NbtIo.write(batch.encode(), output);
      }
      return bytes.toByteArray();
   }

   public static WorldChangeBatch decode(
      HolderLookup.Provider registries, byte[] payload, long maxDecodedBytes
   ) throws IOException {
      if (maxDecodedBytes < 1) throw new IllegalArgumentException("Invalid decoded history budget");
      try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
         CompoundTag tag;
         try {
            tag = NbtIo.read(input, NbtAccounter.create(maxDecodedBytes));
         } catch (net.minecraft.nbt.NbtAccounterException failure) {
            throw new IOException("History data exceeds its decode memory budget", failure);
         } catch (net.minecraft.ReportedException failure) {
            throw new IOException("History NBT data is malformed", failure);
         }
         if (input.read() != -1) throw new IOException("Trailing bytes in history batch");
         return WorldChangeBatch.decode(registries, tag);
      }
   }

   private static final class LimitedOutput extends OutputStream {
      private final OutputStream output;
      private int remaining;

      private LimitedOutput(OutputStream output, int limit) {
         this.output = output;
         this.remaining = limit;
      }

      @Override
      public void write(int value) throws IOException {
         requireSpace(1);
         output.write(value);
         remaining--;
      }

      @Override
      public void write(byte[] bytes, int offset, int length) throws IOException {
         java.util.Objects.checkFromIndexSize(offset, length, bytes.length);
         requireSpace(length);
         output.write(bytes, offset, length);
         remaining -= length;
      }

      private void requireSpace(int length) throws IOException {
         if (length > remaining) throw new IOException("History data exceeds its encoded byte budget");
      }
   }
}
