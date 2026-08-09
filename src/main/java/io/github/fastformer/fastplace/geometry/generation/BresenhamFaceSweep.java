package io.github.fastformer.fastplace.geometry.generation;

import java.util.Collections;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Builds one canonical digital plane constrained by four exact Bresenham edges. */
final class BresenhamFaceSweep {
   private BresenhamFaceSweep() {
   }

   static Result generate(ProjectedBresenhamFace.Frame frame) {
      return generate(frame, LineTieBias.DEFAULT);
   }

   static Result generate(ProjectedBresenhamFace.Frame frame, LineTieBias tieBias) {
      return generate(frame, tieBias, BlockGenerationObserver.NONE);
   }

   static Result generate(
      ProjectedBresenhamFace.Frame frame,
      LineTieBias tieBias,
      BlockGenerationObserver observer
   ) {
      return attempt(frame, tieBias, Integer.MAX_VALUE, observer).result();
   }

   static Result generate(
      ProjectedBresenhamFace.Frame frame,
      LineTieBias tieBias,
      int maxBlocks,
      BlockGenerationObserver observer
   ) {
      return attempt(frame, tieBias, maxBlocks, observer).result();
   }

   static Attempt attempt(
      ProjectedBresenhamFace.Frame frame,
      LineTieBias tieBias,
      int maxBlocks,
      BlockGenerationObserver observer
   ) {
      return TranslatedScanFaceRasterizer.attempt(frame, tieBias, maxBlocks, observer);
   }

   static Attempt thinAttempt(
      ProjectedBresenhamFace.Frame frame,
      LineTieBias tieBias,
      int maxBlocks,
      BlockGenerationObserver observer
   ) {
      return TranslatedScanFaceRasterizer.thinAttempt(frame, tieBias, maxBlocks, observer);
   }

   static Attempt emergencyFallback(
      ProjectedBresenhamFace.Frame frame,
      LineTieBias tieBias,
      int maxBlocks,
      BlockGenerationObserver observer
   ) {
      return TranslatedScanFaceRasterizer.emergencyFallback(frame, tieBias, maxBlocks, observer);
   }

   private static Pixel project(ProjectedBresenhamFace.Frame frame, BlockPos position) {
      long[] delta = {
         (long)position.getX() - frame.anchor().getX(),
         (long)position.getY() - frame.anchor().getY(),
         (long)position.getZ() - frame.anchor().getZ()
      };
      int[] order = frame.order();
      int[] signs = frame.signs();
      return new Pixel(
         safeInt(delta[order[0]] * signs[0]),
         safeInt(delta[order[1]] * signs[1])
      );
   }

   private static boolean isParallelogram(List<BlockPos> vertices) {
      BlockPos first = vertices.getFirst();
      BlockPos second = vertices.get(1);
      BlockPos third = vertices.get(2);
      BlockPos fourth = vertices.get(3);
      return (long)first.getX() + third.getX() == (long)second.getX() + fourth.getX()
         && (long)first.getY() + third.getY() == (long)second.getY() + fourth.getY()
         && (long)first.getZ() + third.getZ() == (long)second.getZ() + fourth.getZ();
   }

   private static int safeInt(long value) {
      return value < Integer.MIN_VALUE ? Integer.MIN_VALUE : value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int)value;
   }

   record Result(Set<BlockPos> fill, Set<BlockPos> outline) {
      private static Result empty() {
         return new Result(Set.of(), Set.of());
      }
   }

   enum Status {
      SUCCESS,
      NOT_APPLICABLE,
      LIMIT_EXCEEDED,
      NO_VALID_CANDIDATE
   }

   record Attempt(Status status, Result result) {
      Attempt {
         status = status == null ? Status.NO_VALID_CANDIDATE : status;
         result = result == null ? Result.empty() : result;
      }

      boolean succeeded() {
         return this.status == Status.SUCCESS;
      }

      static Attempt success(Result result) {
         return new Attempt(Status.SUCCESS, result);
      }

      static Attempt failed(Status status) {
         if (status == Status.SUCCESS) {
            throw new IllegalArgumentException("success requires a result");
         }
         return new Attempt(status, Result.empty());
      }
   }

   private record Pixel(int u, int v) {
   }

   private static final class CellAccumulator {
      private static final Comparator<Pixel> PIXEL_ORDER = Comparator
         .comparingInt(Pixel::u)
         .thenComparingInt(Pixel::v);
      private final ProjectedBresenhamFace.Frame frame;
      private final Set<BlockPos> outline;
      private final Set<Pixel> outlineColumns = new LinkedHashSet<>();
      private final Set<BlockPos> generatedOutline = new LinkedHashSet<>();
      private final LinkedHashSet<BlockPos> blocks = new LinkedHashSet<>();

      CellAccumulator(ProjectedBresenhamFace.Frame frame, Set<BlockPos> outline) {
         this.frame = frame;
         this.outline = outline;
         for (BlockPos point : outline) {
            this.outlineColumns.add(project(frame, point));
         }
      }

      void add(BlockPos block) {
         if (this.outline.contains(block)) {
            this.generatedOutline.add(block);
            this.blocks.add(block);
            return;
         }
         this.blocks.add(block);
      }

      void addLogical(BlockPos block) {
         this.blocks.add(block);
      }

      Set<BlockPos> blocks() {
         return Collections.unmodifiableSet(new LinkedHashSet<>(this.blocks));
      }

      /**
       * Turns the logical raster into a physically joined voxel surface while
       * leaving the authoritative LineGenerator outline intact.  Every cardinal
       * transition on one face uses the same support side.  The lower and upper
       * candidates are both derived from the same immutable columns, then the
       * smoother envelope wins; local 2x2 decisions are never allowed to cascade.
       */
      void sealPhysicalTransitions() {
         Map<Pixel, NavigableSet<Integer>> original = this.columnHeights();
         if (original.isEmpty()) {
            return;
         }
         bridgeDiagonalColumns(this.frame, outlineBounds(this.outline), original);
         Map<Pixel, NavigableSet<Integer>> base = copyColumns(original);
         base.values().forEach(CellAccumulator::fillInternalSpan);
         CandidateColumns lower = candidate(base, SupportSide.LOWER, this.outlineColumns);
         CandidateColumns upper = candidate(base, SupportSide.UPPER, this.outlineColumns);
         Map<Pixel, NavigableSet<Integer>> occupied = lower.compareTo(upper) <= 0
            ? lower.columns()
            : upper.columns();

         for (Map.Entry<Pixel, NavigableSet<Integer>> entry : occupied.entrySet()) {
            Pixel column = entry.getKey();
            for (int height : entry.getValue()) {
               this.blocks.add(this.frame.restore(new ProjectedBresenhamFace.Int3(column.u(), column.v(), height)));
            }
         }
      }

      private Map<Pixel, NavigableSet<Integer>> columnHeights() {
         LinkedHashMap<Pixel, NavigableSet<Integer>> result = new LinkedHashMap<>();
         for (BlockPos block : this.blocks) {
            Pixel column = project(this.frame, block);
            result.computeIfAbsent(column, ignored -> new TreeSet<>()).add(localHeight(this.frame, block));
         }
         return result;
      }

      private static Map<Pixel, NavigableSet<Integer>> copyColumns(
         Map<Pixel, NavigableSet<Integer>> source
      ) {
         LinkedHashMap<Pixel, NavigableSet<Integer>> result = new LinkedHashMap<>();
         source.forEach((column, heights) -> result.put(column, new TreeSet<>(heights)));
         return result;
      }

      /** Completes a checkerboard projection corner instead of choosing another asymmetric L route. */
      private static void bridgeDiagonalColumns(
         ProjectedBresenhamFace.Frame frame,
         int[] outlineBounds,
         Map<Pixel, NavigableSet<Integer>> columns
      ) {
         List<Pixel> source = columns.keySet().stream().sorted(PIXEL_ORDER).toList();
         for (Pixel first : source) {
            if (first.u() == Integer.MAX_VALUE) {
               continue;
            }
            for (int vOffset : new int[]{-1, 1}) {
               if (vOffset < 0 && first.v() == Integer.MIN_VALUE
                  || vOffset > 0 && first.v() == Integer.MAX_VALUE) {
                  continue;
               }
               Pixel diagonal = new Pixel(first.u() + 1, first.v() + vOffset);
               if (!columns.containsKey(diagonal)) {
                  continue;
               }
               Pixel acrossU = new Pixel(first.u() + 1, first.v());
               Pixel acrossV = new Pixel(first.u(), first.v() + vOffset);
               if (columns.containsKey(acrossU) || columns.containsKey(acrossV)) {
                  continue;
               }
               BlockPos acrossUBlock = frame.restore(acrossU.u(), acrossU.v());
               BlockPos acrossVBlock = frame.restore(acrossV.u(), acrossV.v());
               boolean acrossUInside = withinBounds(acrossUBlock, outlineBounds);
               boolean acrossVInside = withinBounds(acrossVBlock, outlineBounds);
               if (acrossUInside || !acrossVInside) {
                  columns.put(acrossU, singletonHeight(frame.height(acrossU.u(), acrossU.v())));
               }
               if (acrossVInside || !acrossUInside) {
                  columns.put(acrossV, singletonHeight(frame.height(acrossV.u(), acrossV.v())));
               }
            }
         }
      }

      private static int[] outlineBounds(Set<BlockPos> outline) {
         return new int[]{
            outline.stream().mapToInt(BlockPos::getX).min().orElse(0),
            outline.stream().mapToInt(BlockPos::getY).min().orElse(0),
            outline.stream().mapToInt(BlockPos::getZ).min().orElse(0),
            outline.stream().mapToInt(BlockPos::getX).max().orElse(0),
            outline.stream().mapToInt(BlockPos::getY).max().orElse(0),
            outline.stream().mapToInt(BlockPos::getZ).max().orElse(0)
         };
      }

      private static boolean withinBounds(BlockPos position, int[] bounds) {
         return position.getX() >= bounds[0] && position.getX() <= bounds[3]
            && position.getY() >= bounds[1] && position.getY() <= bounds[4]
            && position.getZ() >= bounds[2] && position.getZ() <= bounds[5];
      }

      private static NavigableSet<Integer> singletonHeight(int height) {
         TreeSet<Integer> result = new TreeSet<>();
         result.add(height);
         return result;
      }

      private static void fillInternalSpan(NavigableSet<Integer> heights) {
         if (heights.isEmpty()) {
            return;
         }
         int minimum = heights.first();
         int maximum = heights.last();
         for (long height = minimum; height <= maximum; height++) {
            heights.add((int)height);
         }
      }

      private static CandidateColumns candidate(
         Map<Pixel, NavigableSet<Integer>> snapshot,
         SupportSide side,
         Set<Pixel> outlineColumns
      ) {
         Map<Pixel, NavigableSet<Integer>> result = copyColumns(snapshot);
         List<Pixel> columns = snapshot.keySet().stream().sorted(PIXEL_ORDER).toList();
         for (Pixel column : columns) {
            if (column.u() != Integer.MAX_VALUE) {
               connectPair(snapshot, result, column, new Pixel(column.u() + 1, column.v()), side);
            }
            if (column.v() != Integer.MAX_VALUE) {
               connectPair(snapshot, result, column, new Pixel(column.u(), column.v() + 1), side);
            }
         }
         result.values().forEach(CellAccumulator::fillInternalSpan);

         Map<Pixel, Integer> upper = new HashMap<>();
         Map<Pixel, Integer> lower = new HashMap<>();
         result.forEach((column, heights) -> {
            upper.put(column, heights.last());
            lower.put(column, heights.first());
         });
         int basinCells = enclosedBasinCellCount(upper) + enclosedBasinCellCount(negated(lower));
         int strictPits = strictPitCount(upper, outlineColumns) + strictPitCount(negated(lower), outlineColumns);
         int maximumInteriorThickness = result.entrySet().stream()
            .filter(entry -> !outlineColumns.contains(entry.getKey()))
            .mapToInt(entry -> entry.getValue().size())
            .max()
            .orElse(0);
         long addedBlocks = blockCount(result) - blockCount(snapshot);
         return new CandidateColumns(
            result,
            basinCells,
            strictPits,
            maximumInteriorThickness,
            addedBlocks,
            side
         );
      }

      private static void connectPair(
         Map<Pixel, NavigableSet<Integer>> snapshot,
         Map<Pixel, NavigableSet<Integer>> result,
         Pixel first,
         Pixel second,
         SupportSide side
      ) {
         NavigableSet<Integer> firstHeights = snapshot.get(first);
         NavigableSet<Integer> secondHeights = snapshot.get(second);
         if (firstHeights == null || secondHeights == null) {
            return;
         }
         Pixel lowColumn;
         NavigableSet<Integer> low;
         Pixel highColumn;
         NavigableSet<Integer> high;
         if (firstHeights.last() < secondHeights.first()) {
            lowColumn = first;
            low = firstHeights;
            highColumn = second;
            high = secondHeights;
         } else if (secondHeights.last() < firstHeights.first()) {
            lowColumn = second;
            low = secondHeights;
            highColumn = first;
            high = firstHeights;
         } else {
            return;
         }
         if (side == SupportSide.LOWER) {
            result.get(highColumn).add(low.last());
         } else {
            result.get(lowColumn).add(high.first());
         }
      }

      /**
       * Counts cells belonging to any closed sublevel component.  A priority
       * flood computes each cell's lowest possible escape level to the domain
       * boundary in O(n log n), avoiding one flood per integer height.
       */
      private static int enclosedBasinCellCount(Map<Pixel, Integer> terrain) {
         if (terrain.isEmpty()) {
            return 0;
         }
         Map<Pixel, Long> escape = new HashMap<>();
         PriorityQueue<FloodNode> open = new PriorityQueue<>();
         for (Map.Entry<Pixel, Integer> entry : terrain.entrySet()) {
            Pixel column = entry.getKey();
            if (cardinalNeighbors(column).stream().anyMatch(neighbor -> !terrain.containsKey(neighbor))) {
               long level = entry.getValue();
               escape.put(column, level);
               open.add(new FloodNode(column, level));
            }
         }
         while (!open.isEmpty()) {
            FloodNode current = open.remove();
            if (escape.getOrDefault(current.column(), Long.MAX_VALUE) != current.level()) {
               continue;
            }
            for (Pixel neighbor : cardinalNeighbors(current.column())) {
               Integer height = terrain.get(neighbor);
               if (height == null) {
                  continue;
               }
               long candidate = Math.max(current.level(), (long)height);
               if (candidate < escape.getOrDefault(neighbor, Long.MAX_VALUE)) {
                  escape.put(neighbor, candidate);
                  open.add(new FloodNode(neighbor, candidate));
               }
            }
         }
         int result = 0;
         for (Map.Entry<Pixel, Integer> entry : terrain.entrySet()) {
            if (escape.getOrDefault(entry.getKey(), Long.MAX_VALUE) > entry.getValue()) {
               result++;
            }
         }
         return result;
      }

      private static int strictPitCount(Map<Pixel, Integer> terrain, Set<Pixel> excluded) {
         int result = 0;
         for (Map.Entry<Pixel, Integer> entry : terrain.entrySet()) {
            if (excluded.contains(entry.getKey())) {
               continue;
            }
            List<Pixel> neighbors = cardinalNeighbors(entry.getKey());
            if (neighbors.stream().allMatch(terrain::containsKey)
               && neighbors.stream().allMatch(neighbor -> entry.getValue() < terrain.get(neighbor))) {
               result++;
            }
         }
         return result;
      }

      private static Map<Pixel, Integer> negated(Map<Pixel, Integer> source) {
         Map<Pixel, Integer> result = new HashMap<>();
         source.forEach((column, height) -> result.put(column, -height));
         return result;
      }

      private static List<Pixel> cardinalNeighbors(Pixel column) {
         ArrayList<Pixel> result = new ArrayList<>(4);
         if (column.u() != Integer.MAX_VALUE) {
            result.add(new Pixel(column.u() + 1, column.v()));
         }
         if (column.u() != Integer.MIN_VALUE) {
            result.add(new Pixel(column.u() - 1, column.v()));
         }
         if (column.v() != Integer.MAX_VALUE) {
            result.add(new Pixel(column.u(), column.v() + 1));
         }
         if (column.v() != Integer.MIN_VALUE) {
            result.add(new Pixel(column.u(), column.v() - 1));
         }
         return result;
      }

      private static long blockCount(Map<Pixel, NavigableSet<Integer>> columns) {
         return columns.values().stream().mapToLong(Set::size).sum();
      }

      private enum SupportSide {
         LOWER,
         UPPER
      }

      private record FloodNode(Pixel column, long level) implements Comparable<FloodNode> {
         @Override
         public int compareTo(FloodNode other) {
            int compared = Long.compare(this.level, other.level);
            return compared != 0 ? compared : PIXEL_ORDER.compare(this.column, other.column);
         }
      }

      private record CandidateColumns(
         Map<Pixel, NavigableSet<Integer>> columns,
         int basinCells,
         int strictPits,
         int maximumInteriorThickness,
         long addedBlocks,
         SupportSide side
      ) implements Comparable<CandidateColumns> {
         @Override
         public int compareTo(CandidateColumns other) {
            int compared = Integer.compare(this.basinCells, other.basinCells);
            if (compared == 0) {
               compared = Integer.compare(this.strictPits, other.strictPits);
            }
            if (compared == 0) {
               compared = Integer.compare(this.maximumInteriorThickness, other.maximumInteriorThickness);
            }
            if (compared == 0) {
               compared = Long.compare(this.addedBlocks, other.addedBlocks);
            }
            return compared != 0 ? compared : this.side.compareTo(other.side);
         }
      }

      private static int localHeight(ProjectedBresenhamFace.Frame frame, BlockPos position) {
         long[] delta = {
            (long)position.getX() - frame.anchor().getX(),
            (long)position.getY() - frame.anchor().getY(),
            (long)position.getZ() - frame.anchor().getZ()
         };
         return safeInt(delta[frame.order()[2]] * frame.signs()[2]);
      }

      void requireOwnedOutline() {
         if (!this.generatedOutline.containsAll(this.outline)) {
            LinkedHashSet<BlockPos> missing = new LinkedHashSet<>(this.outline);
            missing.removeAll(this.generatedOutline);
            throw new IllegalStateException("digital plane did not generate owned outline " + missing);
         }
      }
   }
}
