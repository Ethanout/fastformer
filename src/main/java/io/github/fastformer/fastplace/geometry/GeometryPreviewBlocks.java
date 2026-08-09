package io.github.fastformer.fastplace.geometry;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;

public final class GeometryPreviewBlocks {
   private GeometryPreviewBlocks() {
   }

   public static Set<BlockPos> generatedOrControlPoints(List<BlockPos> points, long scanCells, int previewLimit, Supplier<Set<BlockPos>> blocks) {
      return generatedOrFallback(scanCells, previewLimit, blocks, () -> Set.copyOf(points));
   }

   public static Set<BlockPos> generatedOrFallback(
      long scanCells, int previewLimit, Supplier<Set<BlockPos>> blocks, Supplier<Set<BlockPos>> fallback
   ) {
      if (scanCells > previewLimit) {
         return fallback.get();
      }
      Set<BlockPos> generated = blocks.get();
      return generated.size() >= previewLimit ? fallback.get() : generated;
   }

   public static Layers layers(Set<BlockPos> confirmed, Set<BlockPos> preview) {
      HashSet<BlockPos> stable = new HashSet<>(confirmed);
      stable.retainAll(preview);
      HashSet<BlockPos> pending = new HashSet<>(preview);
      pending.removeAll(stable);
      return new Layers(stable, pending);
   }

   public static Layers layersPreservingConfirmed(Set<BlockPos> confirmed, Set<BlockPos> preview) {
      HashSet<BlockPos> stable = new HashSet<>(confirmed);
      HashSet<BlockPos> pending = new HashSet<>(preview);
      pending.removeAll(stable);
      return new Layers(stable, pending);
   }

   public record Layers(Set<BlockPos> confirmed, Set<BlockPos> pending) {
      public Layers {
         confirmed = confirmed == null ? Set.of() : Set.copyOf(confirmed);
         pending = pending == null ? Set.of() : Set.copyOf(pending);
      }

      public static Layers confirmed(Set<BlockPos> blocks) {
         return new Layers(blocks, Set.of());
      }
   }
}
