package io.github.fastformer.fastplace.geometry.generation;

import java.util.AbstractSet;
import java.util.Collections;
import java.util.Iterator;
import java.util.Set;
import net.minecraft.core.BlockPos;

/** Typed empty result for a geometry constraint failure that must not fall back silently. */
public final class GenerationFailed {
   private GenerationFailed() {
   }

   public static Set<BlockPos> faceConstraints() {
      return FailureSet.INSTANCE;
   }

   public static boolean is(Set<BlockPos> blocks) {
      return blocks instanceof FailureSet;
   }

   private static final class FailureSet extends AbstractSet<BlockPos> {
      private static final FailureSet INSTANCE = new FailureSet();

      @Override
      public Iterator<BlockPos> iterator() {
         return Collections.emptyIterator();
      }

      @Override
      public int size() {
         return 0;
      }
   }
}
