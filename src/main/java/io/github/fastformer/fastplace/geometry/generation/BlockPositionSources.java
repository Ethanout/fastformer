package io.github.fastformer.fastplace.geometry.generation;

import java.util.Iterator;
import java.util.AbstractSet;
import java.util.Set;
import net.minecraft.core.BlockPos;

/** Adapters between legacy generated sets and the position-source boundary. */
final class BlockPositionSources {
   private BlockPositionSources() {
   }

   static BlockPositionSource from(Set<BlockPos> positions) {
      return new SetPositionSource(positions);
   }

   static Set<BlockPos> asSet(BlockPositionSource source) {
      if (source == null || source.size() == 0) {
         return Set.of();
      }
      return source.supportsDraining() ? new DrainingSourceSet(source) : new SourceSet(source);
   }

   private static class SourceSet extends AbstractSet<BlockPos> {
      protected final BlockPositionSource source;

      private SourceSet(BlockPositionSource source) {
         this.source = source;
      }

         @Override
         public Iterator<BlockPos> iterator() {
            return this.source.iterator();
         }

         @Override
         public int size() {
            return this.source.size();
         }
   }

   private static final class DrainingSourceSet extends SourceSet implements DrainingBlockSet {
      private DrainingSourceSet(BlockPositionSource source) {
         super(source);
      }

      @Override
      public Iterator<BlockPos> drainingIterator() {
         return this.source.drainingIterator();
      }
   }

   private static final class SetPositionSource implements BlockPositionSource {
      private final Set<BlockPos> positions;
      private final DrainingBlockSet draining;

      private SetPositionSource(Set<BlockPos> positions) {
         this.positions = positions;
         this.draining = positions instanceof DrainingBlockSet value ? value : null;
      }

      @Override
      public int size() {
         return this.positions.size();
      }

      @Override
      public Iterator<BlockPos> iterator() {
         return this.positions.iterator();
      }

      @Override
      public Iterator<BlockPos> drainingIterator() {
         return this.draining == null ? iterator() : this.draining.drainingIterator();
      }

      @Override
      public boolean supportsDraining() {
         return this.draining != null;
      }
   }
}
