package io.github.fastformer.fastplace.history;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HistoryEnvelopeFileTest {
   @TempDir Path directory;

   @Test
   void replacesCompleteEnvelopeAndRemovesTemporaryFiles() throws Exception {
      Path target = directory.resolve("history.dat");
      HistoryEnvelopeFile.write(target, 1, new byte[]{1});
      HistoryEnvelopeFile.write(target, 1, new byte[]{2, 3});
      assertArrayEquals(new byte[]{2, 3}, VersionedHistoryEnvelope.decode(Files.readAllBytes(target), 1).payload());
      try (var entries = Files.list(directory)) {
         assertEquals(1, entries.count());
      }
   }

   @Test
   void invalidWritePreservesPreviousHistory() throws Exception {
      Path target = directory.resolve("history.dat");
      HistoryEnvelopeFile.write(target, 1, new byte[]{42});
      byte[] original = Files.readAllBytes(target);
      assertThrows(IllegalArgumentException.class, () -> HistoryEnvelopeFile.write(target, 0, new byte[]{9}));
      assertArrayEquals(original, Files.readAllBytes(target));
   }
}
