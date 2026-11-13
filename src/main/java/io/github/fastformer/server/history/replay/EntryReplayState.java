package io.github.fastformer.server.history.replay;

import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.server.level.ServerLevel;

/** Mutable before-images for one entry and one replay direction, owned by its replay task. */
public interface EntryReplayState {
   boolean captureConflict(ServerLevel level, int index, boolean skip);
   int match(ServerLevel level, int index);
   boolean rollback(ServerLevel level, int index, int flags);
   int rollbackMatch(ServerLevel level, int index);
   Optional<Predicate<ServerLevel>> capturePartial(ServerLevel level, int index);
   boolean hasSkippedCells();
   ReplayJournal journal();
}
