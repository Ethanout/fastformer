package io.github.fastformer.common.geometry.generation.face;

import io.github.fastformer.common.geometry.raster.BlockGenerationObserver;
import io.github.fastformer.core.graph.ExteriorUnionFind;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.core.BlockPos;

import static io.github.fastformer.common.geometry.generation.face.TranslatedScanFaceRasterizer.*;
import static io.github.fastformer.common.geometry.generation.face.TranslatedScanModel.*;

/** Checks projected columns, voxel connectivity, and enclosed air. */
final class TranslatedScanTopology {
   private TranslatedScanTopology() {
   }

   static ComponentMap sixComponents(Set<BlockPos> blocks, BlockGenerationObserver observer) {
      HashSet<BlockPos> unseen = new HashSet<>(blocks);
      LinkedHashMap<BlockPos, Integer> byBlock = new LinkedHashMap<>();
      int component = 0;
      while (!unseen.isEmpty()) {
         observer.checkCancelled();
         ArrayDeque<BlockPos> open = new ArrayDeque<>();
         BlockPos first = unseen.iterator().next();
         unseen.remove(first);
         open.add(first);
         while (!open.isEmpty()) {
            BlockPos current = open.removeFirst();
            byBlock.put(current, component);
            for (BlockPos neighbor : sixNeighbors(current)) {
               if (unseen.remove(neighbor)) {
                  open.addLast(neighbor);
               }
            }
         }
         component++;
      }
      return new ComponentMap(Collections.unmodifiableMap(byBlock), component);
   }

   static List<BlockPos> sixNeighbors(BlockPos position) {
      return List.of(
         position.offset(1, 0, 0), position.offset(-1, 0, 0),
         position.offset(0, 1, 0), position.offset(0, -1, 0),
         position.offset(0, 0, 1), position.offset(0, 0, -1)
      );
   }

   static Analysis analyze(
      Model model,
      Set<BlockPos> blocks,
      BlockGenerationObserver observer
   ) {
      Map<Pixel, NavigableSet<Integer>> columns = columns(model, blocks);
      int missing = 0;
      int maximumThickness = 0;
      int maximumInteriorThickness = 0;
      int gaps = 0;
      LinkedHashMap<Pixel, Integer> logical = new LinkedHashMap<>();
      Set<Pixel> interior = new HashSet<>(model.domain());
      interior.removeAll(model.boundary());
      interior.removeAll(model.ownedColumns().keySet());
      for (Pixel pixel : sorted(model.domain())) {
         observer.checkCancelled();
         NavigableSet<Integer> heights = columns.get(pixel);
         if (heights == null || heights.isEmpty()) {
            missing++;
            continue;
         }
         maximumThickness = Math.max(maximumThickness, heights.size());
         if (interior.contains(pixel)) {
            maximumInteriorThickness = Math.max(maximumInteriorThickness, heights.size());
         }
         if ((long)heights.last() - heights.first() + 1L != heights.size()) {
            gaps++;
         }
         logical.put(pixel, logicalHeight(model, pixel, heights));
      }
      LinkedHashMap<Pixel, Integer> upper = new LinkedHashMap<>();
      LinkedHashMap<Pixel, Integer> lower = new LinkedHashMap<>();
      columns.forEach((pixel, heights) -> {
         if (model.domain().contains(pixel) && !heights.isEmpty()) {
            upper.put(pixel, heights.last());
            lower.put(pixel, heights.first());
         }
      });
      int strictExtrema = strictExtrema(logical) + strictExtrema(upper) + strictExtrema(lower);
      int basinCells = enclosedBasinCellCount(upper, observer)
         + enclosedBasinCellCount(negated(lower), observer);
      Set<Pixel> supportCells = new HashSet<>();
      int support = 0;
      for (Map.Entry<Pixel, NavigableSet<Integer>> entry : columns.entrySet()) {
         if (!logical.containsKey(entry.getKey())) {
            continue;
         }
         int logicalHeight = logical.get(entry.getKey());
         boolean hasSupport = false;
         for (int height : entry.getValue()) {
            if (height != logicalHeight
               && !model.outline().contains(model.restore(entry.getKey(), height))) {
               support++;
               hasSupport = true;
            }
         }
         if (hasSupport && interior.contains(entry.getKey())) {
            supportCells.add(entry.getKey());
         }
      }
      Set<Pixel> extraContourCells = extraContourCells(columns, logical);
      boolean outlineMissing = !blocks.containsAll(model.outline());
      boolean leak = airLeaks(model, columns, observer);
      boolean valid = missing == 0
         && !outlineMissing
         && maximumInteriorThickness <= 2
         && gaps == 0
         && !leak
         && isSixConnected(blocks, observer);
      return new Analysis(
         valid,
         maximumThickness,
         maximumInteriorThickness,
         support,
         largestComponent(supportCells),
         largestComponent(extraContourCells),
         basinCells,
         strictExtrema,
         blocks.size()
      );
   }

   private static boolean isSixConnected(Set<BlockPos> blocks, BlockGenerationObserver observer) {
      if (blocks.isEmpty()) {
         return false;
      }
      HashSet<BlockPos> unseen = new HashSet<>(blocks);
      ArrayDeque<BlockPos> open = new ArrayDeque<>();
      BlockPos first = unseen.iterator().next();
      unseen.remove(first);
      open.add(first);
      int visited = 0;
      while (!open.isEmpty()) {
         if ((visited++ & 1023) == 0) {
            observer.checkCancelled();
         }
         BlockPos current = open.removeFirst();
         for (BlockPos neighbor : List.of(
            current.offset(1, 0, 0), current.offset(-1, 0, 0),
            current.offset(0, 1, 0), current.offset(0, -1, 0),
            current.offset(0, 0, 1), current.offset(0, 0, -1)
         )) {
            if (unseen.remove(neighbor)) {
               open.addLast(neighbor);
            }
         }
      }
      return unseen.isEmpty();
   }

   private static int logicalHeight(
      Model model,
      Pixel pixel,
      NavigableSet<Integer> heights
   ) {
      double real = model.realHeight(pixel);
      double phaseTarget = model.preferred().get(pixel).stream()
         .mapToInt(Integer::intValue)
         .average()
         .orElse(real);
      return heights.stream().min(Comparator
         .comparingInt((Integer value) -> model.preferred().get(pixel).contains(value) ? 0 : 1)
         .thenComparingDouble(value -> Math.abs(value - phaseTarget))
         .thenComparingDouble(value -> Math.abs(value - real))
         .thenComparingInt(Integer::intValue)).orElseThrow();
   }

   private static int strictExtrema(Map<Pixel, Integer> logical) {
      int result = 0;
      for (Map.Entry<Pixel, Integer> entry : logical.entrySet()) {
         List<Pixel> neighbors = neighbors(entry.getKey());
         if (!neighbors.stream().allMatch(logical::containsKey)) {
            continue;
         }
         int height = entry.getValue();
         boolean lower = neighbors.stream().allMatch(pixel -> height < logical.get(pixel));
         boolean upper = neighbors.stream().allMatch(pixel -> height > logical.get(pixel));
         if (lower || upper) {
            result++;
         }
      }
      return result;
   }

   private static int enclosedBasinCellCount(
      Map<Pixel, Integer> terrain,
      BlockGenerationObserver observer
   ) {
      if (terrain.isEmpty()) {
         return 0;
      }
      Map<Pixel, Long> escape = new HashMap<>();
      PriorityQueue<FloodNode> open = new PriorityQueue<>();
      for (Map.Entry<Pixel, Integer> entry : terrain.entrySet()) {
         Pixel pixel = entry.getKey();
         if (neighbors(pixel).stream().anyMatch(neighbor -> !terrain.containsKey(neighbor))) {
            long level = entry.getValue();
            escape.put(pixel, level);
            open.add(new FloodNode(pixel, level));
         }
      }
      int visited = 0;
      while (!open.isEmpty()) {
         if ((visited++ & 1023) == 0) {
            observer.checkCancelled();
         }
         FloodNode current = open.remove();
         if (escape.getOrDefault(current.pixel(), Long.MAX_VALUE) != current.level()) {
            continue;
         }
         for (Pixel neighbor : neighbors(current.pixel())) {
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

   private static Map<Pixel, Integer> negated(Map<Pixel, Integer> source) {
      LinkedHashMap<Pixel, Integer> result = new LinkedHashMap<>();
      source.forEach((pixel, height) -> result.put(pixel, -height));
      return result;
   }

   private static Set<Pixel> extraContourCells(
      Map<Pixel, NavigableSet<Integer>> columns,
      Map<Pixel, Integer> logical
   ) {
      HashSet<Pixel> result = new HashSet<>();
      for (Pixel pixel : logical.keySet()) {
         for (Pixel step : List.of(new Pixel(1, 0), new Pixel(0, 1))) {
            Pixel neighbor = pixel.add(step);
            if (!logical.containsKey(neighbor)) {
               continue;
            }
            boolean logicalStep = !logical.get(pixel).equals(logical.get(neighbor));
            boolean lowerStep = columns.get(pixel).first().intValue() != columns.get(neighbor).first().intValue();
            boolean upperStep = columns.get(pixel).last().intValue() != columns.get(neighbor).last().intValue();
            if (logicalStep != lowerStep || logicalStep != upperStep) {
               result.add(pixel);
               result.add(neighbor);
            }
         }
      }
      return result;
   }

   private static int largestComponent(Set<Pixel> source) {
      HashSet<Pixel> unseen = new HashSet<>(source);
      int largest = 0;
      while (!unseen.isEmpty()) {
         Pixel first = unseen.iterator().next();
         unseen.remove(first);
         ArrayDeque<Pixel> open = new ArrayDeque<>();
         open.add(first);
         int size = 0;
         while (!open.isEmpty()) {
            Pixel pixel = open.removeFirst();
            size++;
            for (Pixel neighbor : neighbors(pixel)) {
               if (unseen.remove(neighbor)) {
                  open.addLast(neighbor);
               }
            }
         }
         largest = Math.max(largest, size);
      }
      return largest;
   }

   /** Tests lower-to-upper air only inside the projected prism; side-wall detours are excluded. */
   private static boolean airLeaks(
      Model model,
      Map<Pixel, NavigableSet<Integer>> columns,
      BlockGenerationObserver observer
   ) {
      LinkedHashMap<Pixel, IntRange> ranges = new LinkedHashMap<>();
      Set<Cell> solid = new HashSet<>();
      for (Pixel pixel : sorted(model.domain())) {
         observer.checkCancelled();
         NavigableSet<Integer> heights = columns.get(pixel);
         if (heights == null || heights.isEmpty()) {
            return true;
         }
         ranges.put(pixel, new IntRange(
            safeInt((long)heights.first() - 1L),
            safeInt((long)heights.last() + 1L)
         ));
         heights.forEach(height -> solid.add(new Cell(pixel, height)));
      }
      ArrayDeque<Cell> open = new ArrayDeque<>();
      HashSet<Cell> seen = new HashSet<>();
      for (Map.Entry<Pixel, IntRange> entry : ranges.entrySet()) {
         Cell bottom = new Cell(entry.getKey(), entry.getValue().minimum());
         if (!solid.contains(bottom) && seen.add(bottom)) {
            open.addLast(bottom);
         }
      }
      int visited = 0;
      while (!open.isEmpty()) {
         if ((visited++ & 1023) == 0) {
            observer.checkCancelled();
         }
         Cell cell = open.removeFirst();
         IntRange range = ranges.get(cell.pixel());
         if (cell.height() == range.maximum()) {
            return true;
         }
         for (int height : new int[]{cell.height() - 1, cell.height() + 1}) {
            if (height < range.minimum() || height > range.maximum()) {
               continue;
            }
            Cell next = new Cell(cell.pixel(), height);
            if (!solid.contains(next) && seen.add(next)) {
               open.addLast(next);
            }
         }
         for (Pixel neighborPixel : neighbors(cell.pixel())) {
            if (!model.domain().contains(neighborPixel)) {
               continue;
            }
            IntRange neighborRange = ranges.get(neighborPixel);
            if (cell.height() > neighborRange.maximum()) {
               return true;
            }
            if (cell.height() < neighborRange.minimum()) {
               continue;
            }
            Cell next = new Cell(neighborPixel, cell.height());
            if (!solid.contains(next) && seen.add(next)) {
               open.addLast(next);
            }
         }
      }
      return false;
   }

   static Map<Pixel, NavigableSet<Integer>> columns(Model model, Set<BlockPos> blocks) {
      LinkedHashMap<Pixel, NavigableSet<Integer>> result = new LinkedHashMap<>();
      for (BlockPos block : blocks) {
         Pixel pixel = model.project(block);
         result.computeIfAbsent(pixel, ignored -> new TreeSet<>()).add(model.height(block));
      }
      return result;
   }

   static List<Pixel> sorted(Set<Pixel> pixels) {
      return pixels.stream().sorted(PIXEL_ORDER).toList();
   }

   static List<Pixel> neighbors(Pixel pixel) {
      return List.of(
         new Pixel(pixel.u() + 1, pixel.v()),
         new Pixel(pixel.u() - 1, pixel.v()),
         new Pixel(pixel.u(), pixel.v() + 1),
         new Pixel(pixel.u(), pixel.v() - 1)
      );
   }

   static void addLatticeSegment(
      Set<Pixel> output,
      BigPoint first,
      BigPoint second,
      BlockGenerationObserver observer
   ) {
      BigPoint delta = second.subtract(first);
      int steps = delta.coordinateGcd().intValueExact();
      if (steps == 0) {
         output.add(first.toPixel());
         return;
      }
      BigInteger divisor = BigInteger.valueOf(steps);
      for (int step = 0; step <= steps; step++) {
         if ((step & 1023) == 0) {
            observer.checkCancelled();
         }
         BigInteger scale = BigInteger.valueOf(step);
         output.add(new BigPoint(
            first.u().add(delta.u().multiply(scale).divide(divisor)),
            first.v().add(delta.v().multiply(scale).divide(divisor))
         ).toPixel());
      }
   }

   static void addMissingRun(
      List<MissingRun> output,
      List<Integer> rowRuns,
      int v,
      long minimumU,
      long maximumU,
      long lowU,
      long highU,
      boolean openVertically
   ) {
      int index = output.size();
      output.add(new MissingRun(
         v,
         minimumU,
         maximumU,
         openVertically || minimumU == lowU || maximumU == highU
      ));
      rowRuns.add(index);
   }

   static void unionOverlappingRuns(
      List<MissingRun> runs,
      ExteriorUnionFind components,
      List<Integer> firstRow,
      List<Integer> secondRow,
      BlockGenerationObserver observer
   ) {
      int firstIndex = 0;
      int secondIndex = 0;
      int visited = 0;
      while (firstIndex < firstRow.size() && secondIndex < secondRow.size()) {
         if ((visited++ & 1023) == 0) {
            observer.checkCancelled();
         }
         int firstId = firstRow.get(firstIndex);
         int secondId = secondRow.get(secondIndex);
         MissingRun first = runs.get(firstId);
         MissingRun second = runs.get(secondId);
         if (first.maximumU() < second.minimumU()) {
            firstIndex++;
         } else if (second.maximumU() < first.minimumU()) {
            secondIndex++;
         } else {
            components.union(firstId, secondId);
            if (first.maximumU() <= second.maximumU()) {
               firstIndex++;
            }
            if (second.maximumU() <= first.maximumU()) {
               secondIndex++;
            }
         }
      }
   }

}
