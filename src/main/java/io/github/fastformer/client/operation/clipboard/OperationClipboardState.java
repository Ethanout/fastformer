package io.github.fastformer.client.operation.clipboard;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

/**
 * Holds only clipboard content that was loaded or saved successfully.
 *
 * <p>Publication rule: the memory copy changes only after durable storage confirms
 * the change. {@link #saveAndPublish} writes before it publishes, so a failed copy
 * keeps the previous clipboard and paste still serves the last successful content.
 * {@link #load} caches content only from a completed read, so a failed read and a
 * missing file both stay retryable.
 */
public final class OperationClipboardState {
   private OperationClipboard clipboard;

   public Optional<OperationClipboard> load(Loader loader) throws IOException {
      if (this.clipboard != null) {
         return Optional.of(this.clipboard);
      }
      Optional<OperationClipboard> loaded = Objects.requireNonNull(loader, "loader").load();
      loaded.ifPresent(value -> this.clipboard = value);
      return loaded;
   }

   public void saveAndPublish(OperationClipboard value, Saver saver) throws IOException {
      OperationClipboard candidate = Objects.requireNonNull(value, "value");
      Objects.requireNonNull(saver, "saver").save(candidate);
      this.clipboard = candidate;
   }

   Optional<OperationClipboard> cached() {
      return Optional.ofNullable(this.clipboard);
   }

   @FunctionalInterface
   public interface Loader {
      Optional<OperationClipboard> load() throws IOException;
   }

   @FunctionalInterface
   public interface Saver {
      void save(OperationClipboard value) throws IOException;
   }
}
