package io.github.fastformer.server.history.compaction;

import io.github.fastformer.common.history.HistoryCompressionLimits;

import io.github.fastformer.server.history.entry.HistoryReplacement;
import io.github.fastformer.server.history.entry.HistoryEntry;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** A frozen block-edit policy passed to the generic history worker. */
public record BlockHistoryCompactionRules(HistoryCompressionLimits limits, HistoryCompactionPolicy policy)
   implements HistoryCompactionStrategy<HistoryEntry> {
   public BlockHistoryCompactionRules {
      Objects.requireNonNull(limits, "limits");
      Objects.requireNonNull(policy, "policy");
   }

   @Override public boolean enabled() { return limits.enabled() && policy.enabled(); }
   @Override public int protectedRecords() { return policy.protectedRecords(); }
   @Override public Optional<HistoryReplacement<HistoryEntry>> compact(List<HistoryEntry> newestFirst) {
      return TinkerHistoryCompactor.compact(newestFirst, limits, policy)
         .map(change -> new HistoryReplacement<>(change.start(), change.removed(), change.batch().map(entry -> entry)));
   }
}
