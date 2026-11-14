package io.github.fastformer.core.persist;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AtomicFilesTest {
   @TempDir Path directory;

   @Test void publishesACompleteFileEvenWhenTheEncoderClosesItsStream() throws IOException {
      Path target = directory.resolve("nested/data");
      AtomicFiles.write(target, output -> {
         output.write("complete".getBytes(StandardCharsets.UTF_8));
         output.close();
      });
      assertEquals("complete", Files.readString(target));
      try (var entries = Files.list(target.getParent())) { assertEquals(1L, entries.count()); }
   }

   @Test void failedEncodingPreservesPreviousFileAndRemovesTemporaryFile() throws IOException {
      Path target = directory.resolve("data");
      Files.writeString(target, "previous");
      IOException failure = new IOException("encoder failed");
      assertSame(failure, assertThrows(IOException.class, () -> AtomicFiles.write(target, output -> {
         output.write(1);
         throw failure;
      })));
      assertEquals("previous", Files.readString(target));
      try (var entries = Files.list(directory)) { assertEquals(1L, entries.count()); }
   }

   @Test void failedPublicationPreservesTargetAndCleansTemporaryFile() throws IOException {
      Path target = Files.createDirectory(directory.resolve("data"));
      Files.writeString(target.resolve("keep"), "previous");
      assertThrows(IOException.class, () -> AtomicFiles.write(target, output -> output.write(1)));
      assertEquals("previous", Files.readString(target.resolve("keep")));
      try (var entries = Files.list(directory)) { assertEquals(1L, entries.count()); }
   }

   @Test void preparationPublishesOnlyAfterCommitAndCannotPublishTwice() throws IOException {
      Path target = directory.resolve("data");
      Files.writeString(target, "previous");
      try (var prepared = AtomicFiles.prepare(target,
            output -> output.write("complete".getBytes(StandardCharsets.UTF_8)))) {
         assertEquals("previous", Files.readString(target));
         prepared.commit();
         assertEquals("complete", Files.readString(target));
         assertThrows(IllegalStateException.class, prepared::commit);
      }
      try (var entries = Files.list(directory)) { assertEquals(1L, entries.count()); }
   }

   @Test void discardingAStalePreparationPreservesTheNewerPublication() throws IOException {
      Path target = directory.resolve("data");
      var stale = AtomicFiles.prepare(target,
         output -> output.write("stale".getBytes(StandardCharsets.UTF_8)));
      try (stale) {
         AtomicFiles.write(target, output -> output.write("newer".getBytes(StandardCharsets.UTF_8)));
      }
      stale.close();
      assertThrows(IllegalStateException.class, stale::commit);
      assertEquals("newer", Files.readString(target));
      try (var entries = Files.list(directory)) { assertEquals(1L, entries.count()); }
   }

   @Test void cancellingPreparationDoesNotCreateAMissingTarget() throws IOException {
      Path target = directory.resolve("data");
      try (var prepared = AtomicFiles.prepare(target, output -> output.write(1))) {
         assertFalse(Files.exists(target));
      }
      assertFalse(Files.exists(target));
      try (var entries = Files.list(directory)) { assertEquals(0L, entries.count()); }
   }
}
