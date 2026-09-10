package io.github.fastformer.fastplace.task;

import io.github.fastformer.fastplace.OperationConflictMode;
import io.github.fastformer.fastplace.world.ReversibleBlockSnapshot;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Compact final-state prediction used while encoding a selection journal. */
final class SelectionJournalPrediction {
   private static final int CLEARED = -1;
   private static final int UNCHANGED = -2;

   private final List<ReversibleBlockSnapshot> sources;
   private final Function<BlockPos, ReversibleBlockSnapshot> originalAt;
   private final OperationConflictMode conflictMode;
   private final Long2IntOpenHashMap sourceIndexByPosition = new Long2IntOpenHashMap();

   SelectionJournalPrediction(
      List<ReversibleBlockSnapshot> sources,
      Function<BlockPos, ReversibleBlockSnapshot> originalAt,
      OperationConflictMode conflictMode
   ) {
      if (sources == null || originalAt == null || conflictMode == null) {
         throw new IllegalArgumentException("Selection journal prediction requires source and conflict policy");
      }
      this.sources = sources;
      this.originalAt = originalAt;
      this.conflictMode = conflictMode;
      this.sourceIndexByPosition.defaultReturnValue(UNCHANGED);
   }

   void clear(BlockPos position) {
      this.sourceIndexByPosition.put(position.asLong(), CLEARED);
   }

   void place(BlockPos position, int sourceIndex) {
      if (sourceIndex < 0 || sourceIndex >= this.sources.size()) {
         throw new IllegalArgumentException("Selection source index is outside the captured source");
      }
      int currentSourceIndex = this.sourceIndexByPosition.get(position.asLong());
      ReversibleBlockSnapshot original = currentSourceIndex == UNCHANGED
         ? this.originalAt.apply(position)
         : null;
      if (currentSourceIndex == UNCHANGED && original == null) {
         throw new IllegalStateException("Missing validated operation target");
      }
      if (this.conflictMode == OperationConflictMode.KEEP_EXISTING
         && !currentState(currentSourceIndex, original).canBeReplaced()) {
         return;
      }
      this.sourceIndexByPosition.put(position.asLong(), sourceIndex);
   }

   Collection<ReversibleBlockSnapshot> snapshots(
      Collection<ReversibleBlockSnapshot> originals
   ) {
      return PlacementTask.lazyMappedCollection(originals, this::predictedSnapshot);
   }

   private BlockState currentState(
      int sourceIndex,
      ReversibleBlockSnapshot original
   ) {
      if (sourceIndex == CLEARED) {
         return Blocks.AIR.defaultBlockState();
      }
      if (sourceIndex >= 0) {
         return this.sources.get(sourceIndex).state();
      }
      return original.state();
   }

   private ReversibleBlockSnapshot predictedSnapshot(ReversibleBlockSnapshot original) {
      int sourceIndex = this.sourceIndexByPosition.get(original.pos().asLong());
      if (sourceIndex == UNCHANGED) {
         return original;
      }
      if (sourceIndex == CLEARED) {
         BlockState air = Blocks.AIR.defaultBlockState();
         return new ReversibleBlockSnapshot(original.pos(), air, air.getFluidState(), null);
      }
      ReversibleBlockSnapshot source = this.sources.get(sourceIndex);
      return new ReversibleBlockSnapshot(
         original.pos(), source.state(), source.fluidState(), source.blockEntity()
      );
   }
}
