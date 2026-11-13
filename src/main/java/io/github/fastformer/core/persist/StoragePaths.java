package io.github.fastformer.core.persist;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

/** Current on-disk layout. Callers supply the world root; no game state is consulted. */
public final class StoragePaths {
   public static final String JOURNAL_MANIFEST = "manifest.dat";
   public static final String JOURNAL_SEAL = "seal.done";
   public static final String JOURNAL_CORRECTION = "correction.delta";
   public static final String JOURNAL_SEGMENT_PATTERN = "segment-[0-9]{6}\\.dat";

   private StoragePaths() {}

   public static Path history(Path worldRoot) {
      return Objects.requireNonNull(worldRoot, "world root").resolve("fastformer-history");
   }

   public static Path recovery(Path worldRoot) {
      return Objects.requireNonNull(worldRoot, "world root").resolve("fastformer-recovery");
   }

   public static Path journalSegment(Path operationDirectory, int sequence) {
      if (sequence < 0 || sequence > 999_999) throw new IllegalArgumentException("Invalid journal segment sequence");
      return Objects.requireNonNull(operationDirectory, "operation directory")
         .resolve(String.format(Locale.ROOT, "segment-%06d.dat", sequence));
   }
}
