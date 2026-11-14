package io.github.fastformer.common.geometry.generation.face;

import io.github.fastformer.common.geometry.LineTieBias;
import io.github.fastformer.common.geometry.generation.source.LineGenerator;
import io.github.fastformer.common.geometry.raster.BlockGenerationObserver;
import io.github.fastformer.core.graph.CountingUnionFind;
import io.github.fastformer.core.graph.FlowGraph;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.core.BlockPos;

import static io.github.fastformer.common.geometry.generation.face.TranslatedScanFaceRasterizer.*;
import static io.github.fastformer.common.geometry.generation.face.TranslatedScanModel.*;
import static io.github.fastformer.common.geometry.generation.face.TranslatedScanTopology.*;

/** Selects edge connectors, six-connected supports, and minimum separators. */
final class TranslatedScanConnections {
   private static final int CANDIDATE_BAND = 2;
   private static final int MAX_CONNECTION_COLUMNS = 1_024;
   private static final long MAX_CUT_CELLS = 65_536L;
   private static final long INFINITE_CAPACITY = Long.MAX_VALUE / 16L;

   private TranslatedScanConnections() {
   }

   static Set<BlockPos> ownedSixConnectors(
      Model model,
      LineTieBias tieBias,
      BlockGenerationObserver observer
   ) {
      List<BlockPos> vertices = model.frame().vertices().stream().map(model.frame()::restore).toList();
      LinkedHashSet<BlockPos> result = new LinkedHashSet<>();
      for (int edge = 0; edge < vertices.size(); edge++) {
         List<BlockPos> path = LineGenerator.path(
            vertices.get(edge),
            vertices.get((edge + 1) % vertices.size()),
            tieBias
         );
         for (int index = 1; index < path.size(); index++) {
            observer.checkCancelled();
            result.addAll(bestOrthogonalRoute(model, path.get(index - 1), path.get(index)));
         }
      }
      result.removeAll(model.outline());
      return Collections.unmodifiableSet(result);
   }

   private static List<BlockPos> bestOrthogonalRoute(Model model, BlockPos first, BlockPos second) {
      LocalPoint a = local(model, first);
      LocalPoint b = local(model, second);
      LocalPoint start = a.compareTo(b) <= 0 ? a : b;
      LocalPoint end = start == a ? b : a;
      ConnectorRoute best = null;
      for (int[] order : List.of(
         new int[]{0, 1, 2}, new int[]{0, 2, 1},
         new int[]{1, 0, 2}, new int[]{1, 2, 0},
         new int[]{2, 0, 1}, new int[]{2, 1, 0}
      )) {
         int[] current = {start.u(), start.v(), start.w()};
         ArrayList<LocalPoint> route = new ArrayList<>();
         for (int axis : order) {
            int target = switch (axis) {
               case 0 -> end.u();
               case 1 -> end.v();
               default -> end.w();
            };
            if (current[axis] == target) {
               continue;
            }
            current[axis] = target;
            LocalPoint point = new LocalPoint(current[0], current[1], current[2]);
            if (!point.equals(end)) {
               route.add(point);
            }
         }
         ConnectorRoute candidate = ConnectorRoute.create(model, route);
         if (best == null || candidate.compareTo(best) < 0) {
            best = candidate;
         }
      }
      return best == null ? List.of() : best.points().stream()
         .map(point -> model.restore(new Pixel(point.u(), point.v()), point.w()))
         .toList();
   }

   private static LocalPoint local(Model model, BlockPos position) {
      Pixel pixel = model.project(position);
      return new LocalPoint(pixel.u(), pixel.v(), model.height(position));
   }

   static BresenhamFaceSweep.Attempt coordinatedSixConnectedAttempt(
      ProjectedBresenhamFace.Frame frame,
      LineTieBias tieBias,
      int maxBlocks,
      BlockGenerationObserver observer
   ) {
      ModelBuild built = Model.createAnalytic(frame, tieBias, maxBlocks, observer, true);
      if (built.model() == null && built.status() == BresenhamFaceSweep.Status.NO_VALID_CANDIDATE) {
         built = Model.createAnalytic(frame, tieBias, maxBlocks, observer, false);
      }
      if (built.model() == null) {
         return BresenhamFaceSweep.Attempt.failed(built.status());
      }
      Model model = built.model();
      if (model.domain().size() > MAX_CONNECTION_COLUMNS) {
         return BresenhamFaceSweep.Attempt.failed(BresenhamFaceSweep.Status.NO_VALID_CANDIDATE);
      }
      FallbackBlocks primary = materializeAnalyticHeightBands(model, maxBlocks, observer);
      if (primary.blocks().isEmpty()) {
         return BresenhamFaceSweep.Attempt.failed(primary.limitExceeded()
            ? BresenhamFaceSweep.Status.LIMIT_EXCEEDED
            : BresenhamFaceSweep.Status.NO_VALID_CANDIDATE);
      }

      LinkedHashSet<BlockPos> result = new LinkedHashSet<>(primary.blocks());
      Set<BlockPos> connectionSupports = minimumSixConnectionSupports(model, result, observer);
      if (connectionSupports == null) {
         return BresenhamFaceSweep.Attempt.failed(BresenhamFaceSweep.Status.NO_VALID_CANDIDATE);
      }
      result.addAll(connectionSupports);
      if (result.size() > maxBlocks) {
         return BresenhamFaceSweep.Attempt.failed(BresenhamFaceSweep.Status.LIMIT_EXCEEDED);
      }
      Analysis analysis = analyze(model, result, observer);
      if (!analysis.valid()) {
         return BresenhamFaceSweep.Attempt.failed(BresenhamFaceSweep.Status.NO_VALID_CANDIDATE);
      }
      return BresenhamFaceSweep.Attempt.success(new BresenhamFaceSweep.Result(
         Collections.unmodifiableSet(result),
         model.outline()
      ));
   }

   static Set<BlockPos> minimumSixConnectionSupports(
      Model model,
      Set<BlockPos> base,
      BlockGenerationObserver observer
   ) {
      ComponentMap components = sixComponents(base, observer);
      if (components.count() <= 1) {
         return Set.of();
      }
      Map<Pixel, NavigableSet<Integer>> baseColumns = columns(model, base);
      LinkedHashMap<BlockPos, HashSet<Integer>> adjacentComponents = new LinkedHashMap<>();
      for (BlockPos block : base) {
         observer.checkCancelled();
         int component = components.byBlock().get(block);
         for (BlockPos candidate : sixNeighbors(block)) {
            if (!base.contains(candidate)) {
               adjacentComponents.computeIfAbsent(candidate, ignored -> new HashSet<>()).add(component);
            }
         }
      }
      ArrayList<ConnectionSupport> candidates = new ArrayList<>();
      for (Map.Entry<BlockPos, HashSet<Integer>> entry : adjacentComponents.entrySet()) {
         if (entry.getValue().size() < 2) {
            continue;
         }
         Pixel pixel = model.project(entry.getKey());
         int height = model.height(entry.getKey());
         NavigableSet<Integer> column = baseColumns.get(pixel);
         if (column != null && !column.contains(height)) {
            if (column.size() >= 2
               || (long)height < (long)column.first() - 1L
               || (long)height > (long)column.last() + 1L) {
               continue;
            }
         }
         double residual = Math.abs(
            model.frame().normal().x * pixel.u()
               + model.frame().normal().y * pixel.v()
               + model.frame().normal().z * height
         ) / Math.abs(model.frame().normal().z);
         boolean nearBoundary = !model.domain().contains(pixel) || model.boundary().contains(pixel);
         candidates.add(new ConnectionSupport(
            entry.getKey(),
            Set.copyOf(entry.getValue()),
            nearBoundary ? 0 : 1,
            residual,
            new LocalPoint(pixel.u(), pixel.v(), height)
         ));
      }
      Collections.sort(candidates);
      CountingUnionFind union = new CountingUnionFind(components.count());
      LinkedHashSet<BlockPos> selected = new LinkedHashSet<>();
      LinkedHashMap<Pixel, NavigableSet<Integer>> selectedColumns = new LinkedHashMap<>();
      baseColumns.forEach((pixel, heights) -> selectedColumns.put(pixel, new TreeSet<>(heights)));
      for (int begin = 0; begin < candidates.size();) {
         observer.checkCancelled();
         ConnectionSupport key = candidates.get(begin);
         int end = begin + 1;
         while (end < candidates.size()
            && key.interiorPenalty() == candidates.get(end).interiorPenalty()
            && Double.compare(key.residual(), candidates.get(end).residual()) == 0) {
            end++;
         }
         ArrayList<ConnectionSupport> viable = new ArrayList<>();
         LinkedHashMap<Pixel, TreeSet<Integer>> proposed = new LinkedHashMap<>();
         for (int index = begin; index < end; index++) {
            ConnectionSupport candidate = candidates.get(index);
            HashSet<Integer> roots = new HashSet<>();
            candidate.components().forEach(component -> roots.add(union.find(component)));
            if (roots.size() < 2) {
               continue;
            }
            Pixel pixel = model.project(candidate.position());
            int height = model.height(candidate.position());
            TreeSet<Integer> heights = proposed.computeIfAbsent(pixel, ignored ->
               new TreeSet<>(selectedColumns.getOrDefault(pixel, Collections.emptyNavigableSet()))
            );
            heights.add(height);
            viable.add(candidate);
         }
         HashSet<Pixel> overfull = new HashSet<>();
         proposed.forEach((pixel, heights) -> {
            if (heights.size() > 2
               || (long)heights.last() - heights.first() + 1L != heights.size()) {
               overfull.add(pixel);
            }
         });
         for (ConnectionSupport candidate : viable) {
            Pixel pixel = model.project(candidate.position());
            if (overfull.contains(pixel)) {
               continue;
            }
            int height = model.height(candidate.position());
            selected.add(candidate.position());
            selectedColumns.computeIfAbsent(pixel, ignored -> new TreeSet<>()).add(height);
            HashSet<Integer> roots = new HashSet<>();
            candidate.components().forEach(component -> roots.add(union.find(component)));
            if (roots.size() >= 2) {
               int first = roots.iterator().next();
               roots.forEach(root -> union.union(first, root));
            }
         }
         if (union.count() == 1) {
            return selected;
         }
         begin = end;
      }
      return null;
   }

   static Set<BlockPos> minimumSeparator(Model model, BlockGenerationObserver observer) {
      long candidateValueBound = 0L;
      for (Pixel pixel : model.domain()) {
         candidateValueBound += 2L * CANDIDATE_BAND + 2L;
         candidateValueBound += model.preferred().get(pixel).size();
         candidateValueBound += model.ownedColumns().getOrDefault(pixel, Collections.emptyNavigableSet()).size();
         if (candidateValueBound > MAX_CUT_CELLS) {
            return Set.of();
         }
      }
      Map<Pixel, NavigableSet<Integer>> candidateHeights = candidateHeights(model);
      LinkedHashMap<Pixel, IntRange> ranges = new LinkedHashMap<>();
      long cellCount = 0L;
      for (Pixel pixel : sorted(model.domain())) {
         NavigableSet<Integer> values = candidateHeights.get(pixel);
         IntRange range = new IntRange(
            safeInt((long)values.first() - 1L),
            safeInt((long)values.last() + 1L)
         );
         long span = (long)range.maximum() - range.minimum() + 1L;
         if (span > MAX_CUT_CELLS - cellCount) {
            return Set.of();
         }
         cellCount += span;
         ranges.put(pixel, range);
      }
      Set<Cell> fixed = new HashSet<>();
      model.ownedColumns().forEach((pixel, heights) ->
         heights.forEach(height -> fixed.add(new Cell(pixel, height)))
      );

      LinkedHashMap<Cell, Long> finiteCosts = new LinkedHashMap<>();
      long voxelWeight = 2_001L * Math.max(1L, model.domain().size()) + 1L;
      for (Pixel pixel : sorted(model.domain())) {
         observer.checkCancelled();
         double real = model.realHeight(pixel);
         double phaseTarget = model.preferred().get(pixel).stream()
            .mapToInt(Integer::intValue)
            .average()
            .orElse(real);
         for (int height : candidateHeights.get(pixel)) {
            int error = (int)Math.min(
               999L,
               Math.round(200.0 * (Math.abs(height - real) + Math.abs(height - phaseTarget)))
            );
            long preference = model.preferred().get(pixel).contains(height) ? 0L : 1_000L;
            finiteCosts.put(new Cell(pixel, height), voxelWeight + preference + error);
         }
      }

      FlowGraph graph = new FlowGraph();
      int source = graph.addNode();
      int sink = graph.addNode();
      LinkedHashMap<Cell, NodePair> nodes = new LinkedHashMap<>();
      for (Pixel pixel : sorted(model.domain())) {
         observer.checkCancelled();
         IntRange range = ranges.get(pixel);
         for (long value = range.minimum(); value <= range.maximum(); value++) {
            Cell cell = new Cell(pixel, (int)value);
            if (!fixed.contains(cell)) {
               nodes.put(cell, new NodePair(graph.addNode(), graph.addNode()));
            }
         }
      }
      for (Map.Entry<Cell, NodePair> entry : nodes.entrySet()) {
         if ((entry.getValue().enter() & 1023) == 0) {
            observer.checkCancelled();
         }
         Cell cell = entry.getKey();
         NodePair pair = entry.getValue();
         graph.addEdge(pair.enter(), pair.leave(), finiteCosts.getOrDefault(cell, INFINITE_CAPACITY));
         IntRange range = ranges.get(cell.pixel());
         if (cell.height() == range.minimum()) {
            graph.addEdge(source, pair.enter(), INFINITE_CAPACITY);
         }
         if (cell.height() == range.maximum()) {
            graph.addEdge(pair.leave(), sink, INFINITE_CAPACITY);
         }
         connectAir(graph, pair, nodes.get(new Cell(cell.pixel(), cell.height() + 1)));
         for (Pixel step : List.of(
            new Pixel(1, 0), new Pixel(-1, 0),
            new Pixel(0, 1), new Pixel(0, -1)
         )) {
            Pixel neighborPixel = cell.pixel().add(step);
            if (!model.domain().contains(neighborPixel)) {
               continue;
            }
            Cell neighbor = new Cell(neighborPixel, cell.height());
            NodePair neighborPair = nodes.get(neighbor);
            if (neighborPair != null) {
               if (step.u() > 0 || step.v() > 0) {
                  connectAir(graph, pair, neighborPair);
               }
            } else if (!fixed.contains(neighbor)) {
               IntRange neighborRange = ranges.get(neighborPixel);
               if (cell.height() < neighborRange.minimum()) {
                  graph.addEdge(source, pair.enter(), INFINITE_CAPACITY);
               } else if (cell.height() > neighborRange.maximum()) {
                  graph.addEdge(pair.leave(), sink, INFINITE_CAPACITY);
               }
            }
         }
      }
      graph.maximumFlow(source, sink, observer::checkCancelled);
      boolean[] reachable = graph.reachableFrom(source);
      LinkedHashSet<BlockPos> selected = new LinkedHashSet<>(model.outline());
      for (Map.Entry<Cell, NodePair> entry : nodes.entrySet()) {
         Cell cell = entry.getKey();
         NodePair pair = entry.getValue();
         if (reachable[pair.enter()] && !reachable[pair.leave()]) {
            if (!finiteCosts.containsKey(cell)) {
               return Set.of();
            }
            selected.add(model.restore(cell.pixel(), cell.height()));
         }
      }
      return selected;
   }

   private static void connectAir(FlowGraph graph, NodePair first, NodePair second) {
      if (second == null) {
         return;
      }
      graph.addEdge(first.leave(), second.enter(), INFINITE_CAPACITY);
      graph.addEdge(second.leave(), first.enter(), INFINITE_CAPACITY);
   }

   private static Map<Pixel, NavigableSet<Integer>> candidateHeights(Model model) {
      LinkedHashMap<Pixel, NavigableSet<Integer>> result = new LinkedHashMap<>();
      for (Pixel pixel : sorted(model.domain())) {
         double real = model.realHeight(pixel);
         TreeSet<Integer> heights = new TreeSet<>();
         for (long height = (long)Math.floor(real) - CANDIDATE_BAND;
              height <= (long)Math.ceil(real) + CANDIDATE_BAND;
              height++) {
            heights.add(safeInt(height));
         }
         heights.addAll(model.preferred().get(pixel));
         heights.addAll(model.ownedColumns().getOrDefault(pixel, new TreeSet<>()));
         result.put(pixel, heights);
      }
      return result;
   }

   static Set<BlockPos> materializePreferred(
      Model model,
      int maxBlocks,
      BlockGenerationObserver observer
   ) {
      LinkedHashSet<BlockPos> result = new LinkedHashSet<>(model.outline());
      for (Pixel pixel : sorted(model.domain())) {
         observer.checkCancelled();
         for (int height : model.preferred().get(pixel)) {
            BlockPos block = model.restore(pixel, height);
            if (!result.contains(block) && result.size() >= maxBlocks) {
               return Set.of();
            }
            result.add(block);
         }
      }
      return result;
   }

   static FallbackBlocks graphFallback(
      Model model,
      HeightSnap snap,
      int maxBlocks,
      BlockGenerationObserver observer
   ) {
      long requiredBlocks = model.outline().size();
      for (Pixel pixel : sorted(model.domain())) {
         observer.checkCancelled();
         IntRange range = fallbackRange(model, pixel, snap);
         NavigableSet<Integer> owned = model.ownedColumns().get(pixel);
         int ownedCount = owned == null ? 0 : owned.size();
         requiredBlocks += (long)range.maximum() - range.minimum() + 1L - ownedCount;
         if (requiredBlocks > maxBlocks) {
            return FallbackBlocks.limited();
         }
      }

      LinkedHashSet<BlockPos> result = new LinkedHashSet<>(model.outline());
      for (Pixel pixel : sorted(model.domain())) {
         observer.checkCancelled();
         IntRange range = fallbackRange(model, pixel, snap);
         for (long value = range.minimum(); value <= range.maximum(); value++) {
            BlockPos block = model.restore(pixel, (int)value);
            if (!result.contains(block) && result.size() >= maxBlocks) {
               return FallbackBlocks.limited();
            }
            result.add(block);
         }
      }
      return FallbackBlocks.success(result);
   }

   private static IntRange fallbackRange(Model model, Pixel pixel, HeightSnap snap) {
      double real = model.realHeight(pixel);
      int height = safeInt((long)switch (snap) {
         case FLOOR -> Math.floor(real);
         case NEAREST -> Math.floor(real + 0.5);
         case CEIL -> Math.ceil(real);
      });
      int minimum = height;
      int maximum = height;
      NavigableSet<Integer> owned = model.ownedColumns().get(pixel);
      if (owned != null && !owned.isEmpty()) {
         minimum = Math.min(minimum, owned.first());
         maximum = Math.max(maximum, owned.last());
      }
      return new IntRange(minimum, maximum);
   }

}
