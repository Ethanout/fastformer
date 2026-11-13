package io.github.fastformer.core.persist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class StoragePathsTest {
   @Test
   void rootsStayInsideTheSuppliedWorld() {
      Path world = Path.of("worlds", "trial");
      assertEquals(world.resolve("fastformer-history"), StoragePaths.history(world));
      assertEquals(world.resolve("fastformer-recovery"), StoragePaths.recovery(world));
   }

   @Test
   void segmentNamesUseAsciiDigitsUnderAnyDefaultLocale() {
      Locale original = Locale.getDefault();
      try {
         Locale.setDefault(Locale.forLanguageTag("ar"));
         Path directory = Path.of("operation");
         assertEquals(directory.resolve("segment-000123.dat"), StoragePaths.journalSegment(directory, 123));
      } finally {
         Locale.setDefault(original);
      }
   }

   @Test
   void invalidSequencesCannotProduceUnrecognizableSegments() {
      assertThrows(IllegalArgumentException.class, () -> StoragePaths.journalSegment(Path.of("op"), -1));
      assertThrows(IllegalArgumentException.class, () -> StoragePaths.journalSegment(Path.of("op"), 1_000_000));
   }
}
