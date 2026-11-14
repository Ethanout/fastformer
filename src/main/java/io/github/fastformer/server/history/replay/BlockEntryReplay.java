package io.github.fastformer.server.history.replay;

import io.github.fastformer.server.worldwrite.snapshot.ReversibleBlockSnapshot;
import io.github.fastformer.server.history.entry.WorldChangeBatch;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.server.level.ServerLevel;

/** Block-specific conflict and partial-write snapshots; the generic replay loop owns no block snapshots. */
final class BlockEntryReplay implements EntryReplayState {
   private final WorldChangeBatch batch;
   private final boolean undo;
   private final HistoryConflictResolution conflicts = new HistoryConflictResolution();

   BlockEntryReplay(WorldChangeBatch batch, boolean undo) {
      this.batch = batch;
      this.undo = undo;
   }

   @Override public boolean captureConflict(ServerLevel level, int index, boolean skip) {
      return conflicts.capture(level, batch, index, skip);
   }

   @Override public int match(ServerLevel level, int index) {
      return conflicts.match(level, batch, index, undo);
   }

   @Override public boolean rollback(ServerLevel level, int index, int flags) {
      return conflicts.rollback(level, batch, index, undo, flags);
   }

   @Override public int rollbackMatch(ServerLevel level, int index) {
      return conflicts.rollbackMatch(level, batch, index, undo);
   }

   @Override public Optional<Predicate<ServerLevel>> capturePartial(ServerLevel level, int index) {
      return ReversibleBlockSnapshot.capture(level, batch.position(index))
         .map(snapshot -> current -> snapshot.matches(current, batch.position(index)));
   }

   @Override public boolean hasSkippedCells() { return conflicts.hasSkippedCells(); }

   @Override public ReplayJournal journal() {
      return new BlockReplayJournal(batch, undo, conflicts);
   }
}
