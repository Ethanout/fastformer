package io.github.fastformer.fastplace.history;

import io.github.fastformer.fastplace.world.snapshot.ReversibleBlockSnapshot;
import java.util.AbstractList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.server.level.ServerLevel;

/** Keeps the live before-image for confirmed overwrites and excludes skipped cells from recovery writes. */
final class HistoryConflictResolution {
   private final Map<Integer, ReversibleBlockSnapshot> sources = new HashMap<>();
   private final BitSet skipped = new BitSet();

   boolean hasSkippedCells() { return !skipped.isEmpty(); }

   boolean capture(ServerLevel level, WorldChangeBatch batch, int index, boolean skip) {
      var snapshot = ReversibleBlockSnapshot.capture(level, batch.position(index));
      if (snapshot.isEmpty()) return false;
      sources.put(index, snapshot.orElseThrow());
      skipped.set(index, skip);
      return true;
   }

   int match(ServerLevel level, WorldChangeBatch batch, int index, boolean undo) {
      if (skipped.get(index)) return 2;
      var source = sources.get(index);
      if (source == null) return batch.match(level, index, undo);
      if (batch.match(level, index, undo) == 2) return 2;
      return source.matches(level, batch.position(index)) ? 1 : 0;
   }

   boolean rollback(ServerLevel level, WorldChangeBatch batch, int index, boolean undo, int flags) {
      var source = sources.get(index);
      return source == null ? batch.apply(level, index, !undo, flags) : source.restore(level, flags);
   }

   int rollbackMatch(ServerLevel level, WorldChangeBatch batch, int index, boolean undo) {
      var source = sources.get(index);
      if (source == null) return batch.match(level, index, !undo);
      if (source.matches(level, batch.position(index))) return 2;
      return batch.matchesExpected(level, index, !undo) ? 1 : 0;
   }

   List<ReversibleBlockSnapshot> snapshots(WorldChangeBatch batch, boolean undo, boolean target) {
      var original = target ? batch.targetSnapshots(undo) : batch.sourceSnapshots(undo);
      return new AbstractList<>() {
         @Override public int size() { return original.size(); }
         @Override public ReversibleBlockSnapshot get(int index) {
            var replacement = sources.get(index);
            return replacement != null && (!target || skipped.get(index)) ? replacement : original.get(index);
         }
      };
   }
}
