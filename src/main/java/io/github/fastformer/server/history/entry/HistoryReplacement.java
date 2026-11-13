package io.github.fastformer.server.history.entry;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** An ordered replacement computed from an immutable history snapshot. */
public record HistoryReplacement<E extends HistoryEntry>(int start, List<UUID> removed, Optional<E> batch) {
   public HistoryReplacement {
      if (start < 0 || removed.isEmpty()) throw new IllegalArgumentException("Empty or misplaced history replacement");
      removed = List.copyOf(removed);
      Objects.requireNonNull(batch, "batch");
   }
}
