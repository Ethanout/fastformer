package io.github.fastformer.common.persistence;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.fastformer.core.persist.Envelope;
import io.github.fastformer.core.persist.EnvelopeFile;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NbtEnvelopeFileTest {
   private static final Envelope.Format FORMAT = new Envelope.Format(0x46465431, 1, 42);
   @TempDir Path directory;

   @Test
   void verifiedCompressedNbtRoundTripsWithTheSharedChecksum() throws IOException {
      Path file = directory.resolve("payload.dat");
      var root = new CompoundTag();
      root.putString("Text", "complete NBT payload");
      NbtEnvelopeFile.write(file, FORMAT, root, 4096);
      var verified = NbtEnvelopeFile.readVerified(file, FORMAT, 4096);
      assertEquals(root, verified.root());
      assertEquals(Envelope.checksum(FORMAT, EnvelopeFile.read(file, FORMAT, 4096)), verified.checksum());
   }

   @Test
   void compressedBytesCannotBypassTheDecodedMemoryBudget() throws IOException {
      Path file = directory.resolve("large.dat");
      var root = new CompoundTag();
      root.putString("Text", "a".repeat(10000));
      NbtEnvelopeFile.write(file, FORMAT, root, 100000);
      assertThrows(IOException.class, () -> NbtEnvelopeFile.read(file, FORMAT, 1024));
   }

   @Test
   void failedEncodingPreservesThePreviousPublishedFile() throws IOException {
      Path file = directory.resolve("payload.dat");
      NbtEnvelopeFile.write(file, FORMAT, new CompoundTag(), 1024);
      byte[] valid = Files.readAllBytes(file);
      var oversized = new CompoundTag();
      oversized.putString("Text", "a".repeat(10000));
      assertThrows(IOException.class, () -> NbtEnvelopeFile.write(file, FORMAT, oversized, 1024));
      assertArrayEquals(valid, Files.readAllBytes(file));
   }

   @Test
   void verifiedWrongVersionIsClassifiedWithoutTreatingCorruptionAsMigration() throws IOException {
      Path file = directory.resolve("payload.dat");
      NbtEnvelopeFile.write(file, FORMAT, new CompoundTag(), 1024);
      assertThrows(IncompatibleWorldDataException.class,
         () -> NbtEnvelopeFile.read(file, new Envelope.Format(FORMAT.magic(), 1, 43), 1024));
      byte[] corrupted = Files.readAllBytes(file);
      corrupted[11] ^= 1;
      Files.write(file, corrupted);
      IOException failure = assertThrows(IOException.class, () -> NbtEnvelopeFile.read(file, FORMAT, 1024));
      assertEquals(IOException.class, failure.getClass());
   }

   @Test
   void validEnvelopeCannotHideTrailingNbtOrMalformedCompression() throws IOException {
      Path file = directory.resolve("payload.dat");
      var bytes = new ByteArrayOutputStream();
      try (var output = new DataOutputStream(new GZIPOutputStream(bytes))) {
         NbtIo.write(new CompoundTag(), output);
         output.write(1);
      }
      EnvelopeFile.write(file, FORMAT, bytes.toByteArray());
      assertThrows(IOException.class, () -> NbtEnvelopeFile.read(file, FORMAT, 4096));
      EnvelopeFile.write(file, FORMAT, new byte[]{1, 2, 3});
      assertThrows(IOException.class, () -> NbtEnvelopeFile.read(file, FORMAT, 4096));
   }
}
