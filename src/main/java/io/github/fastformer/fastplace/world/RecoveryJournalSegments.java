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
      List<CompoundSegment> result = new ArrayList<>(files.size());
      for (int index = 0; index < files.size(); index++) {
         result.add(new CompoundSegment(index, RecoveryJournalSegment.read(files.get(index), operationId, index)));
      }
      return List.copyOf(result);
   }

   static long digest(List<CompoundSegment> segments) {
      if (segments == null || segments.isEmpty()) {
         throw new IllegalArgumentException("Recovery segment digest requires at least one segment");
      }
      long value = 1L;
      for (CompoundSegment segment : segments) {
         if (segment == null || segment.payload() == null) {
            throw new IllegalArgumentException("Recovery segment digest contains a null segment");
         }
         value = 31L * value + RecoveryJournalSegment.checksum(segment.payload());
      }
      return value;
   }

   static List<CompoundSegment> readUnsealed(Path directory, UUID operationId, int limit) throws IOException {
      long count;
      try (var files = Files.list(directory)) {
         count = files.filter(path -> path.getFileName().toString().matches("segment-[0-9]{6}\\.dat")).count();
      }
      if (count == 0 || count > limit) throw new IOException("Invalid unsealed segment count");
      return readComplete(directory, operationId, (int) count);
   }

   record CompoundSegment(int sequence, net.minecraft.nbt.CompoundTag payload) {
   }
}
