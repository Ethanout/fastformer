package io.github.fastformer.server.task.runtime;

import io.github.fastformer.server.recovery.PersistentRecoveryJournal;
import io.github.fastformer.fastplace.world.WorldChangeTransaction;
import io.github.fastformer.fastplace.world.snapshot.ReversibleBlockSnapshot;
import java.util.List;

/** Keeps one immutable in-flight journal slice until its durable append is acknowledged. */
public final class JournalSegments {
   private int count;
   private List<ReversibleBlockSnapshot> pending = List.of();

   public int count() { return count; }
   public List<ReversibleBlockSnapshot> pending() { return pending; }

   /** Repeated calls return the same slice even when the transaction grows during I/O. */
   public List<ReversibleBlockSnapshot> prepare(WorldChangeTransaction transaction) {
      if (!pending.isEmpty()) return pending;
      int remaining = transaction.expectedCount() - count;
      if (remaining <= 0) return pending;
      int size = PersistentRecoveryJournal.segmentBatchSize(count, remaining);
      pending = transaction.expectedRange(count, count + size);
      return pending;
   }

   /** Only the currently staged slice can advance the journal cursor. */
   public void acknowledge(List<ReversibleBlockSnapshot> slice) {
      if (slice != pending || slice.isEmpty()) throw new IllegalStateException("journal slice is not in flight");
      count = Math.addExact(count, slice.size());
      pending = List.of();
   }

   public void clearPending() { pending = List.of(); }
}
