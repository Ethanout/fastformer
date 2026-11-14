package io.github.fastformer.server.history.entry;

import io.github.fastformer.server.worldwrite.snapshot.ReversibleBlockSnapshot;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;

/** An absent composition is discontinuous; a composition without a batch has no net change. */
public record HistoryBatchComposition(Optional<WorldChangeBatch> batch) {
   public static Optional<HistoryBatchComposition> compose(List<WorldChangeBatch> newestFirst) {
      WorldChangeBatch newest = newestFirst.getFirst();
      HistoryBounds bounds = newest.bounds();
      Map<BlockPos, ReversibleBlockSnapshot> before = new LinkedHashMap<>();
      Map<BlockPos, ReversibleBlockSnapshot> after = new LinkedHashMap<>();
      for (WorldChangeBatch batch : newestFirst.reversed()) {
         if (!batch.dimension().equals(newest.dimension())) return Optional.empty();
         bounds = bounds.union(batch.bounds());
         var sources = batch.targetSnapshots(true);
         var targets = batch.sourceSnapshots(true);
         for (int index = 0; index < batch.size(); index++) {
            BlockPos position = batch.position(index);
            ReversibleBlockSnapshot source = sources.get(index);
            ReversibleBlockSnapshot previous = after.get(position);
            if (previous != null && !previous.sameContents(source)) return Optional.empty();
            before.putIfAbsent(position, source);
            after.put(position, targets.get(index));
         }
      }
      HistoryBounds retainedBounds = bounds;
      return Optional.of(new HistoryBatchComposition(
         WorldChangeBatch.capturePairsByPos(newest.dimension(), before.values(), after)
            .map(batch -> batch.withTinker(null, retainedBounds))));
   }
}
