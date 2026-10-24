package io.github.fastformer.fastplace.history;

import io.github.fastformer.fastplace.world.snapshot.ReversibleBlockSnapshot;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** Composes chronological block edit records from immutable snapshots, without reading a world. */
final class TinkerHistoryCompactor {
   private static final int VANILLA_MAX_DISTANCE = 5;

   record Replacement(int start, List<UUID> removed, Optional<WorldChangeBatch> batch) {
      Replacement { removed = List.copyOf(removed); }
   }

   static Optional<Replacement> compact(List<WorldChangeBatch> newestFirst, int limit) {
      return compact(newestFirst, TinkerHistoryMergeLimits.uniform(limit));
   }

   static Optional<Replacement> compact(List<WorldChangeBatch> newestFirst, TinkerHistoryMergeLimits limits) {
      return compact(newestFirst, limits, HistoryStorageConfig.compactionPolicy());
   }

   static Optional<Replacement> compact(List<WorldChangeBatch> newestFirst, TinkerHistoryMergeLimits limits, HistoryCompactionPolicy policy) {
      if (!policy.enabled()) return Optional.empty();
      // Complete smaller tiers first, so an old run can become groups of groups in later passes.
      for (var tier : TinkerHistoryMergeLimits.Tier.values()) {
         var result = compactTier(newestFirst, limits, policy, tier);
         if (result.isPresent()) return result;
      }
      return Optional.empty();
   }

   private static Optional<Replacement> compactTier(List<WorldChangeBatch> newestFirst, TinkerHistoryMergeLimits limits,
      HistoryCompactionPolicy policy, TinkerHistoryMergeLimits.Tier tier) {
      int groupSize = limits.groupSize(tier);
      if (groupSize < 2) return Optional.empty();
      int minimumStage = limits.minimumSourceStage(tier);
      long newerActions = 0;
      for (int start = 0; start + 1 < newestFirst.size(); start++) {
         WorldChangeBatch newest = newestFirst.get(start);
         long depth = newerActions;
         newerActions += newest.tinker() == null ? 1 : newest.tinker().actions();
         if (start < policy.protectedRecords()) continue;
         if (depth <= limits.after(tier) || newest.tinker() == null || newest.size() > policy.maxCells()
            || newest.tinker().compressionStage() < minimumStage || newest.tinker().compressionStage() >= tier.stage()) continue;
         List<WorldChangeBatch> run = new ArrayList<>();
         Set<BlockPos> positions = new HashSet<>();
         int actions = 0;
         int cells = 0;
         long bytes = 0L;
         for (int index = start; index < newestFirst.size(); index++) {
            WorldChangeBatch candidate = newestFirst.get(index);
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
         if (composed != null) return Optional.of(new Replacement(start,
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
      // Capture the full edited region before net-zero cells are removed by composition.
      HistoryBounds bounds = newestFirst.getFirst().bounds();
      for (WorldChangeBatch batch : newestFirst) bounds = bounds.union(batch.bounds());
      HistoryBounds mergedBounds = bounds;
      Map<BlockPos, ReversibleBlockSnapshot> before = new LinkedHashMap<>();
      Map<BlockPos, ReversibleBlockSnapshot> after = new LinkedHashMap<>();
      for (WorldChangeBatch batch : newestFirst.reversed()) {
         var sources = batch.targetSnapshots(true);
         var targets = batch.sourceSnapshots(true);
         for (int index = 0; index < batch.size(); index++) {
            BlockPos position = batch.position(index);
            ReversibleBlockSnapshot source = sources.get(index);
            ReversibleBlockSnapshot previous = after.get(position);
            if (previous != null && !previous.sameContents(source)) return null;
            before.putIfAbsent(position, source);
            after.put(position, targets.get(index));
         }
      }
      WorldChangeBatch newest = newestFirst.getFirst();
      return WorldChangeBatch.capturePairsByPos(newest.dimension(), before.values(), after)
         .map(batch -> batch.withOperationId(UUID.randomUUID())
            .withTinker(newest.tinker().compressed(actions, stage), mergedBounds));
   }
}
