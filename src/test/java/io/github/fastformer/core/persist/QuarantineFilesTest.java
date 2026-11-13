package io.github.fastformer.core.persist;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QuarantineFilesTest {
   @TempDir Path directory;

   @Test void preservesPrimarySidecarAndReasonOutsideActiveNamespace() throws IOException {
      Path primary = directory.resolve("operation.dat");
      Path sidecar = directory.resolve("operation.delta");
      Files.writeString(primary, "base");
      Files.writeString(sidecar, "correction");
      Path retained = QuarantineFiles.move(primary, List.of(sidecar), "future version");
      assertFalse(Files.exists(primary));
      assertFalse(Files.exists(sidecar));
      assertEquals("base", Files.readString(retained.resolve("operation.dat")));
      assertEquals("correction", Files.readString(retained.resolve("operation.delta")));
      assertEquals("future version", Files.readString(retained.resolve("reason.txt")));
   }

   @Test void movesSegmentedOperationAsOneDirectory() throws IOException {
      Path primary = Files.createDirectory(directory.resolve("operation"));
      Files.writeString(primary.resolve("segment-000000.dat"), "segment");
      Path retained = QuarantineFiles.move(primary, List.of(), "future version");
      assertFalse(Files.exists(primary));
      assertEquals("segment", Files.readString(retained.resolve("segment-000000.dat")));
   }

   @Test void copyingFailureLeavesActiveFamilyUntouched() throws IOException {
      Path primary = directory.resolve("operation.dat");
      Files.writeString(primary, "base");
      Path unreadableSidecar = Files.createDirectory(directory.resolve("operation.delta"));
      assertThrows(IOException.class, () -> QuarantineFiles.move(primary, List.of(unreadableSidecar), "future version"));
      assertEquals("base", Files.readString(primary));
      assertTrue(Files.isDirectory(unreadableSidecar));
   }
}
