package io.github.fastformer.fastplace.geometry.generation;

import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import net.minecraft.core.BlockPos;

/** Ordered packed-position set that reports successful insertions to a generation observer. */
final class ObservedBlockSet extends AbstractSet<BlockPos> implements DrainingBlockSet {
   private final BlockGenerationObserver observer;
   private final LongLinkedOpenHashSet positions = new LongLinkedOpenHashSet();

   ObservedBlockSet(BlockGenerationObserver observer) {
      this.observer = observer == null ? BlockGenerationObserver.NONE : observer;
   }

   @Override
   public boolean add(BlockPos position) {
      this.observer.checkCancelled();
      this.observer.onScanned(1L);
      BlockPos accepted = Objects.requireNonNull(position, "position").immutable();
      if (!this.positions.add(accepted.asLong())) {
         return false;
      }
      this.observer.onGenerated(accepted);
      return true;
   }

   @Override
   public boolean contains(Object candidate) {
      return candidate instanceof BlockPos position && this.positions.contains(position.asLong());
   }

   @Override
   public boolean remove(Object candidate) {
      return candidate instanceof BlockPos position && this.positions.remove(position.asLong());
   }

   @Override
   public Iterator<BlockPos> iterator() {
      return iterator(false);
   }

   @Override
   public Iterator<BlockPos> drainingIterator() {
      return iterator(true);
   }

   private Iterator<BlockPos> iterator(boolean drainOnRead) {
      LongIterator iterator = this.positions.iterator();
      return new Iterator<>() {
         @Override
         public boolean hasNext() {
            return iterator.hasNext();
         }

         @Override
         public BlockPos next() {
            if (!hasNext()) {
               throw new NoSuchElementException();
            }
            BlockPos position = BlockPos.of(iterator.nextLong());
            if (drainOnRead) {
               iterator.remove();
            }
            return position;
         }

         @Override
         public void remove() {
            iterator.remove();
         }
      };
   }

   @Override
   public int size() {
      return this.positions.size();
   }

   @Override
   public void clear() {
      this.positions.clear();
   }
}
