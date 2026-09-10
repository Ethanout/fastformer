package io.github.fastformer.fastplace.world;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import net.minecraft.core.BlockPos;

/** A position-keyed map that stores coordinates as packed primitive longs. */
final class PackedBlockSnapshotMap extends AbstractMap<BlockPos, ReversibleBlockSnapshot> {
   private final Long2ObjectLinkedOpenHashMap<ReversibleBlockSnapshot> snapshots =
      new Long2ObjectLinkedOpenHashMap<>();

   @Override
   public ReversibleBlockSnapshot put(BlockPos position, ReversibleBlockSnapshot snapshot) {
      requireMatchingPosition(position, snapshot);
      return this.snapshots.put(position.asLong(), snapshot);
   }

   @Override
   public ReversibleBlockSnapshot get(Object key) {
      return key instanceof BlockPos position ? this.snapshots.get(position.asLong()) : null;
   }

   @Override
   public boolean containsKey(Object key) {
      return key instanceof BlockPos position && this.snapshots.containsKey(position.asLong());
   }

   @Override
   public int size() {
      return this.snapshots.size();
   }

   @Override
   public boolean isEmpty() {
      return this.snapshots.isEmpty();
   }

   @Override
   public void clear() {
      this.snapshots.clear();
   }

   @Override
   public Collection<ReversibleBlockSnapshot> values() {
      return this.snapshots.values();
   }

   Iterator<BlockPos> positionIterator() {
      var positions = this.snapshots.keySet().iterator();
      return new Iterator<>() {
         @Override
         public boolean hasNext() {
            return positions.hasNext();
         }

         @Override
         public BlockPos next() {
            if (!hasNext()) {
               throw new NoSuchElementException();
            }
            return BlockPos.of(positions.nextLong());
         }

         @Override
         public void remove() {
            positions.remove();
         }
      };
   }

   @Override
   public Set<Map.Entry<BlockPos, ReversibleBlockSnapshot>> entrySet() {
      return new AbstractSet<>() {
         @Override
         public int size() {
            return PackedBlockSnapshotMap.this.snapshots.size();
         }

         @Override
         public Iterator<Map.Entry<BlockPos, ReversibleBlockSnapshot>> iterator() {
            Iterator<Long2ObjectMap.Entry<ReversibleBlockSnapshot>> entries =
               PackedBlockSnapshotMap.this.snapshots.long2ObjectEntrySet().iterator();
            return new Iterator<>() {
               @Override
               public boolean hasNext() {
                  return entries.hasNext();
               }

               @Override
               public Map.Entry<BlockPos, ReversibleBlockSnapshot> next() {
                  Long2ObjectMap.Entry<ReversibleBlockSnapshot> entry = entries.next();
                  return Map.entry(BlockPos.of(entry.getLongKey()), entry.getValue());
               }

               @Override
               public void remove() {
                  entries.remove();
               }
            };
         }
      };
   }

   private static void requireMatchingPosition(
      BlockPos position,
      ReversibleBlockSnapshot snapshot
   ) {
      if (position == null || snapshot == null || position.asLong() != snapshot.pos().asLong()) {
         throw new IllegalArgumentException("A packed snapshot must match its position");
      }
   }
}
