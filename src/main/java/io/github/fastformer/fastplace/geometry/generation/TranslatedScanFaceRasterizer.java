package io.github.fastformer.fastplace.geometry.generation;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import net.minecraft.core.BlockPos;

/** Builds a periodic micro-tiled face while preserving four exact nested-Bresenham edges. */
final class TranslatedScanFaceRasterizer {
   private static final int CANDIDATE_BAND = 2;
   private static final int MAX_CUT_COLUMNS = 8_192;
   private static final int MAX_CONNECTION_COLUMNS = 1_024;
   private static final long MAX_CUT_CELLS = 65_536L;
   private static final long MAX_RAW_PAIRS = 4_000_000L;
   private static final long MIN_RAW_PAIR_BUDGET = 16_384L;
   private static final long RAW_PAIR_BUDGET_MULTIPLIER = 8L;
   private static final long MAX_DOMAIN_SCAN = 4_000_000L;
   private static final long INFINITE_CAPACITY = Long.MAX_VALUE / 16L;
   private static final Comparator<Pixel> PIXEL_ORDER = Comparator
      .comparingInt(Pixel::u)
      .thenComparingInt(Pixel::v);

   private TranslatedScanFaceRasterizer() {
   }

   static BresenhamFaceSweep.Result generate(
      ProjectedBresenhamFace.Frame frame,
      LineTieBias tieBias
   ) {
      return attempt(frame, tieBias, Integer.MAX_VALUE, BlockGenerationObserver.NONE).result();
   }

   static BresenhamFaceSweep.Result generate(
      ProjectedBresenhamFace.Frame frame,
      LineTieBias tieBias,
      BlockGenerationObserver observer
   ) {
      return attempt(frame, tieBias, Integer.MAX_VALUE, observer).result();
   }

   static BresenhamFaceSweep.Attempt attempt(
      ProjectedBresenhamFace.Frame frame,
      LineTieBias tieBias,
      int maxBlocks,
      BlockGenerationObserver observer
   ) {
      return attempt(frame, tieBias, maxBlocks, observer, false);
   }

   static BresenhamFaceSweep.Attempt thinAttempt(
      ProjectedBresenhamFace.Frame frame,
      LineTieBias tieBias,
      int maxBlocks,
      BlockGenerationObserver observer
   ) {
      if (maxBlocks <= 0) {
         return BresenhamFaceSweep.Attempt.failed(BresenhamFaceSweep.Status.LIMIT_EXCEEDED);
      }
      return analyticHeightBandAttempt(
         frame,
         tieBias,
         maxBlocks,
         observer == null ? BlockGenerationObserver.NONE : observer
      );
   }

   static BresenhamFaceSweep.Attempt forceEmergencyFallbackForTesting(
      ProjectedBresenhamFace.Frame frame,
      LineTieBias tieBias,
      int maxBlocks
   ) {
      return emergencyFallback(frame, tieBias, maxBlocks, BlockGenerationObserver.NONE);
   }

   static BresenhamFaceSweep.Attempt emergencyFallback(
      ProjectedBresenhamFace.Frame frame,
      LineTieBias tieBias,
      int maxBlocks,
      BlockGenerationObserver observer
   ) {
      return attempt(frame, tieBias, maxBlocks, observer, true);
   }

   static Set<BlockPos> scanConvexDomainForTesting(List<BlockPos> polygon, int maxBlocks) {
      List<Pixel> projected = polygon.stream()
         .map(position -> new Pixel(position.getX(), position.getY()))
         .toList();
      Set<Pixel> result = Model.scanConvexDomain(projected, maxBlocks, BlockGenerationObserver.NONE);
      return testBlocks(result);
   }

   static Set<BlockPos> completeProjectedDomainForTesting(Set<BlockPos> source, int maxBlocks) {
      LinkedHashSet<Pixel> projected = new LinkedHashSet<>();
      source.forEach(position -> projected.add(new Pixel(position.getX(), position.getY())));
      return testBlocks(Model.completeProjectedDomain(projected, maxBlocks, BlockGenerationObserver.NONE));
   }

   static boolean insideConvexForTesting(BlockPos point, List<BlockPos> polygon) {
      Pixel projectedPoint = new Pixel(point.getX(), point.getY());
      List<Pixel> projectedPolygon = polygon.stream()
         .map(position -> new Pixel(position.getX(), position.getY()))
         .toList();
      return Model.insideConvex(projectedPoint, projectedPolygon);
   }

   static Set<BlockPos> fillMicroTileForTesting(
      ProjectedBresenhamFace.Frame frame,
      List<BlockPos> corners
   ) {
      if (frame == null || corners.size() != 4) {
         return Set.of();
      }
      Model coordinates = new Model(frame, Set.of(), Set.of(), Set.of(), Map.of(), Map.of(), Set.of());
      LinkedHashSet<BlockPos> result = new LinkedHashSet<>();
      if (!fillMicroTile(
         coordinates,
         List.copyOf(corners),
         result,
         64,
         BlockGenerationObserver.NONE
      )) {
         return Set.of();
      }
      return Collections.unmodifiableSet(result);
   }

   private static Set<BlockPos> testBlocks(Set<Pixel> source) {
      if (source == null) {
         return Set.of();
      }
      LinkedHashSet<BlockPos> result = new LinkedHashSet<>();
      source.forEach(pixel -> result.add(new BlockPos(pixel.u(), pixel.v(), 0)));
      return Collections.unmodifiableSet(result);
   }

   private static BresenhamFaceSweep.Attempt attempt(
      ProjectedBresenhamFace.Frame frame,
      LineTieBias tieBias,
      int maxBlocks,
      BlockGenerationObserver observer,
      boolean emergencyOnly
   ) {
      if (maxBlocks <= 0) {
         return BresenhamFaceSweep.Attempt.failed(BresenhamFaceSweep.Status.LIMIT_EXCEEDED);
      }
      BlockGenerationObserver effectiveObserver = observer == null ? BlockGenerationObserver.NONE : observer;
      if (!emergencyOnly) {
         BresenhamFaceSweep.Attempt periodic = periodicMicroTileAttempt(
            frame,
            tieBias,
            maxBlocks,
            effectiveObserver
         );
         if (periodic.status() == BresenhamFaceSweep.Status.SUCCESS) {
            return periodic;
         }
         BresenhamFaceSweep.Attempt coordinated = coordinatedSixConnectedAttempt(
            frame,
            tieBias,
            maxBlocks,
            effectiveObserver
         );
         if (coordinated.status() == BresenhamFaceSweep.Status.SUCCESS) {
            return coordinated;
         }
         BresenhamFaceSweep.Attempt analytic = analyticHeightBandAttempt(frame, tieBias, maxBlocks, effectiveObserver);
         if (analytic.status() == BresenhamFaceSweep.Status.SUCCESS) {
            return analytic;
         }
         return BresenhamFaceSweep.Attempt.failed(
            periodic.status() == BresenhamFaceSweep.Status.LIMIT_EXCEEDED
                  || coordinated.status() == BresenhamFaceSweep.Status.LIMIT_EXCEEDED
                  || analytic.status() == BresenhamFaceSweep.Status.LIMIT_EXCEEDED
               ? BresenhamFaceSweep.Status.LIMIT_EXCEEDED
               : analytic.status()
         );
      }
      ModelBuild built = Model.create(frame, tieBias, maxBlocks, effectiveObserver);
      if (emergencyOnly && built.model() == null
         && built.status() == BresenhamFaceSweep.Status.NO_VALID_CANDIDATE) {
         built = Model.create(frame, tieBias, maxBlocks, effectiveObserver, false);
      }
      if (built.model() == null) {
         return BresenhamFaceSweep.Attempt.failed(built.status());
      }
      Model model = built.model();
      emergencyOnly |= built.analyticOnly();

      FallbackBlocks floor = graphFallback(model, HeightSnap.FLOOR, maxBlocks, effectiveObserver);
      FallbackBlocks ceil = graphFallback(model, HeightSnap.CEIL, maxBlocks, effectiveObserver);
      if (emergencyOnly) {
         Set<BlockPos> selected = !floor.blocks().isEmpty() ? floor.blocks() : ceil.blocks();
         if (selected.isEmpty()) {
            BresenhamFaceSweep.Status status = floor.limitExceeded() || ceil.limitExceeded()
               ? BresenhamFaceSweep.Status.LIMIT_EXCEEDED
               : BresenhamFaceSweep.Status.NO_VALID_CANDIDATE;
            return BresenhamFaceSweep.Attempt.failed(status);
         }
         return BresenhamFaceSweep.Attempt.success(
            new BresenhamFaceSweep.Result(
               Collections.unmodifiableSet(new LinkedHashSet<>(selected)),
               model.outline()
            )
         );
      }

      Set<BlockPos> ownedConnectors = ownedSixConnectors(model, tieBias, effectiveObserver);
      Candidate best = null;
      boolean candidateLimitExceeded = floor.limitExceeded() || ceil.limitExceeded();
      if (model.domain().size() <= MAX_CUT_COLUMNS) {
         Set<BlockPos> cut = withConnectors(
            minimumSeparator(model, effectiveObserver),
            ownedConnectors,
            maxBlocks
         );
         candidateLimitExceeded |= cut.size() > maxBlocks;
         best = choose(
            best,
            model,
            "translated-cut",
            cut,
            1,
            maxBlocks,
            effectiveObserver
         );
      }
      Set<BlockPos> preferred = withConnectors(
         materializePreferred(model, maxBlocks, effectiveObserver),
         ownedConnectors,
         maxBlocks
      );
      candidateLimitExceeded |= preferred.isEmpty();
      best = choose(
         best,
         model,
         "translated-filled",
         preferred,
         0,
         maxBlocks,
         effectiveObserver
      );
      best = choose(best, model, "floor-fallback", withConnectors(floor.blocks(), ownedConnectors, maxBlocks), 2, maxBlocks, effectiveObserver);
      best = choose(best, model, "ceil-fallback", withConnectors(ceil.blocks(), ownedConnectors, maxBlocks), 3, maxBlocks, effectiveObserver);
      if (best == null) {
         return BresenhamFaceSweep.Attempt.failed(candidateLimitExceeded
            ? BresenhamFaceSweep.Status.LIMIT_EXCEEDED
            : BresenhamFaceSweep.Status.NO_VALID_CANDIDATE);
      }
      return BresenhamFaceSweep.Attempt.success(
         new BresenhamFaceSweep.Result(
            Collections.unmodifiableSet(new LinkedHashSet<>(best.blocks())),
            model.outline()
         )
      );
   }

   /**
    * Treats the two owned edge paths as periodic mechanical words.  Their
    * prefix sums form a discrete parameter grid; each adjacent pair of steps
    * defines one tiny parallelogram which is filled in the canonical primary
    * projection.  Consequently a repeated pair of edge-step events always
    * receives exactly the same local patch instead of an unrelated hole fix.
    */
   private static BresenhamFaceSweep.Attempt periodicMicroTileAttempt(
      ProjectedBresenhamFace.Frame frame,
      LineTieBias tieBias,
      int maxBlocks,
      BlockGenerationObserver observer
   ) {
      if (frame == null || frame.vertices().size() != 4) {
         return BresenhamFaceSweep.Attempt.failed(BresenhamFaceSweep.Status.NOT_APPLICABLE);
      }
      List<BlockPos> vertices = frame.vertices().stream().map(frame::restore).toList();
      BlockPos p00 = vertices.get(0);
      BlockPos p10 = vertices.get(1);
      BlockPos p11 = vertices.get(2);
      BlockPos p01 = vertices.get(3);
      if (!Model.isParallelogram(p00, p10, p11, p01)) {
         return BresenhamFaceSweep.Attempt.failed(BresenhamFaceSweep.Status.NOT_APPLICABLE);
      }

      long estimateU = LineGenerator.estimateBlocks(p00, p10);
      long estimateV = LineGenerator.estimateBlocks(p00, p01);
      if (estimateU > maxBlocks || estimateV > maxBlocks) {
         return BresenhamFaceSweep.Attempt.failed(BresenhamFaceSweep.Status.LIMIT_EXCEEDED);
      }
      long cellsU = estimateU - 1L;
      long cellsV = estimateV - 1L;
      if (cellsU <= 0L || cellsV <= 0L) {
         return BresenhamFaceSweep.Attempt.failed(BresenhamFaceSweep.Status.NOT_APPLICABLE);
      }
      if (cellsU > MAX_RAW_PAIRS / cellsV) {
         return BresenhamFaceSweep.Attempt.failed(BresenhamFaceSweep.Status.NO_VALID_CANDIDATE);
      }
      List<BlockPos> edgeU = orientedPath(p00, p10, tieBias);
      List<BlockPos> edgeV = orientedPath(p00, p01, tieBias);

      LinkedHashSet<BlockPos> outline = new LinkedHashSet<>();
      for (int edge = 0; edge < vertices.size(); edge++) {
         observer.checkCancelled();
         outline.addAll(LineGenerator.path(
            vertices.get(edge),
            vertices.get((edge + 1) % vertices.size()),
            tieBias
         ));
      }
      if (outline.size() > maxBlocks) {
         return BresenhamFaceSweep.Attempt.failed(BresenhamFaceSweep.Status.LIMIT_EXCEEDED);
      }

      ModelBuild coordinateBuild = Model.createAnalytic(frame, tieBias, maxBlocks, observer, true);
      if (coordinateBuild.model() == null
         && coordinateBuild.status() == BresenhamFaceSweep.Status.NO_VALID_CANDIDATE) {
         coordinateBuild = Model.createAnalytic(frame, tieBias, maxBlocks, observer, false);
      }
      if (coordinateBuild.model() == null) {
         return BresenhamFaceSweep.Attempt.failed(coordinateBuild.status());
      }
      Model coordinates = coordinateBuild.model();
      LinkedHashSet<BlockPos> result = new LinkedHashSet<>(outline);
      for (int j = 0; j + 1 < edgeV.size(); j++) {
         observer.checkCancelled();
         for (int i = 0; i + 1 < edgeU.size(); i++) {
            if ((i & 1023) == 0) {
               observer.checkCancelled();
            }
            BlockPos first = translatedGridPoint(p00, edgeU.get(i), edgeV.get(j));
            BlockPos second = translatedGridPoint(p00, edgeU.get(i + 1), edgeV.get(j));
            BlockPos fourth = translatedGridPoint(p00, edgeU.get(i), edgeV.get(j + 1));
            BlockPos third = translatedGridPoint(p00, edgeU.get(i + 1), edgeV.get(j + 1));
            if (!fillMicroTile(
               coordinates,
               List.of(first, second, third, fourth),
               result,
               maxBlocks,
               observer
            )) {
               return BresenhamFaceSweep.Attempt.failed(BresenhamFaceSweep.Status.LIMIT_EXCEEDED);
            }
         }
      }
      LinkedHashSet<BlockPos> beforeSections = new LinkedHashSet<>(result);
      LinkedHashSet<BlockPos> sectionAdditions = new LinkedHashSet<>();
      int workspaceLimit = (int)Math.min(
         Integer.MAX_VALUE,
         (long)maxBlocks + Math.min(512L, Math.max(16L, maxBlocks / 8L))
      );
      BresenhamFaceSweep.Status sectionStatus = fillEqualHeightSections(
         coordinates,
         result,
         sectionAdditions,
         workspaceLimit,
         observer
      );
      if (sectionStatus != BresenhamFaceSweep.Status.SUCCESS) {
         return BresenhamFaceSweep.Attempt.failed(sectionStatus);
      }
      Set<BlockPos> connectionSupports = minimumSixConnectionSupports(coordinates, result, observer);
      if (connectionSupports == null) {
         return BresenhamFaceSweep.Attempt.failed(BresenhamFaceSweep.Status.NO_VALID_CANDIDATE);
      }
      for (BlockPos support : connectionSupports) {
         if (!addWithinLimit(result, support, workspaceLimit)) {
            return BresenhamFaceSweep.Attempt.failed(BresenhamFaceSweep.Status.LIMIT_EXCEEDED);
         }
      }
      trimReplacedBlocks(
         coordinates,
         result,
         beforeSections,
         sectionAdditions,
         outline,
         observer
      );
      if (result.size() > maxBlocks) {
         return BresenhamFaceSweep.Attempt.failed(BresenhamFaceSweep.Status.LIMIT_EXCEEDED);
      }
      if (!analyze(coordinates, result, observer).valid()) {
         return BresenhamFaceSweep.Attempt.failed(BresenhamFaceSweep.Status.NO_VALID_CANDIDATE);
      }
      return BresenhamFaceSweep.Attempt.success(new BresenhamFaceSweep.Result(
         Collections.unmodifiableSet(result),
         Collections.unmodifiableSet(outline)
      ));
   }

   private static List<BlockPos> orientedPath(BlockPos from, BlockPos to, LineTieBias tieBias) {
      ArrayList<BlockPos> result = new ArrayList<>(LineGenerator.path(from, to, tieBias));
      if (!result.isEmpty() && !result.getFirst().equals(from)) {
         Collections.reverse(result);
      }
      return List.copyOf(result);
   }

   private static BlockPos translatedGridPoint(BlockPos origin, BlockPos alongU, BlockPos alongV) {
      return new BlockPos(
         safeInt((long)alongU.getX() + alongV.getX() - origin.getX()),
         safeInt((long)alongU.getY() + alongV.getY() - origin.getY()),
         safeInt((long)alongU.getZ() + alongV.getZ() - origin.getZ())
      );
   }

   private static boolean fillMicroTile(
      Model coordinates,
      List<BlockPos> corners,
      Set<BlockPos> result,
      int maxBlocks,
      BlockGenerationObserver observer
   ) {
      LinkedHashSet<BlockPos> tile = new LinkedHashSet<>(corners);
      Pixel p00 = coordinates.project(corners.get(0));
      Pixel p10 = coordinates.project(corners.get(1));
      Pixel p11 = coordinates.project(corners.get(2));
      Pixel p01 = coordinates.project(corners.get(3));
      long duU = (long)p10.u() - p00.u();
      long duV = (long)p10.v() - p00.v();
      long dvU = (long)p01.u() - p00.u();
      long dvV = (long)p01.v() - p00.v();
      long determinant = duU * dvV - duV * dvU;
      if (determinant != 0L) {
         LinkedHashSet<Pixel> domain = Model.scanConvexDomain(
            List.of(p00, p10, p11, p01),
            16,
            observer
         );
         if (domain != null) {
            long w00 = coordinates.height(corners.get(0));
            long duW = (long)coordinates.height(corners.get(1)) - w00;
            long dvW = (long)coordinates.height(corners.get(3)) - w00;
            long normalU = duV * dvW - duW * dvV;
            long normalV = duW * dvU - duU * dvW;
            long normalW = determinant;
            for (Pixel pixel : sorted(domain)) {
               observer.checkCancelled();
               long numerator = normalW * w00
                  - normalU * ((long)pixel.u() - p00.u())
                  - normalV * ((long)pixel.v() - p00.v());
               for (long height : nearestQuotients(numerator, normalW)) {
                  tile.add(coordinates.restore(pixel, safeInt(height)));
               }
            }
         }
      } else {
         Map<Pixel, NavigableSet<Integer>> degenerateColumns = columns(coordinates, tile);
         for (Map.Entry<Pixel, NavigableSet<Integer>> entry : degenerateColumns.entrySet()) {
            NavigableSet<Integer> heights = entry.getValue();
            for (int height = heights.first(); height <= heights.last(); height++) {
               tile.add(coordinates.restore(entry.getKey(), height));
            }
         }
      }

      for (BlockPos block : tile) {
         if (!addWithinLimit(result, block, maxBlocks)) {
            return false;
         }
      }
      return true;
   }

   /** Closes and fills every equal-height fragment set in its own two-dimensional slice. */
   private static BresenhamFaceSweep.Status fillEqualHeightSections(
      Model coordinates,
      Set<BlockPos> result,
      Set<BlockPos> addedBySections,
      int maxBlocks,
      BlockGenerationObserver observer
   ) {
      TreeMap<Integer, LinkedHashSet<Pixel>> layers = new TreeMap<>();
      for (BlockPos block : result) {
         layers.computeIfAbsent(coordinates.height(block), ignored -> new LinkedHashSet<>())
            .add(coordinates.project(block));
      }
      LinkedHashSet<BlockPos> additions = new LinkedHashSet<>();
      long scanned = 0L;
      for (Map.Entry<Integer, LinkedHashSet<Pixel>> layer : layers.entrySet()) {
         observer.checkCancelled();
         int layerHeight = layer.getKey();
         List<Pixel> sectionFragments = layer.getValue().stream()
            .filter(coordinates.domain()::contains)
            .toList();
         List<Pixel> hull = convexHull(sectionFragments);
         if (hull.size() == 2) {
            BlockPos first = coordinates.restore(hull.get(0), layerHeight);
            BlockPos second = coordinates.restore(hull.get(1), layerHeight);
            for (BlockPos block : LineGenerator.path(first, second)) {
               Pixel pixel = coordinates.project(block);
               if (coordinates.domain().contains(pixel)) {
                  additions.add(block);
               }
            }
            continue;
         }
         if (hull.size() < 3) {
            continue;
         }
         int minimumU = hull.stream().mapToInt(Pixel::u).min().orElseThrow();
         int maximumU = hull.stream().mapToInt(Pixel::u).max().orElseThrow();
         int minimumV = hull.stream().mapToInt(Pixel::v).min().orElseThrow();
         int maximumV = hull.stream().mapToInt(Pixel::v).max().orElseThrow();
         long width = (long)maximumU - minimumU + 1L;
         long height = (long)maximumV - minimumV + 1L;
         if (width <= 0L || height <= 0L || width > (MAX_DOMAIN_SCAN - scanned) / height) {
            return BresenhamFaceSweep.Status.NO_VALID_CANDIDATE;
         }
         scanned += width * height;
         for (int v = minimumV; v <= maximumV; v++) {
            for (int u = minimumU; u <= maximumU; u++) {
               if (((u - minimumU) & 1023) == 0) {
                  observer.checkCancelled();
               }
               Pixel pixel = new Pixel(u, v);
               if (coordinates.domain().contains(pixel) && Model.insideConvex(pixel, hull)) {
                  additions.add(coordinates.restore(pixel, layerHeight));
               }
               if (u == Integer.MAX_VALUE) {
                  break;
               }
            }
            if (v == Integer.MAX_VALUE) {
               break;
            }
         }
      }
      for (BlockPos block : additions) {
         boolean added = !result.contains(block);
         if (!addWithinLimit(result, block, maxBlocks)) {
            return BresenhamFaceSweep.Status.LIMIT_EXCEEDED;
         }
         if (added) {
            addedBySections.add(block);
         }
      }
      return BresenhamFaceSweep.Status.SUCCESS;
   }

   private static void trimReplacedBlocks(
      Model model,
      LinkedHashSet<BlockPos> result,
      Set<BlockPos> beforeSections,
      Set<BlockPos> sectionAdditions,
      Set<BlockPos> owned,
      BlockGenerationObserver observer
   ) {
      Map<Pixel, NavigableSet<Integer>> addedColumns = columns(model, sectionAdditions);
      LinkedHashSet<BlockPos> lowerCandidates = new LinkedHashSet<>();
      LinkedHashSet<BlockPos> upperCandidates = new LinkedHashSet<>();
      for (BlockPos block : beforeSections) {
         if (owned.contains(block)) {
            continue;
         }
         Pixel pixel = model.project(block);
         int height = model.height(block);
         NavigableSet<Integer> additions = addedColumns.get(pixel);
         if (additions == null) {
            continue;
         }
         double oldResidual = Math.abs(model.realHeight(pixel) - height);
         for (int addedHeight : additions) {
            if (Math.abs(model.realHeight(pixel) - addedHeight) + 1.0E-9 >= oldResidual) {
               continue;
            }
            if ((long)addedHeight == (long)height + 1L) {
               lowerCandidates.add(block);
            } else if ((long)addedHeight == (long)height - 1L) {
               upperCandidates.add(block);
            }
         }
      }
      observer.checkCancelled();
      LinkedHashSet<BlockPos> union = new LinkedHashSet<>(lowerCandidates);
      union.addAll(upperCandidates);
      Set<BlockPos> selected = validRemoval(model, result, union, observer)
         ? union
         : chooseSymmetricRemoval(model, result, lowerCandidates, upperCandidates, observer);
      result.removeAll(selected);
   }

   private static Set<BlockPos> chooseSymmetricRemoval(
      Model model,
      Set<BlockPos> result,
      Set<BlockPos> lower,
      Set<BlockPos> upper,
      BlockGenerationObserver observer
   ) {
      boolean lowerValid = validRemoval(model, result, lower, observer);
      boolean upperValid = validRemoval(model, result, upper, observer);
      if (lowerValid != upperValid) {
         return lowerValid ? lower : upper;
      }
      if (!lowerValid) {
         return Set.of();
      }
      int sizeComparison = Integer.compare(lower.size(), upper.size());
      if (sizeComparison != 0) {
         return sizeComparison > 0 ? lower : upper;
      }
      double lowerResidual = removalResidual(model, lower);
      double upperResidual = removalResidual(model, upper);
      int residualComparison = Double.compare(lowerResidual, upperResidual);
      return residualComparison > 0 ? lower : residualComparison < 0 ? upper : Set.of();
   }

   private static boolean validRemoval(
      Model model,
      Set<BlockPos> result,
      Set<BlockPos> removal,
      BlockGenerationObserver observer
   ) {
      if (removal.isEmpty()) {
         return false;
      }
      LinkedHashSet<BlockPos> candidate = new LinkedHashSet<>(result);
      candidate.removeAll(removal);
      return analyze(model, candidate, observer).valid();
   }

   private static double removalResidual(Model model, Set<BlockPos> removal) {
      double result = 0.0;
      for (BlockPos block : removal) {
         Pixel pixel = model.project(block);
         result += Math.abs(model.realHeight(pixel) - model.height(block));
      }
      return result;
   }

   private static List<Pixel> convexHull(Collection<Pixel> source) {
      List<Pixel> points = source.stream().distinct().sorted(PIXEL_ORDER).toList();
      if (points.size() <= 2) {
         return points;
      }
      ArrayList<Pixel> lower = new ArrayList<>();
      for (Pixel point : points) {
         while (lower.size() >= 2
            && orientation(lower.get(lower.size() - 2), lower.getLast(), point) <= 0) {
            lower.removeLast();
         }
         lower.add(point);
      }
      ArrayList<Pixel> upper = new ArrayList<>();
      for (int index = points.size() - 1; index >= 0; index--) {
         Pixel point = points.get(index);
         while (upper.size() >= 2
            && orientation(upper.get(upper.size() - 2), upper.getLast(), point) <= 0) {
            upper.removeLast();
         }
         upper.add(point);
      }
      lower.removeLast();
      upper.removeLast();
      lower.addAll(upper);
      return List.copyOf(lower);
   }

   private static int orientation(Pixel first, Pixel second, Pixel third) {
      BigInteger ax = BigInteger.valueOf((long)second.u() - first.u());
      BigInteger ay = BigInteger.valueOf((long)second.v() - first.v());
      BigInteger bx = BigInteger.valueOf((long)third.u() - first.u());
      BigInteger by = BigInteger.valueOf((long)third.v() - first.v());
      return ax.multiply(by).subtract(ay.multiply(bx)).signum();
   }

   private static boolean addWithinLimit(Set<BlockPos> result, BlockPos block, int maxBlocks) {
      return result.contains(block) || result.size() < maxBlocks && result.add(block);
   }

   private static long[] nearestQuotients(long numerator, long denominator) {
      if (denominator < 0L) {
         numerator = -numerator;
         denominator = -denominator;
      }
      long floor = Math.floorDiv(numerator, denominator);
      long remainder = Math.floorMod(numerator, denominator);
      long doubled = remainder * 2L;
      if (doubled == denominator) {
         // A single choice at an exact half is reflected to the other choice
         // by a signed-axis transform.  Keeping both is the smallest local
         // supercover and avoids hiding a world-axis preference in the tile.
         return new long[]{floor, floor + 1L};
      }
      return new long[]{doubled > denominator ? floor + 1L : floor};
   }

   private static Set<BlockPos> withConnectors(
      Set<BlockPos> blocks,
      Set<BlockPos> connectors,
      int maxBlocks
   ) {
      if (blocks.isEmpty()) {
         return Set.of();
      }
      LinkedHashSet<BlockPos> result = new LinkedHashSet<>(blocks);
      result.addAll(connectors);
      return result.size() <= maxBlocks ? result : Set.of();
   }

   private static Set<BlockPos> ownedSixConnectors(
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

   /**
    * Fills the analytic parallelogram as disjoint half-open height bands.
    *
    * A projected lattice point belongs to band h exactly when
    * h - 1/2 <= realHeight < h + 1/2.  Enumerating the projected domain and
    * snapping once is the exact integer form of clipping and filling those
    * bands.  Owned Bresenham edges are then unioned verbatim; they never grow
    * the analytic domain and no vertical span is invented between edge voxels.
    */
   private static BresenhamFaceSweep.Attempt analyticHeightBandAttempt(
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
      FallbackBlocks bands = materializeAnalyticHeightBands(model, maxBlocks, observer);
      if (bands.blocks().isEmpty()) {
         return BresenhamFaceSweep.Attempt.failed(
            bands.limitExceeded()
               ? BresenhamFaceSweep.Status.LIMIT_EXCEEDED
               : BresenhamFaceSweep.Status.NO_VALID_CANDIDATE
         );
      }
      return BresenhamFaceSweep.Attempt.success(
         new BresenhamFaceSweep.Result(
            Collections.unmodifiableSet(new LinkedHashSet<>(bands.blocks())),
            model.outline()
         )
      );
   }

   private static FallbackBlocks materializeAnalyticHeightBands(
      Model model,
      int maxBlocks,
      BlockGenerationObserver observer
   ) {
      LinkedHashSet<BlockPos> result = new LinkedHashSet<>(model.outline());
      if (result.size() > maxBlocks) {
         return FallbackBlocks.limited();
      }
      for (Pixel pixel : sorted(model.domain())) {
         observer.checkCancelled();
         // Every analytic column receives exactly one band voxel.  Owned edge
         // voxels may add a second boundary layer, but unlike the old fallback
         // we never fill an entire vertical span between unrelated edge hits.
         int height = safeInt((long)Math.floor(model.realHeight(pixel) + 0.5));
         BlockPos block = model.restore(pixel, height);
         if (!result.contains(block) && result.size() >= maxBlocks) {
            return FallbackBlocks.limited();
         }
         result.add(block);
      }
      return FallbackBlocks.success(result);
   }

   private static BresenhamFaceSweep.Attempt coordinatedSixConnectedAttempt(
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

   private static Set<BlockPos> minimumSixConnectionSupports(
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
      SupportUnion union = new SupportUnion(components.count());
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

   private static ComponentMap sixComponents(Set<BlockPos> blocks, BlockGenerationObserver observer) {
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

   private static List<BlockPos> sixNeighbors(BlockPos position) {
      return List.of(
         position.offset(1, 0, 0), position.offset(-1, 0, 0),
         position.offset(0, 1, 0), position.offset(0, -1, 0),
         position.offset(0, 0, 1), position.offset(0, 0, -1)
      );
   }

   private static Candidate choose(
      Candidate current,
      Model model,
      String name,
      Set<BlockPos> blocks,
      int priority,
      int maxBlocks,
      BlockGenerationObserver observer
   ) {
      if (blocks.isEmpty() || blocks.size() > maxBlocks) {
         return current;
      }
      Analysis analysis = analyze(model, blocks, observer);
      if (!analysis.valid()) {
         return current;
      }
      Candidate candidate = new Candidate(name, blocks, analysis, priority);
      return current == null || candidate.compareTo(current) < 0 ? candidate : current;
   }

   private static Set<BlockPos> minimumSeparator(Model model, BlockGenerationObserver observer) {
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
      graph.maximumFlow(source, sink, observer);
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

   private static Set<BlockPos> materializePreferred(
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

   private static FallbackBlocks graphFallback(
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

   private static Analysis analyze(
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

   private static Map<Pixel, NavigableSet<Integer>> columns(Model model, Set<BlockPos> blocks) {
      LinkedHashMap<Pixel, NavigableSet<Integer>> result = new LinkedHashMap<>();
      for (BlockPos block : blocks) {
         Pixel pixel = model.project(block);
         result.computeIfAbsent(pixel, ignored -> new TreeSet<>()).add(model.height(block));
      }
      return result;
   }

   private static List<Pixel> sorted(Set<Pixel> pixels) {
      return pixels.stream().sorted(PIXEL_ORDER).toList();
   }

   private static List<Pixel> neighbors(Pixel pixel) {
      return List.of(
         new Pixel(pixel.u() + 1, pixel.v()),
         new Pixel(pixel.u() - 1, pixel.v()),
         new Pixel(pixel.u(), pixel.v() + 1),
         new Pixel(pixel.u(), pixel.v() - 1)
      );
   }

   private static int safeInt(long value) {
      return value < Integer.MIN_VALUE ? Integer.MIN_VALUE : value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int)value;
   }

   private static BigInteger difference(int first, int second) {
      return BigInteger.valueOf(first).subtract(BigInteger.valueOf(second));
   }

   private static BigInteger floorDivide(BigInteger numerator, BigInteger positiveDenominator) {
      BigInteger[] divided = numerator.divideAndRemainder(positiveDenominator);
      return divided[1].signum() < 0 ? divided[0].subtract(BigInteger.ONE) : divided[0];
   }

   private static void addLatticeSegment(
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

   private static void addMissingRun(
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

   private static void unionOverlappingRuns(
      List<MissingRun> runs,
      RunComponents components,
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

   private record Pixel(int u, int v) {
      Pixel add(Pixel other) {
         return new Pixel(Math.addExact(this.u, other.u), Math.addExact(this.v, other.v));
      }
   }

   private record SeamScore(double maximumResidual, double totalResidual) implements Comparable<SeamScore> {
      @Override
      public int compareTo(SeamScore other) {
         int compared = Double.compare(this.maximumResidual, other.maximumResidual);
         return compared != 0 ? compared : Double.compare(this.totalResidual, other.totalResidual);
      }
   }

   private record LocalPoint(int u, int v, int w) implements Comparable<LocalPoint> {
      @Override
      public int compareTo(LocalPoint other) {
         int compared = Integer.compare(this.u, other.u);
         if (compared == 0) {
            compared = Integer.compare(this.v, other.v);
         }
         return compared != 0 ? compared : Integer.compare(this.w, other.w);
      }
   }

   private record ConnectorRoute(List<LocalPoint> points, double maximumResidual, double totalResidual)
      implements Comparable<ConnectorRoute> {
      static ConnectorRoute create(Model model, List<LocalPoint> points) {
         double maximum = 0.0;
         double total = 0.0;
         for (LocalPoint point : points) {
            double residual = Math.abs(
               model.frame().normal().x * point.u()
                  + model.frame().normal().y * point.v()
                  + model.frame().normal().z * point.w()
            );
            maximum = Math.max(maximum, residual);
            total += residual;
         }
         return new ConnectorRoute(List.copyOf(points), maximum, total);
      }

      @Override
      public int compareTo(ConnectorRoute other) {
         int compared = Double.compare(this.maximumResidual, other.maximumResidual);
         if (compared == 0) {
            compared = Double.compare(this.totalResidual, other.totalResidual);
         }
         if (compared != 0) {
            return compared;
         }
         int size = Math.min(this.points.size(), other.points.size());
         for (int index = 0; index < size; index++) {
            compared = this.points.get(index).compareTo(other.points.get(index));
            if (compared != 0) {
               return compared;
            }
         }
         return Integer.compare(this.points.size(), other.points.size());
      }
   }

   private record ComponentMap(Map<BlockPos, Integer> byBlock, int count) {
   }

   private record ConnectionSupport(
      BlockPos position,
      Set<Integer> components,
      int interiorPenalty,
      double residual,
      LocalPoint local
   ) implements Comparable<ConnectionSupport> {
      @Override
      public int compareTo(ConnectionSupport other) {
         int compared = Integer.compare(this.interiorPenalty, other.interiorPenalty);
         if (compared == 0) {
            compared = Double.compare(this.residual, other.residual);
         }
         return compared != 0 ? compared : this.local.compareTo(other.local);
      }
   }

   private record Cell(Pixel pixel, int height) {
   }

   private record BigPoint(BigInteger u, BigInteger v) {
      static BigPoint of(Pixel pixel) {
         return new BigPoint(BigInteger.valueOf(pixel.u()), BigInteger.valueOf(pixel.v()));
      }

      BigPoint add(BigPoint other) {
         return new BigPoint(this.u.add(other.u), this.v.add(other.v));
      }

      BigPoint subtract(BigPoint other) {
         return new BigPoint(this.u.subtract(other.u), this.v.subtract(other.v));
      }

      BigPoint scale(BigInteger amount) {
         return new BigPoint(this.u.multiply(amount), this.v.multiply(amount));
      }

      BigInteger cross(BigPoint other) {
         return this.u.multiply(other.v).subtract(this.v.multiply(other.u));
      }

      BigInteger coordinateGcd() {
         return this.u.abs().gcd(this.v.abs());
      }

      Pixel toPixel() {
         return new Pixel(this.u.intValueExact(), this.v.intValueExact());
      }
   }

   private record MissingRun(int v, long minimumU, long maximumU, boolean exterior) {
   }

   private record IntRange(int minimum, int maximum) {
   }

   private record FallbackBlocks(Set<BlockPos> blocks, boolean limitExceeded) {
      static FallbackBlocks success(Set<BlockPos> blocks) {
         return new FallbackBlocks(blocks, false);
      }

      static FallbackBlocks limited() {
         return new FallbackBlocks(Set.of(), true);
      }
   }

   private enum HeightSnap {
      FLOOR,
      NEAREST,
      CEIL
   }

   private record NodePair(int enter, int leave) {
   }

   private record Analysis(
      boolean valid,
      int maximumThickness,
      int maximumInteriorThickness,
      int support,
      int largestSupportComponent,
      int longestExtraContour,
      int basinCells,
      int strictExtrema,
      int blocks
   ) {
   }

   private record Candidate(String name, Set<BlockPos> blocks, Analysis analysis, int priority)
      implements Comparable<Candidate> {
      @Override
      public int compareTo(Candidate other) {
         int compared = Integer.compare(this.analysis.basinCells(), other.analysis.basinCells());
         if (compared == 0) {
            compared = Integer.compare(this.analysis.strictExtrema(), other.analysis.strictExtrema());
         }
         if (compared == 0) {
            compared = Integer.compare(
            this.analysis.largestSupportComponent(),
            other.analysis.largestSupportComponent()
            );
         }
         if (compared == 0) {
            compared = Integer.compare(this.analysis.longestExtraContour(), other.analysis.longestExtraContour());
         }
         if (compared == 0) {
            compared = Integer.compare(this.analysis.support(), other.analysis.support());
         }
         if (compared == 0) {
            compared = Integer.compare(this.analysis.blocks(), other.analysis.blocks());
         }
         return compared != 0 ? compared : Integer.compare(this.priority, other.priority);
      }
   }

   private record ModelBuild(
      BresenhamFaceSweep.Status status,
      Model model,
      boolean analyticOnly
   ) {
      static ModelBuild success(Model model, boolean analyticOnly) {
         return new ModelBuild(BresenhamFaceSweep.Status.SUCCESS, model, analyticOnly);
      }

      static ModelBuild failed(BresenhamFaceSweep.Status status) {
         return new ModelBuild(status, null, false);
      }
   }

   private record Model(
      ProjectedBresenhamFace.Frame frame,
      Set<BlockPos> raw,
      Set<BlockPos> outline,
      Set<Pixel> domain,
      Map<Pixel, NavigableSet<Integer>> preferred,
      Map<Pixel, NavigableSet<Integer>> ownedColumns,
      Set<Pixel> boundary
   ) {
      static ModelBuild create(
         ProjectedBresenhamFace.Frame frame,
         LineTieBias tieBias,
         int maxBlocks,
         BlockGenerationObserver observer
      ) {
         return create(frame, tieBias, maxBlocks, observer, true, false);
      }

      static ModelBuild createAnalytic(
         ProjectedBresenhamFace.Frame frame,
         LineTieBias tieBias,
         int maxBlocks,
         BlockGenerationObserver observer,
         boolean requireConnectedDomain
      ) {
         return create(frame, tieBias, maxBlocks, observer, requireConnectedDomain, true);
      }

      private static ModelBuild create(
         ProjectedBresenhamFace.Frame frame,
         LineTieBias tieBias,
         int maxBlocks,
         BlockGenerationObserver observer,
         boolean requireConnectedDomain
      ) {
         return create(frame, tieBias, maxBlocks, observer, requireConnectedDomain, false);
      }

      private static ModelBuild create(
         ProjectedBresenhamFace.Frame frame,
         LineTieBias tieBias,
         int maxBlocks,
         BlockGenerationObserver observer,
         boolean requireConnectedDomain,
         boolean forceAnalyticOnly
      ) {
         if (frame == null || frame.vertices().size() != 4) {
            return ModelBuild.failed(BresenhamFaceSweep.Status.NOT_APPLICABLE);
         }
         List<BlockPos> vertices = frame.vertices().stream().map(frame::restore).toList();
         BlockPos origin = vertices.getFirst();
         BlockPos p10 = vertices.get(1);
         BlockPos p11 = vertices.get(2);
         BlockPos p01 = vertices.get(3);
         if (!isParallelogram(origin, p10, p11, p01)) {
            return ModelBuild.failed(BresenhamFaceSweep.Status.NOT_APPLICABLE);
         }

         Model coordinateSystem = new Model(frame, Set.of(), Set.of(), Set.of(), Map.of(), Map.of(), Set.of());
         List<Pixel> polygon = vertices.stream().map(coordinateSystem::project).toList();
         int minimumU = polygon.stream().mapToInt(Pixel::u).min().orElseThrow();
         int maximumU = polygon.stream().mapToInt(Pixel::u).max().orElseThrow();
         int minimumV = polygon.stream().mapToInt(Pixel::v).min().orElseThrow();
         int maximumV = polygon.stream().mapToInt(Pixel::v).max().orElseThrow();
         long width = (long)maximumU - minimumU + 1L;
         long height = (long)maximumV - minimumV + 1L;
         boolean expansiveDomain = width <= 0L
            || height <= 0L
            || width > MAX_DOMAIN_SCAN / height;

         long lineUCount = LineGenerator.estimateBlocks(origin, p10);
         long lineVCount = LineGenerator.estimateBlocks(origin, p01);
         long budgetScaledPairs = Math.min(
            MAX_RAW_PAIRS,
            Math.max(MIN_RAW_PAIR_BUDGET, (long)maxBlocks * RAW_PAIR_BUDGET_MULTIPLIER)
         );
         boolean analyticOnly = forceAnalyticOnly
            || expansiveDomain
            || lineUCount <= 0L
            || lineVCount <= 0L
            || lineUCount > budgetScaledPairs
            || lineVCount > budgetScaledPairs
            || lineUCount > budgetScaledPairs / lineVCount;
         for (int index = 0; index < 4; index++) {
            long edgeBlocks = LineGenerator.estimateBlocks(
               vertices.get(index),
               vertices.get((index + 1) % 4)
            );
            if (edgeBlocks > maxBlocks) {
               return ModelBuild.failed(BresenhamFaceSweep.Status.LIMIT_EXCEEDED);
            }
         }

         LinkedHashSet<BlockPos> outline = new LinkedHashSet<>();
         for (int index = 0; index < 4; index++) {
            observer.checkCancelled();
            outline.addAll(LineGenerator.path(vertices.get(index), vertices.get((index + 1) % 4), tieBias));
         }
         if (outline.size() > maxBlocks) {
            return ModelBuild.failed(BresenhamFaceSweep.Status.LIMIT_EXCEEDED);
         }

         LinkedHashSet<Pixel> domain = scanConvexDomain(polygon, maxBlocks, observer);
         if (domain == null) {
            return ModelBuild.failed(BresenhamFaceSweep.Status.LIMIT_EXCEEDED);
         }

         LinkedHashSet<BlockPos> raw = new LinkedHashSet<>();
         if (!analyticOnly) {
            List<BlockPos> lineU = LineGenerator.path(origin, p10, tieBias);
            List<BlockPos> lineV = LineGenerator.path(origin, p01, tieBias);
            for (BlockPos first : lineU) {
               observer.checkCancelled();
               long dx = (long)first.getX() - origin.getX();
               long dy = (long)first.getY() - origin.getY();
               long dz = (long)first.getZ() - origin.getZ();
               for (BlockPos second : lineV) {
                  raw.add(new BlockPos(
                     safeInt(dx + second.getX()),
                     safeInt(dy + second.getY()),
                     safeInt(dz + second.getZ())
                  ));
               }
            }
         }

         Model shell = new Model(frame, raw, outline, Set.of(), Map.of(), Map.of(), Set.of());
         Map<Pixel, NavigableSet<Integer>> rawColumns = columns(shell, raw);
         Map<Pixel, NavigableSet<Integer>> ownedColumns = columns(shell, outline);
         if (!forceAnalyticOnly) {
            domain.addAll(rawColumns.keySet());
            domain.addAll(ownedColumns.keySet());
         }
         domain = bridgeDiagonalContacts(domain, polygon);
         if (!forceAnalyticOnly) {
            domain = completeProjectedDomain(domain, maxBlocks, observer);
            if (domain == null) {
               return ModelBuild.failed(BresenhamFaceSweep.Status.LIMIT_EXCEEDED);
            }
         }
         if (requireConnectedDomain && !isFourConnected(domain)) {
            return ModelBuild.failed(BresenhamFaceSweep.Status.NO_VALID_CANDIDATE);
         }
         if (domain.size() > maxBlocks) {
            return ModelBuild.failed(BresenhamFaceSweep.Status.LIMIT_EXCEEDED);
         }
         long mandatoryBlocks = domain.size();
         for (NavigableSet<Integer> heights : ownedColumns.values()) {
            if (!heights.isEmpty()) {
               long ownedSpan = (long)heights.last() - heights.first() + 1L;
               mandatoryBlocks += Math.max(0L, ownedSpan - 1L);
            }
            if (mandatoryBlocks > maxBlocks) {
               return ModelBuild.failed(BresenhamFaceSweep.Status.LIMIT_EXCEEDED);
            }
         }

         Model geometry = new Model(frame, raw, outline, domain, Map.of(), ownedColumns, Set.of());
         int radiusLimit = safeInt(Math.max(width, height) + 2L);
         LinkedHashMap<Pixel, NavigableSet<Integer>> preferred = new LinkedHashMap<>();
         for (Pixel pixel : sorted(domain)) {
            observer.checkCancelled();
            NavigableSet<Integer> known = rawColumns.get(pixel);
            if (known != null && !known.isEmpty()) {
               preferred.put(pixel, new TreeSet<>(known));
            } else if (analyticOnly) {
               preferred.put(pixel, analyticPreference(geometry, pixel));
            } else {
               preferred.put(pixel, interpolate(geometry, pixel, rawColumns, radiusLimit));
            }
         }
         LinkedHashSet<Pixel> boundary = new LinkedHashSet<>();
         for (Pixel pixel : domain) {
            for (Pixel neighbor : neighbors(pixel)) {
               if (!domain.contains(neighbor)) {
                  boundary.add(pixel);
                  break;
               }
            }
         }
         return ModelBuild.success(
            new Model(
               frame,
               Collections.unmodifiableSet(raw),
               Collections.unmodifiableSet(outline),
               Collections.unmodifiableSet(domain),
               immutableColumns(preferred),
               immutableColumns(ownedColumns),
               Collections.unmodifiableSet(boundary)
            ),
            analyticOnly
         );
      }

      Pixel project(BlockPos position) {
         long[] delta = {
            (long)position.getX() - this.frame.anchor().getX(),
            (long)position.getY() - this.frame.anchor().getY(),
            (long)position.getZ() - this.frame.anchor().getZ()
         };
         return new Pixel(
            safeInt(delta[this.frame.order()[0]] * this.frame.signs()[0]),
            safeInt(delta[this.frame.order()[1]] * this.frame.signs()[1])
         );
      }

      int height(BlockPos position) {
         long[] delta = {
            (long)position.getX() - this.frame.anchor().getX(),
            (long)position.getY() - this.frame.anchor().getY(),
            (long)position.getZ() - this.frame.anchor().getZ()
         };
         return safeInt(delta[this.frame.order()[2]] * this.frame.signs()[2]);
      }

      BlockPos restore(Pixel pixel, int height) {
         return this.frame.restore(new ProjectedBresenhamFace.Int3(pixel.u(), pixel.v(), height));
      }

      double realHeight(Pixel pixel) {
         return -(this.frame.normal().x * pixel.u() + this.frame.normal().y * pixel.v())
            / this.frame.normal().z;
      }

      private static boolean isParallelogram(BlockPos p00, BlockPos p10, BlockPos p11, BlockPos p01) {
         return (long)p00.getX() + p11.getX() == (long)p10.getX() + p01.getX()
            && (long)p00.getY() + p11.getY() == (long)p10.getY() + p01.getY()
            && (long)p00.getZ() + p11.getZ() == (long)p10.getZ() + p01.getZ();
      }

      private static boolean insideConvex(Pixel point, List<Pixel> polygon) {
         int sign = 0;
         for (int index = 0; index < polygon.size(); index++) {
            Pixel first = polygon.get(index);
            Pixel second = polygon.get((index + 1) % polygon.size());
            BigInteger value = difference(second.u(), first.u()).multiply(difference(point.v(), first.v()))
               .subtract(difference(second.v(), first.v()).multiply(difference(point.u(), first.u())));
            if (value.signum() == 0) {
               continue;
            }
            int current = value.signum();
            if (sign != 0 && current != sign) {
               return false;
            }
            sign = current;
         }
         return true;
      }

      /** Enumerates a lattice parallelogram in time proportional to its lattice-point count. */
      private static LinkedHashSet<Pixel> scanConvexDomain(
         List<Pixel> polygon,
         int maxBlocks,
         BlockGenerationObserver observer
      ) {
         if (polygon.size() != 4 || maxBlocks <= 0) {
            return null;
         }
         BigPoint origin = BigPoint.of(polygon.get(0));
         BigPoint p10 = BigPoint.of(polygon.get(1));
         BigPoint p11 = BigPoint.of(polygon.get(2));
         BigPoint p01 = BigPoint.of(polygon.get(3));
         if (!origin.add(p11).equals(p10.add(p01))) {
            return null;
         }
         BigPoint edgeU = p10.subtract(origin);
         BigPoint edgeV = p01.subtract(origin);
         BigInteger determinant = edgeU.cross(edgeV);
         if (determinant.signum() == 0) {
            return null;
         }
         if (determinant.signum() < 0) {
            BigPoint swap = edgeU;
            edgeU = edgeV;
            edgeV = swap;
            determinant = determinant.negate();
         }

         BigInteger edgeUGcd = edgeU.coordinateGcd();
         BigInteger edgeVGcd = edgeV.coordinateGcd();
         BigInteger exactCount = determinant.add(edgeUGcd).add(edgeVGcd).add(BigInteger.ONE);
         if (exactCount.compareTo(BigInteger.valueOf(maxBlocks)) > 0) {
            return null;
         }

         BigInteger rowModulus = edgeU.v().abs().gcd(edgeV.v().abs());
         if (rowModulus.signum() == 0) {
            return null;
         }
         BigInteger columnModulus = determinant.divide(rowModulus);
         int rowResidues = rowModulus.intValueExact();
         int columnResidues = columnModulus.intValueExact();
         LinkedHashSet<Pixel> result = new LinkedHashSet<>();
         int visited = 0;
         for (int v = 0; v < rowResidues; v++) {
            for (int u = 0; u < columnResidues; u++) {
               if ((visited++ & 1023) == 0) {
                  observer.checkCancelled();
               }
               BigPoint residue = new BigPoint(BigInteger.valueOf(u), BigInteger.valueOf(v));
               BigInteger uFloor = floorDivide(residue.cross(edgeV), determinant);
               BigInteger vFloor = floorDivide(edgeU.cross(residue), determinant);
               BigPoint local = residue.subtract(edgeU.scale(uFloor)).subtract(edgeV.scale(vFloor));
               result.add(origin.add(local).toPixel());
            }
         }

         addLatticeSegment(result, origin, p10, observer);
         addLatticeSegment(result, p10, p11, observer);
         addLatticeSegment(result, p11, p01, observer);
         addLatticeSegment(result, p01, origin, observer);
         if (result.size() != exactCount.intValueExact()) {
            throw new IllegalStateException("lattice parallelogram enumeration count mismatch");
         }
         return result;
      }

      private static LinkedHashSet<Pixel> bridgeDiagonalContacts(
         Set<Pixel> source,
         List<Pixel> polygon
      ) {
         LinkedHashSet<Pixel> snapshot = new LinkedHashSet<>(source);
         LinkedHashSet<Pixel> result = new LinkedHashSet<>(snapshot);
         BigInteger centerU = polygon.stream()
            .map(pixel -> BigInteger.valueOf(pixel.u()))
            .reduce(BigInteger.ZERO, BigInteger::add);
         BigInteger centerV = polygon.stream()
            .map(pixel -> BigInteger.valueOf(pixel.v()))
            .reduce(BigInteger.ZERO, BigInteger::add);
         BigInteger scale = BigInteger.valueOf(polygon.size());
         for (Pixel pixel : sorted(snapshot)) {
            for (Pixel step : List.of(new Pixel(1, 1), new Pixel(1, -1))) {
               Pixel diagonal = new Pixel(pixel.u() + step.u(), pixel.v() + step.v());
               if (!snapshot.contains(diagonal)) {
                  continue;
               }
               Pixel first = new Pixel(pixel.u() + step.u(), pixel.v());
               Pixel second = new Pixel(pixel.u(), pixel.v() + step.v());
               if (snapshot.contains(first) || snapshot.contains(second)) {
                  continue;
               }
               BigInteger firstDistance = centerDistance(first, scale, centerU, centerV);
               BigInteger secondDistance = centerDistance(second, scale, centerU, centerV);
               if (firstDistance.compareTo(secondDistance) <= 0) {
                  result.add(first);
               }
               if (secondDistance.compareTo(firstDistance) <= 0) {
                  result.add(second);
               }
            }
         }
         return result;
      }

      private static BigInteger centerDistance(
         Pixel pixel,
         BigInteger scale,
         BigInteger centerU,
         BigInteger centerV
      ) {
         BigInteger du = scale.multiply(BigInteger.valueOf(pixel.u())).subtract(centerU);
         BigInteger dv = scale.multiply(BigInteger.valueOf(pixel.v())).subtract(centerV);
         return du.multiply(du).add(dv.multiply(dv));
      }

      private static LinkedHashSet<Pixel> completeProjectedDomain(
         Set<Pixel> source,
         int maxBlocks,
         BlockGenerationObserver observer
      ) {
         if (source.isEmpty()) {
            return new LinkedHashSet<>();
         }
         if (source.size() > maxBlocks) {
            return null;
         }
         int minimumU = source.stream().mapToInt(Pixel::u).min().orElseThrow();
         int maximumU = source.stream().mapToInt(Pixel::u).max().orElseThrow();
         long lowU = (long)minimumU - 1L;
         long highU = (long)maximumU + 1L;
         TreeMap<Integer, ArrayList<Integer>> occupiedRows = new TreeMap<>();
         int visited = 0;
         for (Pixel pixel : source) {
            if ((visited++ & 1023) == 0) {
               observer.checkCancelled();
            }
            occupiedRows.computeIfAbsent(pixel.v(), ignored -> new ArrayList<>()).add(pixel.u());
         }

         ArrayList<MissingRun> runs = new ArrayList<>();
         TreeMap<Integer, List<Integer>> runsByRow = new TreeMap<>();
         visited = 0;
         for (Map.Entry<Integer, ArrayList<Integer>> entry : occupiedRows.entrySet()) {
            if ((visited++ & 1023) == 0) {
               observer.checkCancelled();
            }
            int row = entry.getKey();
            ArrayList<Integer> occupied = entry.getValue();
            occupied.sort(Integer::compare);
            Integer previousRow = occupiedRows.lowerKey(row);
            Integer nextRow = occupiedRows.higherKey(row);
            boolean openVertically = previousRow == null
               || (long)row - previousRow != 1L
               || nextRow == null
               || (long)nextRow - row != 1L;
            ArrayList<Integer> rowRuns = new ArrayList<>();
            long cursor = lowU;
            for (int coordinate : occupied) {
               if (cursor < coordinate) {
                  addMissingRun(runs, rowRuns, row, cursor, (long)coordinate - 1L, lowU, highU, openVertically);
               }
               cursor = (long)coordinate + 1L;
            }
            if (cursor <= highU) {
               addMissingRun(runs, rowRuns, row, cursor, highU, lowU, highU, openVertically);
            }
            runsByRow.put(row, List.copyOf(rowRuns));
         }

         RunComponents components = new RunComponents(runs.size());
         Map.Entry<Integer, List<Integer>> previous = null;
         for (Map.Entry<Integer, List<Integer>> current : runsByRow.entrySet()) {
            if (previous != null && (long)current.getKey() - previous.getKey() == 1L) {
               unionOverlappingRuns(runs, components, previous.getValue(), current.getValue(), observer);
            }
            previous = current;
         }
         for (int index = 0; index < runs.size(); index++) {
            if ((index & 1023) == 0) {
               observer.checkCancelled();
            }
            if (runs.get(index).exterior()) {
               components.markExterior(index);
            }
         }

         long required = source.size();
         for (int index = 0; index < runs.size(); index++) {
            if ((index & 1023) == 0) {
               observer.checkCancelled();
            }
            if (!components.isExterior(index)) {
               MissingRun run = runs.get(index);
               long length = run.maximumU() - run.minimumU() + 1L;
               if (length > (long)maxBlocks - required) {
                  return null;
               }
               required += length;
            }
         }

         LinkedHashSet<Pixel> result = new LinkedHashSet<>();
         visited = 0;
         for (Pixel pixel : source) {
            if ((visited++ & 1023) == 0) {
               observer.checkCancelled();
            }
            result.add(pixel);
         }
         visited = 0;
         for (int index = 0; index < runs.size(); index++) {
            if (components.isExterior(index)) {
               continue;
            }
            MissingRun run = runs.get(index);
            for (long u = run.minimumU(); u <= run.maximumU(); u++) {
               if ((visited++ & 1023) == 0) {
                  observer.checkCancelled();
               }
               result.add(new Pixel(Math.toIntExact(u), run.v()));
            }
         }
         return result;
      }

      private static boolean isFourConnected(Set<Pixel> source) {
         if (source.isEmpty()) {
            return true;
         }
         HashSet<Pixel> unseen = new HashSet<>(source);
         ArrayDeque<Pixel> open = new ArrayDeque<>();
         Pixel first = unseen.iterator().next();
         unseen.remove(first);
         open.add(first);
         while (!open.isEmpty()) {
            Pixel pixel = open.removeFirst();
            for (Pixel neighbor : neighbors(pixel)) {
               if (unseen.remove(neighbor)) {
                  open.addLast(neighbor);
               }
            }
         }
         return unseen.isEmpty();
      }

      private static NavigableSet<Integer> analyticPreference(Model model, Pixel pixel) {
         double target = model.realHeight(pixel);
         int lower = safeInt((long)Math.floor(target));
         TreeSet<Integer> result = new TreeSet<>();
         result.add(lower);
         if (Math.abs(target - lower) > 1.0E-9) {
            result.add(safeInt((long)lower + 1L));
         }
         return result;
      }

      private static NavigableSet<Integer> interpolate(
         Model model,
         Pixel pixel,
         Map<Pixel, NavigableSet<Integer>> known,
         int radiusLimit
      ) {
         ArrayList<Ray> rays = new ArrayList<>();
         for (Pixel direction : neighbors(new Pixel(0, 0))) {
            for (int radius = 1; radius <= radiusLimit; radius++) {
               Pixel sample = new Pixel(
                  safeInt((long)pixel.u() + (long)direction.u() * radius),
                  safeInt((long)pixel.v() + (long)direction.v() * radius)
               );
               NavigableSet<Integer> heights = known.get(sample);
               if (heights != null && !heights.isEmpty()) {
                  double phase = heights.stream().mapToDouble(value -> value - model.realHeight(sample)).average().orElse(0.0);
                  rays.add(new Ray(direction, radius, phase));
                  break;
               }
            }
         }
         double phase = 0.0;
         ArrayList<Double> axes = new ArrayList<>(2);
         addAxisEstimate(axes, rays, new Pixel(-1, 0), new Pixel(1, 0));
         addAxisEstimate(axes, rays, new Pixel(0, -1), new Pixel(0, 1));
         if (!axes.isEmpty()) {
            phase = axes.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
         } else if (!rays.isEmpty()) {
            double weighted = 0.0;
            double weights = 0.0;
            for (Ray ray : rays) {
               double weight = 1.0 / ray.radius();
               weighted += weight * ray.phase();
               weights += weight;
            }
            phase = weighted / weights;
         }
         double target = model.realHeight(pixel) + phase;
         int lower = safeInt((long)Math.floor(target));
         TreeSet<Integer> result = new TreeSet<>();
         result.add(lower);
         if (Math.abs(target - lower) > 1.0E-9) {
            result.add(safeInt((long)lower + 1L));
         }
         return result;
      }

      private static void addAxisEstimate(
         List<Double> output,
         List<Ray> rays,
         Pixel negative,
         Pixel positive
      ) {
         Ray first = rays.stream().filter(ray -> ray.direction().equals(negative)).findFirst().orElse(null);
         Ray second = rays.stream().filter(ray -> ray.direction().equals(positive)).findFirst().orElse(null);
         if (first != null && second != null) {
            output.add(
               (second.radius() * first.phase() + first.radius() * second.phase())
                  / (first.radius() + second.radius())
            );
         }
      }

      private static Map<Pixel, NavigableSet<Integer>> immutableColumns(
         Map<Pixel, NavigableSet<Integer>> source
      ) {
         LinkedHashMap<Pixel, NavigableSet<Integer>> result = new LinkedHashMap<>();
         source.forEach((pixel, heights) -> result.put(
            pixel,
            Collections.unmodifiableNavigableSet(new TreeSet<>(heights))
         ));
         return Collections.unmodifiableMap(result);
      }
   }

   private record Ray(Pixel direction, int radius, double phase) {
   }

   private record FloodNode(Pixel pixel, long level) implements Comparable<FloodNode> {
      @Override
      public int compareTo(FloodNode other) {
         int compared = Long.compare(this.level, other.level);
         return compared != 0 ? compared : PIXEL_ORDER.compare(this.pixel, other.pixel);
      }
   }

   private static final class SupportUnion {
      private final int[] parent;
      private final byte[] rank;
      private int count;

      private SupportUnion(int size) {
         this.parent = new int[size];
         this.rank = new byte[size];
         this.count = size;
         for (int index = 0; index < size; index++) {
            this.parent[index] = index;
         }
      }

      int find(int value) {
         int root = value;
         while (this.parent[root] != root) {
            root = this.parent[root];
         }
         while (this.parent[value] != value) {
            int next = this.parent[value];
            this.parent[value] = root;
            value = next;
         }
         return root;
      }

      void union(int first, int second) {
         int firstRoot = find(first);
         int secondRoot = find(second);
         if (firstRoot == secondRoot) {
            return;
         }
         if (this.rank[firstRoot] < this.rank[secondRoot]) {
            this.parent[firstRoot] = secondRoot;
         } else {
            this.parent[secondRoot] = firstRoot;
            if (this.rank[firstRoot] == this.rank[secondRoot]) {
               this.rank[firstRoot]++;
            }
         }
         this.count--;
      }

      int count() {
         return this.count;
      }
   }

   private static final class RunComponents {
      private final int[] parent;
      private final byte[] rank;
      private final boolean[] exterior;

      RunComponents(int size) {
         this.parent = new int[size];
         this.rank = new byte[size];
         this.exterior = new boolean[size];
         for (int index = 0; index < size; index++) {
            this.parent[index] = index;
         }
      }

      void union(int first, int second) {
         int firstRoot = this.find(first);
         int secondRoot = this.find(second);
         if (firstRoot == secondRoot) {
            return;
         }
         if (this.rank[firstRoot] < this.rank[secondRoot]) {
            int swap = firstRoot;
            firstRoot = secondRoot;
            secondRoot = swap;
         }
         this.parent[secondRoot] = firstRoot;
         this.exterior[firstRoot] |= this.exterior[secondRoot];
         if (this.rank[firstRoot] == this.rank[secondRoot]) {
            this.rank[firstRoot]++;
         }
      }

      void markExterior(int value) {
         this.exterior[this.find(value)] = true;
      }

      boolean isExterior(int value) {
         return this.exterior[this.find(value)];
      }

      private int find(int value) {
         int root = value;
         while (this.parent[root] != root) {
            root = this.parent[root];
         }
         while (this.parent[value] != value) {
            int next = this.parent[value];
            this.parent[value] = root;
            value = next;
         }
         return root;
      }
   }

   private static final class FlowGraph {
      private final ArrayList<ArrayList<Edge>> adjacency = new ArrayList<>();
      private int[] level = new int[0];
      private int[] next = new int[0];
      private int[] pathNodes = new int[0];
      private int[] pathEdges = new int[0];
      private long[] pathAmounts = new long[0];

      int addNode() {
         this.adjacency.add(new ArrayList<>());
         return this.adjacency.size() - 1;
      }

      void addEdge(int from, int to, long capacity) {
         Edge forward = new Edge(to, this.adjacency.get(to).size(), capacity);
         Edge reverse = new Edge(from, this.adjacency.get(from).size(), 0L);
         this.adjacency.get(from).add(forward);
         this.adjacency.get(to).add(reverse);
      }

      long maximumFlow(int source, int sink, BlockGenerationObserver observer) {
         long result = 0L;
         while (this.buildLevels(source, sink, observer)) {
            this.next = new int[this.adjacency.size()];
            this.pathNodes = new int[this.adjacency.size()];
            this.pathEdges = new int[this.adjacency.size()];
            this.pathAmounts = new long[this.adjacency.size()];
            long pushed;
            while ((pushed = this.pushIterative(source, sink, INFINITE_CAPACITY, observer)) > 0L) {
               result = Math.min(INFINITE_CAPACITY, result + pushed);
            }
         }
         return result;
      }

      boolean[] reachableFrom(int source) {
         boolean[] seen = new boolean[this.adjacency.size()];
         ArrayDeque<Integer> open = new ArrayDeque<>();
         seen[source] = true;
         open.add(source);
         while (!open.isEmpty()) {
            int current = open.removeFirst();
            for (Edge edge : this.adjacency.get(current)) {
               if (edge.capacity > 0L && !seen[edge.to]) {
                  seen[edge.to] = true;
                  open.addLast(edge.to);
               }
            }
         }
         return seen;
      }

      private boolean buildLevels(int source, int sink, BlockGenerationObserver observer) {
         this.level = new int[this.adjacency.size()];
         Arrays.fill(this.level, -1);
         ArrayDeque<Integer> open = new ArrayDeque<>();
         this.level[source] = 0;
         open.add(source);
         int visited = 0;
         while (!open.isEmpty()) {
            if ((visited++ & 1023) == 0) {
               observer.checkCancelled();
            }
            int current = open.removeFirst();
            for (Edge edge : this.adjacency.get(current)) {
               if (edge.capacity > 0L && this.level[edge.to] < 0) {
                  this.level[edge.to] = this.level[current] + 1;
                  open.addLast(edge.to);
               }
            }
         }
         return this.level[sink] >= 0;
      }

      private long pushIterative(
         int source,
         int sink,
         long amount,
         BlockGenerationObserver observer
      ) {
         int depth = 0;
         int visited = 0;
         this.pathNodes[0] = source;
         this.pathAmounts[0] = amount;
         while (depth >= 0) {
            if ((visited++ & 4095) == 0) {
               observer.checkCancelled();
            }
            int current = this.pathNodes[depth];
            if (current == sink) {
               long pushed = this.pathAmounts[depth];
               for (int index = 0; index < depth; index++) {
                  int from = this.pathNodes[index];
                  Edge edge = this.adjacency.get(from).get(this.pathEdges[index]);
                  edge.capacity -= pushed;
                  this.adjacency.get(edge.to).get(edge.reverse).capacity += pushed;
               }
               return pushed;
            }

            ArrayList<Edge> edges = this.adjacency.get(current);
            boolean advanced = false;
            while (this.next[current] < edges.size()) {
               int edgeIndex = this.next[current];
               Edge edge = edges.get(edgeIndex);
               if (edge.capacity > 0L && this.level[edge.to] == this.level[current] + 1) {
                  this.pathEdges[depth] = edgeIndex;
                  this.pathNodes[depth + 1] = edge.to;
                  this.pathAmounts[depth + 1] = Math.min(this.pathAmounts[depth], edge.capacity);
                  depth++;
                  advanced = true;
                  break;
               }
               this.next[current]++;
            }
            if (advanced) {
               continue;
            }
            this.level[current] = -1;
            if (depth == 0) {
               return 0L;
            }
            depth--;
            this.next[this.pathNodes[depth]]++;
         }
         return 0L;
      }
   }

   private static final class Edge {
      private final int to;
      private final int reverse;
      private long capacity;

      Edge(int to, int reverse, long capacity) {
         this.to = to;
         this.reverse = reverse;
         this.capacity = capacity;
      }
   }
}
