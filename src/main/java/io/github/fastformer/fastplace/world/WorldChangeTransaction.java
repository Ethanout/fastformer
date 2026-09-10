package io.github.fastformer.fastplace.world;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * Owns the mutable before/after staging and asynchronous commit for one world
 * operation. Target generation and task phase transitions remain task-owned.
 */
public final class WorldChangeTransaction {
   private final Long2ObjectLinkedOpenHashMap<ReversibleBlockSnapshot> expected = new Long2ObjectLinkedOpenHashMap<>();
   private ArrayDeque<ReversibleBlockSnapshot> before = new ArrayDeque<>();
   private PackedBlockSnapshotMap after = new PackedBlockSnapshotMap();
   private WorldOperationCommit commit;
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

   public JournalPreparation prepareCommit(
      ResourceKey<Level> dimension,
      PersistentRecoveryJournal journal,
      BooleanSupplier reserveMemory
   ) {
      if (this.commit == null) {
         if (reserveMemory == null || !reserveMemory.getAsBoolean()) {
            return JournalPreparation.PENDING;
         }
         this.commit = WorldOperationCommit.begin(dimension, this.before, this.after, journal);
      }
      return this.commit.poll();
   }

   public String commitFailureReason() {
      return this.commit == null ? "commit preparation did not start" : this.commit.failureReason();
   }

   public boolean commitStarted() {
      return this.commit != null;
   }

   public Optional<WorldChangeBatch> preparedBatch(UUID operationId) {
      return this.commit == null
         ? Optional.empty()
         : this.commit.batch().map(batch -> batch.withOperationId(operationId));
   }

   public void cancelCommit() {
      if (this.commit != null) {
         this.commit.cancel();
      }
   }

   Collection<ReversibleBlockSnapshot> beforeView() {
      return Collections.unmodifiableCollection(this.before);
   }

   Map<BlockPos, ReversibleBlockSnapshot> afterView() {
      return Collections.unmodifiableMap(this.after);
   }

   /**
    * Atomically transfers both recovery sides out of this transaction. A
    * second call returns only the transaction's new empty containers.
    */
   public WorldRecoverySnapshot transferRecoverySnapshot() {
      CompletableFuture<Void> ready = this.commit == null
         ? CompletableFuture.completedFuture(null)
         : this.commit.stopForRecovery();
      WorldRecoverySnapshot transferred = new WorldRecoverySnapshot(this.before, this.after, ready);
      this.before = new ArrayDeque<>();
      this.after = new PackedBlockSnapshotMap();
      this.commit = null;
      this.commitBlockEntityReserve = -1L;
      return transferred;
   }

   public void releaseCommitted() {
      this.expected.clear();
      this.before.clear();
      this.after.clear();
      this.commit = null;
      this.commitBlockEntityReserve = -1L;
   }
}
