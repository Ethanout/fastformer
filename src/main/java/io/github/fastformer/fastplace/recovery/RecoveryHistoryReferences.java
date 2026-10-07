package io.github.fastformer.fastplace.recovery;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Reads recovery identities without accessing world state. */
public final class RecoveryHistoryReferences {
   private RecoveryHistoryReferences() { }

   public static Set<UUID> operations(Path directory, UUID owner) {
      if (!Files.exists(directory)) return Set.of();
      Set<UUID> operations = new HashSet<>();
      try (var entries = Files.list(directory)) {
         for (Path entry : entries.toList()) {
            Path manifestPath = entry.resolve("manifest.dat");
            if (!Files.isDirectory(entry) || !Files.exists(manifestPath)) continue;
            try {
               var manifest = RecoveryJournalManifest.read(manifestPath);
               if (manifest.owner().equals(owner)) operations.add(manifest.operationId());
            } catch (java.nio.file.NoSuchFileException removedDuringCleanup) {
               // A durable world save can remove the journal while this scan runs.
            }
         }
         return Set.copyOf(operations);
      } catch (IOException failure) {
         throw new UncheckedIOException(failure);
      }
   }
}
