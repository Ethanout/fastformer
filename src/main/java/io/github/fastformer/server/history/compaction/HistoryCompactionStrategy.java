package io.github.fastformer.server.history.compaction;

import io.github.fastformer.server.history.entry.HistoryEntry;
import io.github.fastformer.server.history.entry.HistoryReplacement;
import java.util.List;
import java.util.Optional;

/** Immutable, worker-safe compaction rules; equality identifies one configuration. */
public interface HistoryCompactionStrategy<E extends HistoryEntry> {
   boolean enabled();
   int protectedRecords();
   Optional<HistoryReplacement<E>> compact(List<E> newestFirst);
}
