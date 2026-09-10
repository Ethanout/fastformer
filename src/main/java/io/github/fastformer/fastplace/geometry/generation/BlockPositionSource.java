package io.github.fastformer.fastplace.geometry.generation;

import java.util.Iterator;
import net.minecraft.core.BlockPos;

/**
 * Read boundary for generated positions.
 *
 * <p>The current adapter is backed by a set, but placement code only needs
 * ordered iteration and an optional draining iterator. A future producer can
 * implement this boundary without exposing its storage representation.</p>
 */
public interface BlockPositionSource {
   int size();

   Iterator<BlockPos> iterator();

   default Iterator<BlockPos> drainingIterator() {
      return iterator();
   }

   default boolean supportsDraining() {
      return false;
   }
}
