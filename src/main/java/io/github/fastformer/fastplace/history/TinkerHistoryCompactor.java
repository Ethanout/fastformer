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

/** Composes adjacent chronological wrench records from immutable snapshots, without reading a world. */
final class TinkerHistoryCompactor {

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
      for (int start = policy.protectedRecords(); start + 1 < newestFirst.size(); start++) {
         WorldChangeBatch newest = newestFirst.get(start);
         if (newest.tinker() == null || newest.size() > policy.maxCells()) continue;
         int bounded = limits.limit(newest.tinker().distanceBand());
         if (newest.tinker().actions() >= bounded) continue;
         List<WorldChangeBatch> run = new ArrayList<>();
         Set<BlockPos> positions = new HashSet<>();
         int actions = 0;
         int cells = 0;
         long bytes = 0L;
         for (int index = start; index < newestFirst.size(); index++) {
            WorldChangeBatch candidate = newestFirst.get(index);
            if (candidate.tinker() == null || !candidate.dimension().equals(newest.dimension())
               || !candidate.tinker().cluster().equals(newest.tinker().cluster())
               || candidate.tinker().distanceBand() != newest.tinker().distanceBand()
               || actions + candidate.tinker().actions() > bounded
               || cells + candidate.size() > policy.maxCells() || bytes + candidate.estimatedBytes() > policy.maxBytes()
               || (!run.isEmpty() && !touches(candidate, positions, policy.includeDiagonalNeighbors()))) break;
            run.add(candidate);
            actions += candidate.tinker().actions();
            cells += candidate.size();
            bytes += candidate.estimatedBytes();
            for (int cell = 0; cell < candidate.size(); cell++) positions.add(candidate.position(cell));
         }
         if (run.size() < 2) continue;
         var composed = compose(run, actions);
         if (composed != null) return Optional.of(new Replacement(start,
            run.stream().map(WorldChangeBatch::operationId).toList(), composed));
      }
      return Optional.empty();
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
   private static Optional<WorldChangeBatch> compose(List<WorldChangeBatch> newestFirst, int actions) {
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
            .withTinker(new BlockEditHistoryMetadata(newest.tinker().cluster(), actions, newest.tinker().distanceBand())));
   }
}
