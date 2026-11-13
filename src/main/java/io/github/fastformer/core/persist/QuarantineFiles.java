package io.github.fastformer.core.persist;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/** Retains incompatible data and its related files outside the active storage namespace. */
public final class QuarantineFiles {
   private QuarantineFiles() {}

   /**
    * Copies every related file durably before removing the active primary file.
    * A failure during copying leaves the active family untouched. Directories move as a unit.
    */
   public static Path move(Path primary, List<Path> related, String reason) throws IOException {
      Path source = primary.toAbsolutePath().normalize();
      Path root = source.getParent().resolve("quarantine");
      Files.createDirectories(root);
      Path target = root.resolve(source.getFileName() + "-" + UUID.randomUUID());
      if (Files.isDirectory(source)) {
         AtomicFiles.move(source, target);
      } else {
         Files.createDirectories(target);
         copy(source, target);
         for (Path sidecar : related) if (Files.exists(sidecar)) copy(sidecar, target);
         // The primary is removed only after every evidence file is durable. A crash
         // before this point may leave a duplicate, never a partially active family.
         Files.delete(source);
         for (Path sidecar : related) Files.deleteIfExists(sidecar);
      }
      AtomicFiles.write(target.resolve("reason.txt"), output ->
         output.write(reason.getBytes(StandardCharsets.UTF_8)));
      return target;
   }

   private static void copy(Path source, Path directory) throws IOException {
      AtomicFiles.write(directory.resolve(source.getFileName()), output -> {
         try (var input = Files.newInputStream(source)) { input.transferTo(output); }
      });
   }
}
