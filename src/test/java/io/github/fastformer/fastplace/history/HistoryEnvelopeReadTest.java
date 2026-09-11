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
   void enforcesCallerBudgetAndVerifiesChecksum() throws Exception {
      Path file = root.resolve("history.dat");
      HistoryEnvelopeFile.write(file, 1, new byte[]{1, 2, 3});
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
