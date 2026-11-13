package io.github.fastformer.server.history.replay;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.server.history.entry.WorldChangeBatch;
import io.github.fastformer.testkit.CounterHistory;
import org.junit.jupiter.api.Test;

class EntryReplayRegistryTest {
   private final CounterHistory counter = new CounterHistory();

   @Test
   void createsIndependentStateForEachReplayDirection() {
      var entry = counter.entry(1, 2);
      var registry = counter.replays();
      var undo = registry.open(entry, true);
      var redo = registry.open(entry, false);
      counter.value.set(2);
      assertEquals(1, undo.match(null, 0));
      assertEquals(2, redo.match(null, 0));
      assertNotSame(undo, registry.open(entry, true));
      counter.value.set(8);
      undo.captureConflict(null, 0, true);
      assertEquals(2, undo.match(null, 0));
      assertEquals(0, redo.match(null, 0));
   }

   @Test
   void rejectsUnknownMismatchedDuplicateAndLateRegistrations() {
      var entry = counter.entry(0, 1);
      assertThrows(IllegalArgumentException.class, () -> EntryReplayRegistry.standard().open(entry, true));
      var wrong = EntryReplayRegistry.create(registry -> registry.register(CounterHistory.TYPE,
         WorldChangeBatch.class, (batch, undo) -> { throw new AssertionError("Wrong factory was called"); }));
      assertThrows(IllegalArgumentException.class, () -> wrong.open(entry, true));
      assertThrows(IllegalArgumentException.class, () -> EntryReplayRegistry.create(registry -> {
         registry.register(CounterHistory.TYPE, CounterHistory.CounterEntry.class, (batch, undo) -> null);
         registry.register(CounterHistory.TYPE, CounterHistory.CounterEntry.class, (batch, undo) -> null);
      }));
      assertThrows(IllegalStateException.class, () -> counter.replays().register(CounterHistory.TYPE,
         CounterHistory.CounterEntry.class, (batch, undo) -> null));
   }
}
