package io.github.fastformer.server.recovery;

import static io.github.fastformer.core.persist.StoragePaths.JOURNAL_CORRECTION;
import static io.github.fastformer.core.persist.StoragePaths.JOURNAL_MANIFEST;
import static io.github.fastformer.core.persist.StoragePaths.JOURNAL_SEAL;
import static io.github.fastformer.server.recovery.RecoveryJournalCodec.validateHeader;

import com.mojang.logging.LogUtils;
import io.github.fastformer.core.persist.StoragePaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.zip.CRC32C;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;

/** Validates the durable segment set before recovery is allowed to proceed. */
final class RecoveryJournalSegments {
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final int MAX_SEGMENTS = 1_000_000;
   private RecoveryJournalSegments() {
   }

   static List<CompoundSegment> readComplete(Path directory, UUID operationId, int expectedCount)
      throws IOException {
      SegmentSet segmentSet = inspectComplete(directory, operationId, expectedCount);
      List<CompoundSegment> result = new ArrayList<>(segmentSet.count());
      for (int index = 0; index < segmentSet.count(); index++) {
         result.add(read(directory, operationId, index));
      }
      return List.copyOf(result);
   }

   /** Validates every segment while retaining only aggregate metadata. */
   static SegmentSet inspectComplete(Path directory, UUID operationId, int expectedCount) throws IOException {
      if (directory == null || operationId == null || expectedCount <= 0
         || expectedCount > MAX_SEGMENTS || !Files.isDirectory(directory)) {
         throw new IOException("Invalid recovery segment directory");
      }
      List<Path> files;
      try (var stream = Files.list(directory)) {
         List<Path> entries = stream.toList();
         for (Path entry : entries) {
            String name = entry.getFileName().toString();
            boolean allowed = name.equals(JOURNAL_MANIFEST) || name.equals(JOURNAL_SEAL)
               || name.equals(JOURNAL_CORRECTION)
               || name.matches(StoragePaths.JOURNAL_SEGMENT_PATTERN)
               || (name.startsWith("segment-") && name.contains(".tmp-"));
            if (!allowed) {
               throw new IOException("Recovery segment set contains an unknown file: " + name);
            }
            if (name.startsWith("segment-") && name.contains(".tmp-")) {
               throw new IOException("Recovery segment set contains an incomplete temporary segment: " + name);
            }
            if (name.startsWith("segment-") && !name.matches(StoragePaths.JOURNAL_SEGMENT_PATTERN)) {
               throw new IOException("Recovery segment set contains an invalid segment file: " + name);
            }
         }
         files = entries.stream()
            .filter(path -> path.getFileName().toString().matches(StoragePaths.JOURNAL_SEGMENT_PATTERN))
            .sorted(Comparator.comparing(path -> path.getFileName().toString()))
            .toList();
      }
      if (files.size() != expectedCount) {
         throw new IOException("Recovery segment count mismatch");
      }
      var digest = new SegmentDigest();
      for (int index = 0; index < files.size(); index++) {
         CompoundSegment segment = read(directory, operationId, index);
         digest.append(segment);
      }
      return new SegmentSet(files.size(), digest.value());
   }

   static CompoundSegment read(Path directory, UUID operationId, int sequence) throws IOException {
      if (directory == null || operationId == null || sequence < 0 || sequence >= MAX_SEGMENTS) {
         throw new IOException("Invalid recovery segment identity");
      }
      Path file = StoragePaths.journalSegment(directory, sequence);
      var verified = RecoveryJournalSegment.readVerified(file, operationId, sequence);
      return new CompoundSegment(sequence, verified.payload(), verified.checksum());
   }

   static long digest(List<CompoundSegment> segments) {
      if (segments == null || segments.isEmpty()) {
         throw new IllegalArgumentException("Recovery segment digest requires at least one segment");
      }
      var digest = new SegmentDigest();
      for (CompoundSegment segment : segments) {
         digest.append(segment);
      }
      return digest.value();
   }

   private static final class SegmentDigest {
      private final CRC32C crc = new CRC32C();

      void append(CompoundSegment segment) {
         if (segment == null || segment.payload() == null) {
            throw new IllegalArgumentException("Recovery segment digest contains a null segment");
         }
         for (int shift = 24; shift >= 0; shift -= 8) crc.update((segment.sequence() >>> shift) & 255);
         for (int shift = 56; shift >= 0; shift -= 8) crc.update((int)(segment.checksum() >>> shift) & 255);
      }
      long value() { return crc.getValue(); }
   }

   static List<CompoundSegment> readUnsealed(Path directory, UUID operationId, int limit) throws IOException {
      SegmentSet segmentSet = inspectUnsealed(directory, operationId, limit);
      List<CompoundSegment> result = new ArrayList<>(segmentSet.count());
      for (int index = 0; index < segmentSet.count(); index++) {
         result.add(read(directory, operationId, index));
      }
      return List.copyOf(result);
   }

   static SegmentSet inspectUnsealed(Path directory, UUID operationId, int limit) throws IOException {
      long count;
      try (var files = Files.list(directory)) {
         count = files.filter(path -> path.getFileName().toString().matches(StoragePaths.JOURNAL_SEGMENT_PATTERN)).count();
      }
      if (count == 0 || count > limit) throw new IOException("Invalid unsealed segment count");
      return inspectComplete(directory, operationId, (int) count);
   }

   /**
    * Checks sealed segment files before startup restores their world contents.
    */
   static boolean validateSegmentDirectories(Collection<Path> entries) {
      if (entries == null) {
         return false;
      }
      boolean valid = true;
      for (Path directory : entries) {
         if (!Files.isDirectory(directory)) {
            continue;
         }
         boolean segmentedMarker = Files.exists(directory.resolve(JOURNAL_MANIFEST))
            || Files.exists(directory.resolve(JOURNAL_SEAL));
         if (!segmentedMarker) {
            try (var stream = Files.list(directory)) {
               segmentedMarker = stream.anyMatch(path -> path.getFileName().toString().startsWith("segment-"));
            } catch (IOException | RuntimeException exception) {
               valid = false;
               LOGGER.error("Could not inspect segmented recovery operation: {}", directory, exception);
               continue;
            }
         }
         if (!segmentedMarker) {
            continue;
         }
         try {
            if (!Files.exists(directory.resolve(JOURNAL_MANIFEST))) {
               throw new IOException("Segmented recovery operation has no manifest: " + directory);
            }
            RecoveryJournalManifest.Manifest manifest = RecoveryJournalManifest.read(directory.resolve(JOURNAL_MANIFEST));
            Path seal = directory.resolve(JOURNAL_SEAL);
            if (!Files.exists(seal)) {
               var segments = RecoveryJournalSegments.inspectUnsealed(directory, manifest.operationId(), manifest.segmentLimit());
               ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(manifest.dimension()));
               for (int index = 0; index < segments.count(); index++) {
                  validateHeader(RecoveryJournalSegments.read(directory, manifest.operationId(), index).payload(), dimension);
               }
               continue;
            }
            RecoveryJournalSeal.Seal sealData = RecoveryJournalSeal.read(seal, manifest.operationId());
            if (sealData.segmentCount() > manifest.segmentLimit()) {
               throw new IOException("Segmented recovery operation exceeds manifest segment limit");
            }
            RecoveryJournalSegments.SegmentSet segments = RecoveryJournalSegments.inspectComplete(
               directory, manifest.operationId(), sealData.segmentCount()
            );
            if (segments.digest() != sealData.digest()) {
               throw new IOException("Segmented recovery operation digest mismatch");
            }
            ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(manifest.dimension()));
            for (int index = 0; index < segments.count(); index++) {
               validateHeader(RecoveryJournalSegments.read(directory, manifest.operationId(), index).payload(), dimension);
            }
         } catch (IOException | RuntimeException exception) {
            valid = false;
            LOGGER.error("FastFormer found an invalid segmented recovery operation: {}", directory, exception);
         }
      }
      return valid;
   }

   record SegmentSet(int count, long digest) {
   }

   record CompoundSegment(int sequence, net.minecraft.nbt.CompoundTag payload, long checksum) {
   }
}
