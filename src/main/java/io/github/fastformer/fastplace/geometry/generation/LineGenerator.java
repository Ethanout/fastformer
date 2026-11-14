package io.github.fastformer.fastplace.geometry.generation;
import io.github.fastformer.common.geometry.raster.BlockGenerationObserver;
import io.github.fastformer.common.geometry.raster.line.LinePath;

import io.github.fastformer.common.geometry.LineTieBias;

import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

public final class LineGenerator {
   private LineGenerator() {
   }

   public static Set<BlockPos> generate(BlockPos from, BlockPos to, int maxBlocks) {
      return generate(from, to, maxBlocks, BlockGenerationObserver.NONE);
   }

   public static Set<BlockPos> generate(BlockPos from, BlockPos to, int maxBlocks, BlockGenerationObserver observer) {
      return generate(from, to, maxBlocks, observer, LineTieBias.DEFAULT);
   }

   public static Set<BlockPos> generate(
      BlockPos from,
      BlockPos to,
      int maxBlocks,
      BlockGenerationObserver observer,
      LineTieBias tieBias
   ) {
      if (maxBlocks > 0 && estimateBlocks(from, to) > maxBlocks) {
         return GenerationLimitExceeded.witness(maxBlocks, observer);
      }
      if (maxBlocks <= 0) {
         return Set.of();
      }
      return new LazyLineBlockSet(from, to, tieBias, observer);
   }

   public static BlockPositionSource generateSource(
      BlockPos from, BlockPos to, int maxBlocks, BlockGenerationObserver observer, LineTieBias tieBias
   ) {
      if (maxBlocks > 0 && estimateBlocks(from, to) > maxBlocks) {
         return new SetPositionSource(GenerationLimitExceeded.witness(maxBlocks, observer));
      }
      if (maxBlocks <= 0) {
         return new SetPositionSource(Set.of());
      }
      return new SetPositionSource(new LazyLineBlockSet(from, to, tieBias, observer));
   }

   private record SetPositionSource(Set<BlockPos> blocks) implements BlockPositionSource {
      @Override
      public int size() {
         return this.blocks.size();
      }

      @Override
      public java.util.Iterator<BlockPos> iterator() {
         return this.blocks.iterator();
      }

      @Override
      public java.util.Iterator<BlockPos> drainingIterator() {
         return this.blocks instanceof DrainingBlockSet draining ? draining.drainingIterator() : iterator();
      }

      @Override
      public boolean supportsDraining() {
         return this.blocks instanceof DrainingBlockSet;
      }
   }

   public static List<BlockPos> offsets(Vec3 delta, int maxBlocks) { return LinePath.offsets(delta, maxBlocks); }
   public static List<BlockPos> offsets(Vec3 delta, int maxBlocks, LineTieBias tieBias) { return LinePath.offsets(delta, maxBlocks, tieBias); }
   public static long estimateBlocks(BlockPos from, BlockPos to) { return LinePath.estimateBlocks(from, to); }
   public static void add(Set<BlockPos> output, Vec3 from, Vec3 to, int maxBlocks) { LinePath.add(output, from, to, maxBlocks); }
   public static void add(Set<BlockPos> output, Vec3 from, Vec3 to, int maxBlocks, LineTieBias tieBias) { LinePath.add(output, from, to, maxBlocks, tieBias); }
   public static void add(Set<BlockPos> output, BlockPos from, BlockPos to, int maxBlocks) { LinePath.add(output, from, to, maxBlocks); }
   public static void add(Set<BlockPos> output, BlockPos from, BlockPos to, int maxBlocks, LineTieBias tieBias) { LinePath.add(output, from, to, maxBlocks, tieBias); }
   public static List<BlockPos> path(BlockPos from, BlockPos to) { return LinePath.path(from, to); }
   public static List<BlockPos> path(BlockPos from, BlockPos to, LineTieBias tieBias) { return LinePath.path(from, to, tieBias); }
   static int[] axesByDescendingSlope(BlockPos from, BlockPos to) { return LinePath.axesByDescendingSlope(from, to); }
   static int[] axesByDescendingSlope(long[] distance) { return LinePath.axesByDescendingSlope(distance); }
   static BlockPos orderedStart(BlockPos first, BlockPos second) { return LinePath.orderedStart(first, second); }
}
