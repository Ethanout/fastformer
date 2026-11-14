package io.github.fastformer.server.history.memory;

import io.github.fastformer.server.history.entry.HistoryBatchComposition;
import io.github.fastformer.server.history.entry.WorldChangeBatch;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import io.github.fastformer.server.worldwrite.snapshot.BlockEntitySnapshot;
import io.github.fastformer.server.worldwrite.snapshot.ReversibleBlockSnapshot;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BlockEditHistoryGestureTest {
   @Test void replayAndInterveningEditBreakGestureIdentity() {
      var history = new HistoryMemoryCache<WorldChangeBatch>();
      var gesture = new BlockEditHistoryGesture();
      assertFalse(gesture.canExtend(history));
      history.addNew(edit("a", "b"));
      gesture.recorded(history, true);
      assertTrue(gesture.canExtend(history));
      history.commit(true);
      history.commit(false);
      assertFalse(gesture.canExtend(history));
      gesture.recorded(history, true);
      history.addNew(edit("b", "c"));
      assertFalse(gesture.canExtend(history));
      gesture.recorded(history, false);
      assertFalse(gesture.canExtend(history));
   }

   @Test void compositionRetainsOriginalAndFinalStatesAndDistinguishesNoOpFromDiscontinuity() {
      var a = edit("a", "b");
      var b = edit("b", "c");
      var combined = HistoryBatchComposition.compose(List.of(b, a)).orElseThrow().batch().orElseThrow();
      assertTrue(combined.targetSnapshots(true).getFirst().sameContents(snapshot("a")));
      assertTrue(combined.sourceSnapshots(true).getFirst().sameContents(snapshot("c")));
      assertTrue(HistoryBatchComposition.compose(List.of(edit("b", "a"), a)).orElseThrow().batch().isEmpty());
      assertTrue(HistoryBatchComposition.compose(List.of(edit("external", "c"), a)).isEmpty());
   }

   private static WorldChangeBatch edit(String before, String after) {
      return WorldChangeBatch.capturePairsByPos(Level.OVERWORLD, List.of(snapshot(before)), Map.of(BlockPos.ZERO, snapshot(after)))
         .orElseThrow().withOperationId(UUID.randomUUID());
   }

   private static ReversibleBlockSnapshot snapshot(String value) {
      var tag = new CompoundTag();
      tag.putString("value", value);
      return new ReversibleBlockSnapshot(BlockPos.ZERO, null, null, new BlockEntitySnapshot(tag));
   }
}
