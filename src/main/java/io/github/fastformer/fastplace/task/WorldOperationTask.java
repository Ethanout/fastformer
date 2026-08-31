package io.github.fastformer.fastplace.task;

import io.github.fastformer.fastplace.world.PersistentRecoveryJournal;
import io.github.fastformer.fastplace.world.ReversibleBlockSnapshot;
import io.github.fastformer.fastplace.world.WorldChangeBatch;
import io.github.fastformer.fastplace.world.WorldTaskBudget;
import io.github.fastformer.fastplace.world.WorldTaskContext;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/** A UUID-owned server task that validates and atomically changes one world. */
public interface WorldOperationTask {
   OperationTaskResult tick(WorldTaskContext context, ServerLevel level, WorldTaskBudget budget);

   String phaseName();

   void cancelJournalPreparation();

   PersistentRecoveryJournal journal();

   boolean acquireLease(WorldTaskContext context);

   void releaseLease(WorldTaskContext context);

   void releaseAfterCancelledJournal(WorldTaskContext context);

   boolean hasWrites();

   ArrayDeque<ReversibleBlockSnapshot> undoChanges();

   Map<BlockPos, ReversibleBlockSnapshot> afterChanges();

   ResourceKey<Level> dimension();

   Optional<WorldChangeBatch> preparedBatch();
}
