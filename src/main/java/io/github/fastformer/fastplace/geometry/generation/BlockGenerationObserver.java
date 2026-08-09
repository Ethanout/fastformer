package io.github.fastformer.fastplace.geometry.generation;

import net.minecraft.core.BlockPos;

/** Observes only positions accepted into a generator's final target set. */
public interface BlockGenerationObserver {
   BlockGenerationObserver NONE = new BlockGenerationObserver() {
   };

   default void onScanned(long amount) {
   }

   default void onGenerated(BlockPos position) {
   }

   default void checkCancelled() {
   }
}
