package io.github.fastformer.server.recovery;

import static io.github.fastformer.core.persist.StoragePaths.JOURNAL_CORRECTION;
import static io.github.fastformer.core.persist.StoragePaths.JOURNAL_MANIFEST;
import static io.github.fastformer.core.persist.StoragePaths.JOURNAL_SEAL;

import com.mojang.logging.LogUtils;
import io.github.fastformer.common.persistence.IncompatibleWorldDataException;
import io.github.fastformer.common.persistence.MinecraftDataVersion;
import io.github.fastformer.core.persist.QuarantineFiles;
import io.github.fastformer.server.recovery.spi.HistoryReconciler;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.function.BooleanSupplier;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.common.IOUtilities;
import org.slf4j.Logger;

/** Startup scan, validation, reconciliation and cleanup after a durable world save. */
final class JournalStartupRecovery {
   private static final Logger LOGGER = LogUtils.getLogger();

   private JournalStartupRecovery() {}

   static boolean recoverAll(MinecraftServer server, Path directory, HistoryReconciler history, BooleanSupplier durableSave) {
      java.util.Objects.requireNonNull(history, "history reconciler");
      if (!Files.exists(directory)) {
         return true;
      }
      boolean success = true;
      List<Path> recovered = new ArrayList<>();
      try (var files = Files.list(directory)) {
         List<Path> entries = quarantineIncompatibleVersions(files.toList());
         if (!RecoveryJournalSegments.validateSegmentDirectories(entries)) {
            success = false;
         }
         for (Path path : entries) {
            String name = path.getFileName().toString();
            if (name.contains(".tmp-")) {
               try {
                  Files.deleteIfExists(path);
                } catch (IOException | RuntimeException exception) {
                   success = false;
                   LOGGER.error("Could not delete incomplete FastFormer journal temp file {}", path, exception);
                }
            }
         }
          List<Path> prepared = recoveryOrder(entries.stream()
             .filter(path -> Files.isDirectory(path) && Files.exists(path.resolve(JOURNAL_MANIFEST)) && !Files.exists(path.resolve(JOURNAL_SEAL)))
             .toList());
          List<Path> committed = entries.stream()
             .filter(path -> Files.isDirectory(path) && Files.exists(path.resolve(JOURNAL_SEAL)))
            .sorted(Comparator.comparing(path -> path.getFileName().toString()))
            .toList();
         for (Path path : prepared) {
            if (JournalReplayer.restore(server, path)) {
               recovered.add(path);
            } else {
               success = false;
            }
         }
         for (Path path : committed) {
            if (JournalReplayer.restore(server, path) && reconcileHistory(history, path)) {
               recovered.add(path);
            } else {
               success = false;
            }
         }
      } catch (IOException | RuntimeException exception) {
         LOGGER.error("Could not scan FastFormer recovery journals", exception);
         success = false;
      } catch (OutOfMemoryError error) {
         LOGGER.error("FastFormer recovery journal scan exceeded the safe JVM heap budget", error);
         success = false;
      }
      if (!recovered.isEmpty()) {
         // Do not remove the write-ahead log until the restored chunks are
         // durably flushed. A second crash during startup can then retry.
         if (!durableSave.getAsBoolean()) {
            LOGGER.error("FastFormer restored startup journals but the world save did not complete");
            success = false;
         } else {
            for (Path path : recovered) {
               try {
                   try (var children = Files.list(path)) {
                      for (Path child : children.toList()) {
                         Files.delete(child);
                      }
                   }
                   Files.deleteIfExists(path);
                } catch (IOException | RuntimeException exception) {
                   LOGGER.error("Recovered FastFormer journal will be retried next startup: {}", path, exception);
                   success = false;
                }
            }
         }
      }
      return success;
   }

   private static List<Path> quarantineIncompatibleVersions(List<Path> entries) throws IOException {
      List<Path> compatible = new ArrayList<>();
      for (Path entry : entries) {
         boolean segmented = Files.isDirectory(entry) &&
            (Files.exists(entry.resolve(JOURNAL_MANIFEST)) || Files.exists(entry.resolve(JOURNAL_SEAL)));
         if (!segmented) {
            compatible.add(entry);
            continue;
         }
         try {
               var manifest = RecoveryJournalManifest.read(entry.resolve(JOURNAL_MANIFEST));
               try (var children = Files.list(entry)) {
                  for (Path child : children.toList()) {
                     String childName = child.getFileName().toString();
                     if (childName.startsWith("segment-") && childName.endsWith(".dat")) {
                        int sequence;
                        try { sequence = Integer.parseInt(childName.substring(8, childName.length() - 4)); }
                        catch (NumberFormatException invalid) { throw new IOException("Invalid segment filename", invalid); }
                        MinecraftDataVersion.read(RecoveryJournalSegment.read(child, manifest.operationId(), sequence));
                     } else if (childName.equals(JOURNAL_CORRECTION)) {
                        MinecraftDataVersion.read(RecoveryJournalCodec.readCorrection(child));
                     }
                  }
               }
            compatible.add(entry);
         } catch (IncompatibleWorldDataException incompatible) {
            Path retained = QuarantineFiles.move(entry, List.of(), incompatible.getMessage());
            LOGGER.error("FastFormer quarantined incompatible recovery data at {}. An administrator must review it; "
               + "the operation was not replayed. Reason: {}", retained, incompatible.getMessage());
         } catch (IOException | RuntimeException invalid) {
            // Corrupt or unreadable journals keep the existing safety gate. Quarantine
            // only identifies a known version incompatibility, never disguises corruption.
            compatible.add(entry);
         }
      }
      return compatible.stream().filter(Files::exists).toList();
   }

   private static boolean reconcileHistory(HistoryReconciler history, Path path) {
      try {
         var manifest = RecoveryJournalManifest.read(path.resolve(JOURNAL_MANIFEST));
         history.reconcile(manifest.owner(), manifest.operationId()).join();
         return true;
      } catch (IOException | RuntimeException failure) {
         LOGGER.error("Could not reconcile history for committed journal {}", path, failure);
         return false;
      }
   }

   static List<Path> recoveryOrder(Collection<Path> files) {
      return files.stream()
         .sorted(Comparator.comparing((Path path) -> path.getFileName().toString()).reversed())
         .toList();
   }

   static boolean saveDurably(MinecraftServer server) {
      if (server == null) {
         return false;
      }
      try {
         if (!server.saveAllChunks(true, true, true)) {
            return false;
         }
         IOUtilities.waitUntilIOWorkerComplete();
         return true;
      } catch (RuntimeException exception) {
         LOGGER.error("FastFormer could not durably flush restored chunks", exception);
         return false;
      }
   }

}
