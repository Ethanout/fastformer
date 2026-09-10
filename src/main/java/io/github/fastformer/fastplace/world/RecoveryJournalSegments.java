package io.github.fastformer.fastplace.world;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Validates the durable segment set before recovery is allowed to proceed. */
final class RecoveryJournalSegments {
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
            boolean allowed = name.equals("manifest.dat") || name.equals("seal.done")
               || name.equals("correction.delta")
               || name.matches("segment-[0-9]{6}\\.dat")
               || (name.startsWith("segment-") && name.contains(".tmp-"));
            if (!allowed) {
               throw new IOException("Recovery segment set contains an unknown file: " + name);
            }
            if (name.startsWith("segment-") && name.contains(".tmp-")) {
               throw new IOException("Recovery segment set contains an incomplete temporary segment: " + name);
            }
            if (name.startsWith("segment-") && !name.matches("segment-[0-9]{6}\\.dat")) {
               throw new IOException("Recovery segment set contains an invalid segment file: " + name);
            }
         }
         files = entries.stream()
            .filter(path -> path.getFileName().toString().matches("segment-[0-9]{6}\\.dat"))
            .sorted(Comparator.comparing(path -> path.getFileName().toString()))
            .toList();
      }
      if (files.size() != expectedCount) {
         throw new IOException("Recovery segment count mismatch");
      }
      long digest = 1L;
      for (int index = 0; index < files.size(); index++) {
         CompoundSegment segment = read(directory, operationId, index);
         digest = appendDigest(digest, segment);
      }
      return new SegmentSet(files.size(), digest);
   }

   static CompoundSegment read(Path directory, UUID operationId, int sequence) throws IOException {
      if (directory == null || operationId == null || sequence < 0 || sequence >= MAX_SEGMENTS) {
         throw new IOException("Invalid recovery segment identity");
      }
      Path file = directory.resolve(String.format("segment-%06d.dat", sequence));
      return new CompoundSegment(sequence, RecoveryJournalSegment.read(file, operationId, sequence));
   }

   static long digest(List<CompoundSegment> segments) {
      if (segments == null || segments.isEmpty()) {
         throw new IllegalArgumentException("Recovery segment digest requires at least one segment");
      }
      long value = 1L;
      for (CompoundSegment segment : segments) {
         value = appendDigest(value, segment);
      }
      return value;
   }

   private static long appendDigest(long value, CompoundSegment segment) {
      if (segment == null || segment.payload() == null) {
         throw new IllegalArgumentException("Recovery segment digest contains a null segment");
      }
      return 31L * value + RecoveryJournalSegment.checksum(segment.payload());
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
         count = files.filter(path -> path.getFileName().toString().matches("segment-[0-9]{6}\\.dat")).count();
      }
      if (count == 0 || count > limit) throw new IOException("Invalid unsealed segment count");
      return inspectComplete(directory, operationId, (int) count);
   }

   record SegmentSet(int count, long digest) {
   }

   record CompoundSegment(int sequence, net.minecraft.nbt.CompoundTag payload) {
   }
}
