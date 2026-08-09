package io.github.fastformer.fastplace.geometry.generation;

import java.util.LinkedHashSet;
import net.minecraft.core.BlockPos;

/** Ordered final-output set that reports successful insertions without exposing temporary generator sets. */
final class ObservedBlockSet extends LinkedHashSet<BlockPos> {
   private final BlockGenerationObserver observer;

   ObservedBlockSet(BlockGenerationObserver observer) {
      this.observer = observer == null ? BlockGenerationObserver.NONE : observer;
   }

   @Override
   public boolean add(BlockPos position) {
      this.observer.checkCancelled();
      this.observer.onScanned(1L);
      BlockPos immutable = position.immutable();
      if (!super.add(immutable)) {
         return false;
      }
      this.observer.onGenerated(immutable);
      return true;
   }
}
