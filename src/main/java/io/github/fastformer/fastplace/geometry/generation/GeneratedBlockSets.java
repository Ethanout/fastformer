package io.github.fastformer.fastplace.geometry.generation;

import java.util.AbstractSet;
import java.util.Collections;
import java.util.Iterator;
import java.util.Set;
import net.minecraft.core.BlockPos;

/** Read-only views that preserve task-owned draining for packed generated positions. */
public final class GeneratedBlockSets {
   private GeneratedBlockSets() {
   }

   public static Set<BlockPos> readOnly(Set<BlockPos> positions) {
      if (positions == null || positions.isEmpty()) {
         return Set.of();
      }
      return positions instanceof DrainingBlockSet draining
         ? new ReadOnlyDrainingSet(positions, draining)
         : Collections.unmodifiableSet(positions);
   }

   private static final class ReadOnlyDrainingSet extends AbstractSet<BlockPos> implements DrainingBlockSet {
      private final Set<BlockPos> delegate;
      private final DrainingBlockSet draining;

      private ReadOnlyDrainingSet(Set<BlockPos> delegate, DrainingBlockSet draining) {
         this.delegate = delegate;
         this.draining = draining;
      }

      @Override
      public Iterator<BlockPos> iterator() {
         return Collections.unmodifiableSet(this.delegate).iterator();
      }

      @Override
      public Iterator<BlockPos> drainingIterator() {
         return this.draining.drainingIterator();
      }

      @Override
      public int size() {
         return this.delegate.size();
      }

      @Override
      public boolean contains(Object candidate) {
         return this.delegate.contains(candidate);
      }
   }
}
