package io.github.fastformer.fastplace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

class WorldChangeBatchTest {
   private static final ResourceKey<Level> DIMENSION = Level.OVERWORLD;

   @Test
   void denseBatchReconstructsPackedAabbPositions() {
      List<ReversibleBlockSnapshot> before = List.of(
         snapshot(new BlockPos(4, 10, -2), "before"),
         snapshot(new BlockPos(5, 10, -2), "before")
      );
      Map<BlockPos, ReversibleBlockSnapshot> after = new HashMap<>();
      after.put(new BlockPos(4, 10, -2), snapshot(new BlockPos(4, 10, -2), "after"));
      after.put(new BlockPos(5, 10, -2), snapshot(new BlockPos(5, 10, -2), "after"));

      WorldChangeBatch batch = WorldChangeBatch.fromPairsForTest(DIMENSION, before, after).orElseThrow();

      assertEquals(2, batch.size());
      assertEquals(new BlockPos(4, 10, -2), batch.position(0));
      assertEquals(new BlockPos(5, 10, -2), batch.position(1));
      assertEquals(DIMENSION, batch.dimension());
   }

   @Test
   void denseBatchKeepsThreeDimensionalOrdering() {
      List<ReversibleBlockSnapshot> before = new ArrayList<>();
      Map<BlockPos, ReversibleBlockSnapshot> after = new HashMap<>();
      for (int x = 0; x < 2; x++) {
         for (int y = 0; y < 2; y++) {
            for (int z = 0; z < 2; z++) {
               BlockPos pos = new BlockPos(x, y, z);
               before.add(snapshot(pos, "before"));
               after.put(pos, snapshot(pos, "after"));
            }
         }
      }

      WorldChangeBatch batch = WorldChangeBatch.fromPairsForTest(DIMENSION, before, after).orElseThrow();

      int index = 0;
      for (int x = 0; x < 2; x++) {
         for (int y = 0; y < 2; y++) {
            for (int z = 0; z < 2; z++) {
               assertEquals(new BlockPos(x, y, z), batch.position(index++));
            }
         }
      }
   }

   @Test
   void duplicateWritesKeepFirstBeforeAndFinalAfter() {
      BlockPos pos = new BlockPos(0, 64, 0);
      List<ReversibleBlockSnapshot> before = new ArrayList<>();
      before.add(snapshot(pos, "first"));
      before.add(snapshot(pos, "second"));
      Map<BlockPos, ReversibleBlockSnapshot> after = Map.of(
         pos, snapshot(pos, "final")
      );

      Optional<WorldChangeBatch> captured = WorldChangeBatch.fromPairsForTest(DIMENSION, before, after);

      assertTrue(captured.isPresent());
      assertEquals(1, captured.orElseThrow().size());
   }

   @Test
   void unchangedPairsAreNotRetained() {
      BlockPos pos = new BlockPos(1, 2, 3);
      ReversibleBlockSnapshot same = snapshot(pos, "same");

      assertTrue(WorldChangeBatch.fromPairsForTest(DIMENSION, List.of(same), Map.of(pos, same)).isEmpty());
   }

   @Test
   void mismatchedAfterPositionIsRejected() {
      BlockPos target = new BlockPos(0, 0, 0);
      BlockPos wrong = new BlockPos(1, 0, 0);
      ReversibleBlockSnapshot before = snapshot(target, "before");

      assertTrue(WorldChangeBatch.fromPairsForTest(
         DIMENSION,
         List.of(before),
         Map.of(target, snapshot(wrong, "after"))
      ).isEmpty());
   }

   @Test
   void persistentHistoryJournalUsesTheCurrentSourceSide() {
      BlockPos pos = new BlockPos(3, 4, 5);
      WorldChangeBatch batch = WorldChangeBatch.fromPairsForTest(
         DIMENSION,
         List.of(snapshot(pos, "before")),
         Map.of(pos, snapshot(pos, "after"))
      ).orElseThrow();

      assertEquals("after", batch.sourceSnapshots(true).getFirst().blockEntity().data().getString("marker"));
      assertEquals("before", batch.sourceSnapshots(false).getFirst().blockEntity().data().getString("marker"));
      assertEquals("before", batch.targetSnapshots(true).getFirst().blockEntity().data().getString("marker"));
      assertEquals("after", batch.targetSnapshots(false).getFirst().blockEntity().data().getString("marker"));
   }

   @Test
   void dequeCompressionKeepsBeforeFromTheFirstTaskOwnedMutation() {
      BlockPos pos = new BlockPos(8, 9, 10);
      ArrayDeque<ReversibleBlockSnapshot> changes = new ArrayDeque<>();
      changes.addFirst(snapshot(pos, "original"));
      changes.addFirst(snapshot(pos, "intermediate"));
      WorldChangeBatch batch = WorldChangeBatch.capturePairsByPos(
         DIMENSION,
         changes,
         Map.of(pos, snapshot(pos, "final"))
      ).orElseThrow();

      assertEquals(
         "original",
         batch.targetSnapshots(true).getFirst().blockEntity().data().getString("marker")
      );
   }

   private static ReversibleBlockSnapshot snapshot(BlockPos pos, String marker) {
      CompoundTag tag = null;
      if (marker != null) {
         tag = new CompoundTag();
         tag.putString("marker", marker);
      }
      return new ReversibleBlockSnapshot(pos, null, null, tag == null ? null : new BlockEntitySnapshot(tag));
   }
}
