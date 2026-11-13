package io.github.fastformer.core.persist;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EnvelopeFileTest {
   private static final Envelope.Format FORMAT = new Envelope.Format(0x46465431, 1, 42);
   @TempDir Path directory;

   @Test
   void replacesTheCompleteEnvelopeAndRemovesTemporaryFiles() throws Exception {
      Path file = directory.resolve("payload.dat");
      EnvelopeFile.write(file, FORMAT, new byte[]{1});
      EnvelopeFile.write(file, FORMAT, new byte[]{2, 3});
      assertArrayEquals(new byte[]{2, 3}, EnvelopeFile.read(file, FORMAT, 2));
      assertArrayEquals(Envelope.encode(FORMAT, new byte[]{2, 3}), Files.readAllBytes(file));
      try (var files = Files.list(directory)) { assertEquals(1L, files.count()); }
   }

   @Test
   void invalidWritePreservesThePreviousFile() throws Exception {
      Path file = directory.resolve("payload.dat");
      EnvelopeFile.write(file, FORMAT, new byte[]{42});
      byte[] original = Files.readAllBytes(file);
      assertThrows(IllegalArgumentException.class,
         () -> EnvelopeFile.write(file, new Envelope.Format(1, 0, 42), new byte[]{9}));
      assertArrayEquals(original, Files.readAllBytes(file));
   }

   @Test
   void streamingVerificationChecksEveryChunkAndBudget() throws Exception {
      Path file = directory.resolve("stream.dat");
      byte[] payload = new byte[20000];
      new Random(19).nextBytes(payload);
      EnvelopeFile.write(file, FORMAT, payload);
      EnvelopeFile.verify(file, FORMAT, payload.length);
      assertThrows(IOException.class, () -> EnvelopeFile.verify(file, FORMAT, payload.length - 1));
      byte[] valid = Files.readAllBytes(file);
      for (int offset : new int[]{Envelope.HEADER_BYTES, Envelope.HEADER_BYTES + 8192, valid.length - 1}) {
         byte[] corrupt = valid.clone();
         corrupt[offset] ^= 1;
         Files.write(file, corrupt);
         assertThrows(IOException.class, () -> EnvelopeFile.verify(file, FORMAT, payload.length));
      }
      Files.write(file, Arrays.copyOf(valid, valid.length - 1));
      assertThrows(IOException.class, () -> EnvelopeFile.verify(file, FORMAT, payload.length));
      Files.write(file, Arrays.copyOf(valid, valid.length + 1));
      assertThrows(IOException.class, () -> EnvelopeFile.verify(file, FORMAT, payload.length));
      EnvelopeFile.write(file, FORMAT, new byte[0]);
      EnvelopeFile.verify(file, FORMAT, 0);
      assertArrayEquals(new byte[0], EnvelopeFile.read(file, FORMAT, 0));
   }

   @Test
   void boundedReadRejectsChecksumFailureTruncationAndTrailingBytes() throws Exception {
      Path file = directory.resolve("payload.dat");
      EnvelopeFile.write(file, FORMAT, new byte[]{1, 2, 3});
      assertThrows(IOException.class, () -> EnvelopeFile.read(file, FORMAT, 2));
      byte[] valid = Files.readAllBytes(file);
      byte[] corrupt = valid.clone();
      corrupt[corrupt.length - 1] ^= 1;
      Files.write(file, corrupt);
      assertThrows(IOException.class, () -> EnvelopeFile.read(file, FORMAT, 100));
      Files.write(file, Arrays.copyOf(valid, valid.length - 1));
      assertThrows(IOException.class, () -> EnvelopeFile.read(file, FORMAT, 100));
      Files.write(file, Arrays.copyOf(valid, valid.length + 1));
      assertThrows(IOException.class, () -> EnvelopeFile.read(file, FORMAT, 100));
   }
}
