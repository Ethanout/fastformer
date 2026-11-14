package io.github.fastformer.server.task.runtime;

import io.github.fastformer.server.recovery.PersistentRecoveryJournal;
import io.github.fastformer.server.worldwrite.transaction.WorldChangeTransaction;
import io.github.fastformer.server.worldwrite.snapshot.ReversibleBlockSnapshot;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JournalSegmentsTest {
   @Test void growthDoesNotChangeAnInFlightSlice() {
      var transaction = new WorldChangeTransaction();
      var segments = new JournalSegments();
      record(transaction, 0);
      var first = segments.prepare(transaction);
      record(transaction, 1);
      assertSame(first, segments.prepare(transaction));
      assertEquals(1, first.size());
      assertEquals(0, segments.count());
      segments.acknowledge(first);
      assertEquals(1, segments.count());
      assertEquals(new BlockPos(1, 0, 0), segments.prepare(transaction).getFirst().pos());
   }

   @Test void staleOrCopiedAcknowledgementsCannotAdvanceTheCursor() {
      var transaction = new WorldChangeTransaction();
      record(transaction, 0);
      var segments = new JournalSegments();
      var slice = segments.prepare(transaction);
      assertThrows(IllegalStateException.class, () -> segments.acknowledge(new ArrayList<>(slice)));
      segments.acknowledge(slice);
      assertThrows(IllegalStateException.class, () -> segments.acknowledge(slice));
      assertThrows(IllegalStateException.class, () -> segments.acknowledge(List.of()));
      assertEquals(1, segments.count());
      assertTrue(segments.prepare(transaction).isEmpty());
   }

   @Test void segmentsRetainInsertionOrderAcrossTheCapacityBoundary() {
      var transaction = new WorldChangeTransaction();
      int size = PersistentRecoveryJournal.segmentCapacity(0) + 3;
      for (int index = 0; index < size; index++) record(transaction, index);
      var segments = new JournalSegments();
      int cursor = 0;
      while (cursor < size) {
         var slice = segments.prepare(transaction);
         assertTrue(slice.size() <= PersistentRecoveryJournal.segmentCapacity(cursor));
         for (var snapshot : slice) assertEquals(cursor++, snapshot.pos().getX());
         segments.acknowledge(slice);
      }
      assertEquals(size, segments.count());
      assertTrue(segments.pending().isEmpty());
   }

   private static void record(WorldChangeTransaction transaction, int x) {
      var position = new BlockPos(x, 0, 0);
      // Segment ownership only reads position and identity; no game registries are needed.
      transaction.recordExpected(position, new ReversibleBlockSnapshot(position, null, null, null));
   }
}
