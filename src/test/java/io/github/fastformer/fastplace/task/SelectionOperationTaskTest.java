package io.github.fastformer.fastplace.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.fastformer.fastplace.OperationConflictMode;
import io.github.fastformer.fastplace.world.BlockEntitySnapshot;
import io.github.fastformer.fastplace.world.ReversibleBlockSnapshot;
import java.util.Collection;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

class SelectionOperationTaskTest {
   @Test
   void predictedJournalViewReusesOriginalsWithoutOverrides() {
      ReversibleBlockSnapshot unchanged = snapshot(BlockPos.ZERO);
      ReversibleBlockSnapshot original = snapshot(BlockPos.ZERO.above());
      ReversibleBlockSnapshot source = markedSnapshot(new BlockPos(10, 0, 0), "source");
      SelectionJournalPrediction prediction = new SelectionJournalPrediction(
         List.of(source),
         position -> position.equals(unchanged.pos()) ? unchanged : original,
         OperationConflictMode.REPLACE
      );
      prediction.place(original.pos(), 0);
      Collection<ReversibleBlockSnapshot> view = prediction.snapshots(List.of(unchanged, original));

      List<ReversibleBlockSnapshot> snapshots = List.copyOf(view);

      assertEquals(2, view.size());
      assertSame(unchanged, snapshots.get(0));
      assertSame(source.blockEntity(), snapshots.get(1).blockEntity());
      assertEquals(original.pos(), snapshots.get(1).pos());
   }

   @Test
   void laterPlacementReplacesAPlannedSourceClear() {
      BlockPos target = BlockPos.ZERO;
      ReversibleBlockSnapshot original = snapshot(target);
      ReversibleBlockSnapshot source = markedSnapshot(target.above(), "source");
      SelectionJournalPrediction prediction = new SelectionJournalPrediction(
         List.of(source), ignored -> original, OperationConflictMode.REPLACE
      );

      prediction.clear(target);
      prediction.place(target, 0);

      ReversibleBlockSnapshot predicted = prediction.snapshots(List.of(original)).iterator().next();
      assertSame(source.blockEntity(), predicted.blockEntity());
      assertEquals(target, predicted.pos());
   }

   @Test
   void replacementPredictionRejectsAnUnvalidatedTarget() {
      ReversibleBlockSnapshot source = markedSnapshot(BlockPos.ZERO, "source");
      SelectionJournalPrediction prediction = new SelectionJournalPrediction(
         List.of(source), ignored -> null, OperationConflictMode.REPLACE
      );

      assertThrows(
         IllegalStateException.class,
         () -> prediction.place(BlockPos.ZERO.above(), 0)
      );
   }

   private static ReversibleBlockSnapshot snapshot(BlockPos pos) {
      return new ReversibleBlockSnapshot(pos, null, null, null);
   }

   private static ReversibleBlockSnapshot markedSnapshot(BlockPos pos, String marker) {
      CompoundTag data = new CompoundTag();
      data.putString("marker", marker);
      return new ReversibleBlockSnapshot(pos, null, null, new BlockEntitySnapshot(data));
   }
}
