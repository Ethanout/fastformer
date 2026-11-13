package io.github.fastformer.server.history.compaction;

import io.github.fastformer.common.history.HistoryCompressionLimits;
import io.github.fastformer.common.history.CompressionTier;

import io.github.fastformer.server.history.entry.HistoryReplacement;

import io.github.fastformer.server.history.entry.BlockEditHistoryMetadata;
import io.github.fastformer.server.history.entry.HistoryBatchComposition;
import io.github.fastformer.server.history.entry.WorldChangeBatch;
import io.github.fastformer.server.history.entry.HistoryEntry;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** Composes chronological block edit records from immutable snapshots, without reading a world. */
public final class TinkerHistoryCompactor {
   private static final int VANILLA_MAX_DISTANCE = 5;

   public static Optional<HistoryReplacement<WorldChangeBatch>> compact(List<? extends HistoryEntry> newestFirst, int limit) {
      return compact(newestFirst, HistoryCompressionLimits.uniform(limit));
   }

   public static Optional<HistoryReplacement<WorldChangeBatch>> compact(List<? extends HistoryEntry> newestFirst, HistoryCompressionLimits limits) {
      return compact(newestFirst, limits, HistoryCompactionPolicy.DEFAULTS);
   }

   public static Optional<HistoryReplacement<WorldChangeBatch>> compact(List<? extends HistoryEntry> newestFirst, HistoryCompressionLimits limits, HistoryCompactionPolicy policy) {
      if (!policy.enabled()) return Optional.empty();
      // Complete smaller tiers first, so an old run can become groups of groups in later passes.
      for (var tier : CompressionTier.values()) {
         var result = compactTier(newestFirst, limits, policy, tier);
         if (result.isPresent()) return result;
      }
      return Optional.empty();
   }

   private static Optional<HistoryReplacement<WorldChangeBatch>> compactTier(List<? extends HistoryEntry> newestFirst, HistoryCompressionLimits limits,
      HistoryCompactionPolicy policy, CompressionTier tier) {
      int groupSize = limits.groupSize(tier);
      if (groupSize < 2) return Optional.empty();
      int minimumStage = limits.minimumSourceStage(tier);
      long newerActions = 0;
      for (int start = 0; start + 1 < newestFirst.size(); start++) {
         HistoryEntry entry = newestFirst.get(start);
         long depth = newerActions;
         newerActions += entry instanceof WorldChangeBatch block && block.tinker() != null ? block.tinker().actions() : 1;
         if (!(entry instanceof WorldChangeBatch newest)) continue;
         if (start < policy.protectedRecords()) continue;
         if (depth <= limits.after(tier) || newest.tinker() == null || newest.size() > policy.maxCells()
            || newest.tinker().compressionStage() < minimumStage || newest.tinker().compressionStage() >= tier.stage()) continue;
         List<WorldChangeBatch> run = new ArrayList<>();
         Set<BlockPos> positions = new HashSet<>();
         int actions = 0;
         int cells = 0;
         long bytes = 0L;
         for (int index = start; index < newestFirst.size(); index++) {
            if (!(newestFirst.get(index) instanceof WorldChangeBatch candidate)) break;
            if (candidate.tinker() == null || !candidate.dimension().equals(newest.dimension())
               || !candidate.tinker().sharesGroup(newest.tinker())
               || candidate.tinker().compressionStage() < minimumStage
               || candidate.tinker().compressionStage() >= tier.stage()
               || (long)actions + candidate.tinker().actions() > BlockEditHistoryMetadata.MAX_ACTIONS
               || (long)cells + candidate.size() > policy.maxCells() || bytes + candidate.estimatedBytes() > policy.maxBytes()
               || (!run.isEmpty() && !spatiallyCompatible(candidate, run.getLast(), positions, policy))) break;
            run.add(candidate);
            actions += candidate.tinker().actions();
            cells += candidate.size();
            bytes += candidate.estimatedBytes();
            if (!newest.tinker().vanillaEdit()) {
               for (int cell = 0; cell < candidate.size(); cell++) positions.add(candidate.position(cell));
            }
            if (run.size() == groupSize) break;
         }
         if (run.size() != groupSize) continue;
         var composed = compose(run, actions, tier.stage());
         if (composed != null) return Optional.of(new HistoryReplacement<>(start,
            run.stream().map(WorldChangeBatch::operationId).toList(), composed));
      }
      return Optional.empty();
   }

   private static boolean spatiallyCompatible(WorldChangeBatch candidate, WorldChangeBatch previous,
      Set<BlockPos> positions, HistoryCompactionPolicy policy) {
      return candidate.tinker().vanillaEdit()
         ? candidate.bounds().within(previous.bounds(), VANILLA_MAX_DISTANCE)
         : touches(candidate, positions, policy.includeDiagonalNeighbors());
   }

   private static boolean touches(WorldChangeBatch batch, Set<BlockPos> positions, boolean includeDiagonals) {
      for (int index = 0; index < batch.size(); index++) {
         BlockPos position = batch.position(index);
         if (positions.contains(position)) return true;
         for (Direction direction : Direction.values()) {
            if (positions.contains(position.relative(direction))) return true;
         }
         if (includeDiagonals) {
            for (int x = -1; x <= 1; x++) for (int y = -1; y <= 1; y++) for (int z = -1; z <= 1; z++) {
               if (positions.contains(position.offset(x, y, z))) return true;
            }
         }
      }
      return false;
   }

   /** A discontinuity means an external change occurred between records, so retain them separately. */
   private static Optional<WorldChangeBatch> compose(List<WorldChangeBatch> newestFirst, int actions, int stage) {
      WorldChangeBatch newest = newestFirst.getFirst();
      return HistoryBatchComposition.compose(newestFirst)
         .map(composition -> composition.batch().map(batch -> batch.withOperationId(UUID.randomUUID())
            .withTinker(newest.tinker().compressed(actions, stage))))
         .orElse(null);
   }
}
