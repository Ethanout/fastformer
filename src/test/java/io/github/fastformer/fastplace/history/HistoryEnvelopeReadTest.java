package io.github.fastformer.fastplace.history;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HistoryEnvelopeReadTest {
   @TempDir Path root;

   @Test
   void streamedVerificationChecksEveryChunkAndFormatBoundary() throws Exception {
      Path file = root.resolve("stream.dat");
      byte[] payload = new byte[20000];
      new java.util.Random(19).nextBytes(payload);
      HistoryEnvelopeFile.write(file, 1, payload);
      HistoryEnvelopeFile.verify(file, 1, payload.length);
      assertThrows(IOException.class, () -> HistoryEnvelopeFile.verify(file, 2, payload.length));
      assertThrows(IOException.class, () -> HistoryEnvelopeFile.verify(file, 1, payload.length - 1));
      byte[] valid = Files.readAllBytes(file);
      for (int offset : new int[]{20, 8212, valid.length - 1}) {
         byte[] corrupt = valid.clone();
         corrupt[offset] ^= 1;
         Files.write(file, corrupt);
         assertThrows(IOException.class, () -> HistoryEnvelopeFile.verify(file, 1, payload.length));
      }
      Files.write(file, java.util.Arrays.copyOf(valid, valid.length - 1));
      assertThrows(IOException.class, () -> HistoryEnvelopeFile.verify(file, 1, payload.length));
      Files.write(file, java.util.Arrays.copyOf(valid, valid.length + 1));
      assertThrows(IOException.class, () -> HistoryEnvelopeFile.verify(file, 1, payload.length));
      HistoryEnvelopeFile.write(file, 1, new byte[0]);
      HistoryEnvelopeFile.verify(file, 1, 0);
   }

   @Test
   void enforcesCallerBudgetAndVerifiesChecksum() throws Exception {
      Path file = root.resolve("history.dat");
      HistoryEnvelopeFile.write(file, 1, new byte[]{1, 2, 3});
      assertArrayEquals(VersionedHistoryEnvelope.encode(1, new byte[]{1, 2, 3}), Files.readAllBytes(file));
      assertArrayEquals(new byte[]{1, 2, 3}, HistoryEnvelopeFile.read(file, 1, 3));
      assertThrows(IOException.class, () -> HistoryEnvelopeFile.read(file, 1, 2));
      byte[] corrupt = Files.readAllBytes(file);
      corrupt[corrupt.length - 1] ^= 1;
      Files.write(file, corrupt);
      assertThrows(IOException.class, () -> HistoryEnvelopeFile.read(file, 1, 3));
   }

   @Test
   void rejectsTruncationAndTrailingBytes() throws Exception {
      Path file = root.resolve("history.dat");
      HistoryEnvelopeFile.write(file, 1, new byte[]{1, 2, 3});
      byte[] valid = Files.readAllBytes(file);
      Files.write(file, java.util.Arrays.copyOf(valid, valid.length - 1));
      assertThrows(IOException.class, () -> HistoryEnvelopeFile.read(file, 1, 100));
      Files.write(file, java.util.Arrays.copyOf(valid, valid.length + 1));
      assertThrows(IOException.class, () -> HistoryEnvelopeFile.read(file, 1, 100));
   }
}
