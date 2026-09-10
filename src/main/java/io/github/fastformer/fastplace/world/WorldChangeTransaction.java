package io.github.fastformer.fastplace.world;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;

/**
 * Owns conflict checks and mutable before/after staging for one world write.
 * History publication and task phase transitions remain task-owned.
 */
public final class WorldChangeTransaction {
   private final Long2ObjectLinkedOpenHashMap<ReversibleBlockSnapshot> expected = new Long2ObjectLinkedOpenHashMap<>();
   private ArrayDeque<ReversibleBlockSnapshot> before = new ArrayDeque<>();
   private PackedBlockSnapshotMap after = new PackedBlockSnapshotMap();
   private long commitBlockEntityReserve = -1L;

   public void recordExpected(BlockPos position, ReversibleBlockSnapshot snapshot) {
      if (position == null || snapshot == null || position.asLong() != snapshot.pos().asLong()) {
         throw new IllegalArgumentException("A transaction expected snapshot must match its position");
      }
      this.expected.put(position.asLong(), snapshot);
   }

   public ReversibleBlockSnapshot expectedAt(BlockPos position) {
      return position == null ? null : this.expected.get(position.asLong());
   }

   public ReversibleBlockSnapshot recordExpectedIfAbsent(BlockPos position, ReversibleBlockSnapshot snapshot) {
      if (position == null || snapshot == null || position.asLong() != snapshot.pos().asLong()) {
         throw new IllegalArgumentException("A transaction expected snapshot must match its position");
      }
      return this.expected.putIfAbsent(position.asLong(), snapshot);
   }

   public boolean expects(BlockPos position) {
      return position != null && this.expected.containsKey(position.asLong());
   }

   public int expectedCount() {
      return this.expected.size();
   }

   public Collection<ReversibleBlockSnapshot> expectedView() {
      return Collections.unmodifiableCollection(this.expected.values());
   }

   public List<ReversibleBlockSnapshot> expectedRange(int fromInclusive, int toExclusive) {
      if (fromInclusive < 0 || toExclusive < fromInclusive || toExclusive > this.expected.size()) {
         throw new IllegalArgumentException("Invalid expected snapshot range");
      }
      List<ReversibleBlockSnapshot> range = new ArrayList<>(toExclusive - fromInclusive);
      int index = 0;
      for (ReversibleBlockSnapshot snapshot : this.expected.values()) {
         if (index >= toExclusive) {
            break;
         }
         if (index >= fromInclusive) {
            range.add(snapshot);
         }
         index++;
      }
      return List.copyOf(range);
   }

   /**
    * Returns the insertion order already owned by the expected snapshot map.
    * Callers must finish consuming it before clearing expected snapshots.
    */
   public Iterator<BlockPos> expectedPositions() {
      Iterator<Long> positions = this.expected.keySet().iterator();
      return new Iterator<>() {
         @Override
         public boolean hasNext() {
            return positions.hasNext();
         }

         @Override
         public BlockPos next() {
            return BlockPos.of(positions.next());
         }
      };
   }

   public void clearExpected() {
      this.expected.clear();
   }

   public void recordBefore(ReversibleBlockSnapshot snapshot) {
      if (snapshot == null) {
         throw new IllegalArgumentException("A transaction before snapshot cannot be null");
      }
      this.before.addFirst(snapshot);
      this.commitBlockEntityReserve = -1L;
   }

   public void recordAfter(BlockPos position, ReversibleBlockSnapshot snapshot) {
      if (position == null || snapshot == null || position.asLong() != snapshot.pos().asLong()) {
         throw new IllegalArgumentException("A transaction after snapshot must match its position");
      }
      this.after.put(position, snapshot);
      this.commitBlockEntityReserve = -1L;
   }

   public ReversibleBlockSnapshot afterAt(BlockPos position) {
      return this.after.get(position);
   }

   public Iterator<BlockPos> afterPositions() {
      return this.after.positionIterator();
   }

   public boolean hasWrites() {
      return !this.before.isEmpty();
   }

   public int beforeCount() {
      return this.before.size();
   }

   public int afterCount() {
      return this.after.size();
   }

   public long snapshotCount() {
      return (long)this.before.size() + this.after.size();
   }

   public int largestSideCount() {
      return Math.max(this.before.size(), this.after.size());
   }

   public long commitBlockEntityReserve() {
      if (this.commitBlockEntityReserve < 0L) {
         this.commitBlockEntityReserve = WorldOperationMemory.saturatingAdd(
            WorldOperationMemory.snapshotNbtReserve(this.before),
            WorldOperationMemory.snapshotNbtReserve(this.after.values())
         );
      }
      return this.commitBlockEntityReserve;
   }

   Collection<ReversibleBlockSnapshot> beforeView() {
      return Collections.unmodifiableCollection(this.before);
   }

   Map<BlockPos, ReversibleBlockSnapshot> afterView() {
      return Collections.unmodifiableMap(this.after);
   }

   /**
    * Atomically transfers both recovery sides out of this transaction. The
    * supplied signal prevents recovery from reading them during publication.
    * A second call returns only the transaction's new empty containers.
    */
   public WorldRecoverySnapshot transferRecoverySnapshot(CompletableFuture<Void> ready) {
      CompletableFuture<Void> recoveryReady = ready == null ? CompletableFuture.completedFuture(null) : ready;
      WorldRecoverySnapshot transferred = new WorldRecoverySnapshot(this.before, this.after, recoveryReady);
      this.before = new ArrayDeque<>();
      this.after = new PackedBlockSnapshotMap();
      this.commitBlockEntityReserve = -1L;
      return transferred;
   }

   public void releaseWriteState() {
      this.expected.clear();
      this.before.clear();
      this.after.clear();
      this.commitBlockEntityReserve = -1L;
   }
}
