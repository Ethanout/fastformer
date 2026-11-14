package io.github.fastformer.server.task.block;

import io.github.fastformer.common.workspace.model.OperationConflictMode;
import io.github.fastformer.server.worldwrite.snapshot.BlockEntitySnapshot;
import io.github.fastformer.server.worldwrite.snapshot.ReversibleBlockSnapshot;
import java.util.AbstractCollection;
import java.util.Collection;
import java.util.Iterator;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Lazily predicts journal after snapshots without allocating a second full-size snapshot list. */
final class PlacementJournalPrediction {
   private PlacementJournalPrediction() {}

   static Collection<ReversibleBlockSnapshot> after(RegistryAccess registries,
      Collection<ReversibleBlockSnapshot> before, Function<BlockPos, BlockState> placedState,
      OperationConflictMode conflicts) {
      return lazyMap(before, snapshot -> predictedSnapshot(registries, snapshot, placedState.apply(snapshot.pos()), conflicts));
   }

   static <T, R> Collection<R> lazyMap(Collection<T> source, Function<T, R> mapper) {
      return new AbstractCollection<>() {
         @Override public Iterator<R> iterator() {
            Iterator<T> positions = source.iterator();
            return new Iterator<>() {
               @Override public boolean hasNext() { return positions.hasNext(); }
               @Override public R next() { return mapper.apply(positions.next()); }
            };
         }
         @Override public int size() { return source.size(); }
      };
   }

   private static ReversibleBlockSnapshot predictedSnapshot(RegistryAccess registries,
      ReversibleBlockSnapshot before, BlockState placedState, OperationConflictMode conflicts) {
      if (before.state().equals(placedState)
         || conflicts == OperationConflictMode.KEEP_EXISTING && !before.state().canBeReplaced()) return before;
      BlockEntitySnapshot entity = predictedBlockEntity(registries, before, placedState);
      return new ReversibleBlockSnapshot(before.pos(), placedState, placedState.getFluidState(), entity);
   }

   private static BlockEntitySnapshot predictedBlockEntity(RegistryAccess registries,
      ReversibleBlockSnapshot before, BlockState placedState) {
      if (!(placedState.getBlock() instanceof EntityBlock factory)) return null;
      if (before.state().getBlock() == placedState.getBlock() && before.blockEntity() != null) return before.blockEntity();
      BlockEntity entity = factory.newBlockEntity(before.pos(), placedState);
      return entity == null ? null : new BlockEntitySnapshot(entity.saveWithFullMetadata(registries));
   }
}
