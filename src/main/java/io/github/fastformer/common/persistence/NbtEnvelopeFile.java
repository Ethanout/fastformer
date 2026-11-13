package io.github.fastformer.common.persistence;

import io.github.fastformer.core.persist.Envelope;
import io.github.fastformer.core.persist.EnvelopeFile;
import io.github.fastformer.core.persist.PayloadOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

/** Compressed NBT inside the shared byte envelope; both stored and decoded sizes have budgets. */
public final class NbtEnvelopeFile {
   private NbtEnvelopeFile() {}

   public static void write(Path file, Envelope.Format format, CompoundTag root, long maxBytes) throws IOException {
      if (maxBytes < 0) throw new IllegalArgumentException("Invalid NBT envelope budget");
      long limit = Math.min(maxBytes, Envelope.MAX_PAYLOAD_BYTES);
      var bytes = new ByteArrayOutputStream();
      try (var compressed = new GZIPOutputStream(new PayloadOutputStream(bytes, limit));
           var output = new DataOutputStream(new PayloadOutputStream(compressed, limit))) {
         NbtIo.write(root, output);
      }
      EnvelopeFile.write(file, format, bytes.toByteArray());
   }

   public static CompoundTag read(Path file, Envelope.Format format, long maxBytes) throws IOException {
      return readVerified(file, format, maxBytes).root();
   }

   public static Verified readVerified(Path file, Envelope.Format format, long maxBytes) throws IOException {
      final byte[] bytes;
      try {
         bytes = EnvelopeFile.read(file, format, maxBytes);
      } catch (Envelope.FormatMismatchException mismatch) {
         throw new IncompatibleWorldDataException(mismatch.getMessage(), mismatch);
      }
      try (var input = new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(bytes)))) {
         CompoundTag root = NbtIo.read(input, NbtAccounter.create(maxBytes));
         if (root == null || input.read() != -1) throw new IOException("Trailing or missing envelope NBT");
         return new Verified(root, Envelope.checksum(format, bytes));
      } catch (net.minecraft.nbt.NbtAccounterException | net.minecraft.ReportedException failure) {
         throw new IOException("Malformed or over-budget envelope NBT", failure);
      }
   }

   public record Verified(CompoundTag root, long checksum) {}
}
