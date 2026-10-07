package io.github.fastformer.fastplace.history;

/** Immutable config snapshot shared with a worker and checked again before publication. */
public record HistoryCompactionPolicy(boolean enabled, int protectedRecords, int maxCells, long maxBytes,
   boolean includeDiagonalNeighbors, TinkerHistoryGroupingRules grouping) {
   public static final HistoryCompactionPolicy DEFAULTS = new HistoryCompactionPolicy(
      true, 5, 4096, 8L * 1024 * 1024, false, TinkerHistoryGroupingRules.DEFAULTS);

   public HistoryCompactionPolicy {
      if (protectedRecords < 1 || maxCells < 1 || maxBytes < 1 || grouping == null) {
         throw new IllegalArgumentException("Invalid history compaction policy");
      }
   }
}
