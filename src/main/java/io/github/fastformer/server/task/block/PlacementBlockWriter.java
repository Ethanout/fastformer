package io.github.fastformer.server.task.block;

import io.github.fastformer.common.placement.PlacementUpdateMode;
import io.github.fastformer.common.workspace.model.OperationConflictMode;
import io.github.fastformer.server.feedback.PlayerMessages;
import io.github.fastformer.server.task.WorldTaskContext;
import io.github.fastformer.server.worldwrite.snapshot.BlockSnapshotReader;
import io.github.fastformer.server.worldwrite.snapshot.ReversibleBlockSnapshot;
import io.github.fastformer.server.worldwrite.transaction.WorldChangeTransaction;
import io.github.fastformer.server.worldwrite.effect.WorldWriteSideEffectGuard;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/** Performs one placement while retaining recovery state before any world callback can run. */
final class PlacementBlockWriter {
   enum Result { PLACED, SKIPPED, FAILED }
   private PlacementBlockWriter() {}

   static Result write(WorldTaskContext context, ServerLevel level, BlockPos pos, BlockState state,
      OperationConflictMode conflicts, PlacementUpdateMode updates, WorldChangeTransaction transaction,
      BlockSnapshotReader snapshots) {
      BlockState previous = level.getBlockState(pos);
      ReversibleBlockSnapshot expected = transaction.expectedAt(pos);
      if (expected != null && !expected.matches(level, pos)) {
         return conflicts == OperationConflictMode.KEEP_EXISTING && !previous.canBeReplaced() ? Result.SKIPPED : Result.FAILED;
      }
      if (conflicts == OperationConflictMode.KEEP_EXISTING && !previous.canBeReplaced() || previous.equals(state)) return Result.SKIPPED;
      ReversibleBlockSnapshot before = expected;
      if (before == null) {
         Optional<ReversibleBlockSnapshot> read = snapshots.capture(level, pos);
         if (read.isEmpty()) {
            context.actionBar(PlayerMessages.text("fastformer.message.block_snapshot_failed", pos.toShortString()));
            return Result.FAILED;
         }
         before = read.orElseThrow();
      }
      transaction.recordBefore(before);
      boolean placed = WorldWriteSideEffectGuard.setBlock(level, pos, state, updates.flags());
      Optional<ReversibleBlockSnapshot> written = snapshots.capture(level, pos);
      transaction.recordAfter(pos, written.orElseGet(() ->
         new ReversibleBlockSnapshot(pos, level.getBlockState(pos), level.getFluidState(pos), null)));
      if (updates == PlacementUpdateMode.NORMAL && (!placed || written.isPresent())) {
         transaction.refreshOwnedNeighbors(level, pos);
      }
      return placed && written.isPresent() ? Result.PLACED : Result.FAILED;
   }
}
