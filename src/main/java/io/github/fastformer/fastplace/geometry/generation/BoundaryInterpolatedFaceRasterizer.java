package io.github.fastformer.fastplace.geometry.generation;

import io.github.fastformer.fastplace.FaceRasterizationMode;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Fills a rectangle from the pointwise sweep of two Bresenham edges and a single-valued height field. */
final class BoundaryInterpolatedFaceRasterizer {
   private static final long MAX_DIRECT_SWEEP_SAMPLES = 4_000_000L;
   private static final List<Pixel> STEPS = List.of(
      new Pixel(1, 0), new Pixel(-1, 0), new Pixel(0, 1), new Pixel(0, -1)
   );
   private static final Comparator<Pixel> PIXEL_ORDER = Comparator
      .comparingInt(Pixel::u)
      .thenComparingInt(Pixel::v);

   private BoundaryInterpolatedFaceRasterizer() {
   }

   static BresenhamFaceSweep.Attempt attempt(
      ProjectedBresenhamFace.Frame frame,
      LineTieBias tieBias,
      int maxBlocks,
      BlockGenerationObserver observer
   ) {
      return attempt(frame, tieBias, maxBlocks, observer, FaceRasterizationMode.POINT_SWEEP);
   }

   static BresenhamFaceSweep.Attempt attempt(
      ProjectedBresenhamFace.Frame frame,
      LineTieBias tieBias,
      int maxBlocks,
      BlockGenerationObserver observer,
      FaceRasterizationMode rasterizationMode
   ) {
      if (frame == null || frame.vertices().size() != 4 || maxBlocks <= 0) {
         return BresenhamFaceSweep.Attempt.failed(BresenhamFaceSweep.Status.NOT_APPLICABLE);
      }
      BlockGenerationObserver effectiveObserver = observer == null ? BlockGenerationObserver.NONE : observer;
      List<BlockPos> corners = frame.vertices().stream().map(frame::restore).toList();
      if (!isParallelogram(corners)) {
         return BresenhamFaceSweep.Attempt.failed(BresenhamFaceSweep.Status.NOT_APPLICABLE);
      }
      for (int index : new int[]{1, 3}) {
         if (LineGenerator.estimateBlocks(corners.get(0), corners.get(index)) > maxBlocks) {
            return BresenhamFaceSweep.Attempt.failed(BresenhamFaceSweep.Status.LIMIT_EXCEEDED);
         }
      }

      List<BlockPos> firstEdge = orientedPath(corners.get(0), corners.get(1), tieBias);
      List<BlockPos> secondEdge = orientedPath(corners.get(0), corners.get(3), tieBias);
      Symmetry symmetry = Symmetry.create(frame, corners);
      if (symmetry == null) {
         return BresenhamFaceSweep.Attempt.failed(BresenhamFaceSweep.Status.NOT_APPLICABLE);
      }
      LinkedHashSet<BlockPos> boundarySamples = new LinkedHashSet<>();
      boundarySamples.addAll(firstEdge);
      boundarySamples.addAll(secondEdge);
      translateInto(boundarySamples, firstEdge, delta(corners.get(0), corners.get(3)));
      translateInto(boundarySamples, secondEdge, delta(corners.get(0), corners.get(1)));

      LinkedHashSet<Pixel> domain = new LinkedHashSet<>();
      for (BlockPos block : boundarySamples) {
         effectiveObserver.checkCancelled();
         domain.add(project(frame, block));
      }
      domain = fillScanRows(frame, domain, maxBlocks, effectiveObserver);
      if (domain.isEmpty()) {
         return BresenhamFaceSweep.Attempt.failed(BresenhamFaceSweep.Status.NO_VALID_CANDIDATE);
      }
      if (domain.size() > maxBlocks) {
         return BresenhamFaceSweep.Attempt.failed(BresenhamFaceSweep.Status.LIMIT_EXCEEDED);
      }

      FaceRasterizationMode effectiveMode = rasterizationMode == null
         ? FaceRasterizationMode.POINT_SWEEP
         : rasterizationMode;
      LinkedHashMap<Pixel, TreeSet<Integer>> boundaryHeights = new LinkedHashMap<>();
      for (BlockPos block : boundarySamples) {
         boundaryHeights.computeIfAbsent(project(frame, block), ignored -> new TreeSet<>())
            .add(localHeight(frame, block));
      }
      SweepField sweep = effectiveMode == FaceRasterizationMode.POINT_SWEEP
         ? SweepField.create(
            frame,
            corners.get(0),
            firstEdge,
            secondEdge,
            maxBlocks,
            effectiveObserver
         )
         : null;
      CrossBoundaryField cross = effectiveMode == FaceRasterizationMode.GRADIENT_CROSS_INTERPOLATED_EXPERIMENTAL
         ? CrossBoundaryField.create(frame, boundaryHeights, maxBlocks, effectiveObserver)
         : null;
      LinkedHashMap<Pixel, Double> scanlineTargets = new LinkedHashMap<>();
      for (Pixel pixel : sorted(domain)) {
         effectiveObserver.checkCancelled();
         scanlineTargets.put(
            pixel,
            cross == null
               ? java.util.Objects.requireNonNull(sweep).height(pixel)
               : cross.height(pixel)
         );
      }
      if (cross != null) {
         scanlineTargets = cross.interpolateHoles(domain, scanlineTargets);
      }
      // A contour ray can miss the *discrete* boundary even when the
      // continuous projected rectangle is valid.  That is a sampling
      // failure, not a geometric infeasibility.  Complete the field from
      // the analytic plane before applying any hard candidate checks.
      repairNonFiniteTargets(frame, domain, scanlineTargets);
      LinkedHashMap<Pixel, Integer> desired = new LinkedHashMap<>();
      LinkedHashMap<Pixel, Integer> fixedBoundary = new LinkedHashMap<>();
      for (Pixel pixel : sorted(domain)) {
         effectiveObserver.checkCancelled();
         double target = scanlineTargets.getOrDefault(pixel, Double.NaN);
         if (!Double.isFinite(target)) {
            target = frame.height(pixel.u(), pixel.v());
         }
         int rounded = roundedHeight(target);
         TreeSet<Integer> fixed = boundaryHeights.get(pixel);
         if (fixed != null) {
            int nearestTarget = rounded;
            rounded = fixed.stream()
               .min(Comparator.<Integer>comparingLong(height -> Math.abs((long)height - nearestTarget))
                  .thenComparingInt(Integer::intValue))
               .orElse(rounded);
            fixedBoundary.put(pixel, rounded);
         }
         desired.put(pixel, rounded);
      }
      LinkedHashMap<Pixel, Integer> cornerHeights = new LinkedHashMap<>();
      for (BlockPos corner : corners) {
         Pixel pixel = project(frame, corner);
         int height = localHeight(frame, corner);
         desired.put(pixel, height);
         cornerHeights.put(pixel, height);
      }

      List<Map<Pixel, Integer>> heightCandidates = new ArrayList<>();
      if (isOneLipschitz(domain, desired)) {
         heightCandidates.add(Collections.unmodifiableMap(new LinkedHashMap<>(desired)));
      }
      Map<Pixel, Integer> boundaryLower = lowerLipschitzEnvelope(domain, fixedBoundary, effectiveObserver);
      Map<Pixel, Integer> boundaryUpper = upperLipschitzEnvelope(domain, fixedBoundary, effectiveObserver);
      if (preservesFixed(boundaryLower, fixedBoundary) && isOneLipschitz(domain, boundaryLower)) {
         heightCandidates.add(boundaryLower);
      }
      if (preservesFixed(boundaryUpper, fixedBoundary)
         && isOneLipschitz(domain, boundaryUpper)
         && !heightCandidates.contains(boundaryUpper)) {
         heightCandidates.add(boundaryUpper);
      }
      LinkedHashMap<Pixel, Integer> boundaryMiddle = new LinkedHashMap<>();
      for (Pixel pixel : sorted(domain)) {
         int low = Math.min(boundaryLower.get(pixel), boundaryUpper.get(pixel));
         int high = Math.max(boundaryLower.get(pixel), boundaryUpper.get(pixel));
         boundaryMiddle.put(pixel, Math.clamp(desired.get(pixel), low, high));
      }
      if (preservesFixed(boundaryMiddle, fixedBoundary)
         && isOneLipschitz(domain, boundaryMiddle)
         && !heightCandidates.contains(boundaryMiddle)) {
         heightCandidates.add(Collections.unmodifiableMap(boundaryMiddle));
      }
      Map<Pixel, Integer> lower = lowerLipschitzEnvelope(domain, desired, effectiveObserver);
      Map<Pixel, Integer> upper = upperLipschitzEnvelope(domain, desired, effectiveObserver);
      if (preservesCorners(lower, cornerHeights)) {
         heightCandidates.add(lower);
      }
      if (preservesCorners(upper, cornerHeights) && !upper.equals(lower)) {
         heightCandidates.add(upper);
      }
      Map<Pixel, Integer> analytic = symmetricAnalyticHeights(frame, domain, symmetry);
      if (analytic != null) {
         cornerHeights.forEach(analytic::put);
      }
      if (analytic != null && isOneLipschitz(domain, analytic) && !heightCandidates.contains(analytic)) {
         heightCandidates.add(Collections.unmodifiableMap(analytic));
      }
      LinkedHashMap<Pixel, Integer> ordinaryAnalytic = new LinkedHashMap<>();
      for (Pixel pixel : sorted(domain)) {
         ordinaryAnalytic.put(pixel, frame.height(pixel.u(), pixel.v()));
      }
      cornerHeights.forEach(ordinaryAnalytic::put);
      if (isOneLipschitz(domain, ordinaryAnalytic) && !heightCandidates.contains(ordinaryAnalytic)) {
         heightCandidates.add(Collections.unmodifiableMap(ordinaryAnalytic));
      }
      if (heightCandidates.isEmpty()) {
         // Exact edge samples and a one-Lipschitz field are incompatible for
         // steep projected slopes.  The strongest user-visible invariant is
         // a complete projected domain (one column per pixel), so keep the
         // corner/edge choices and emit the deterministic height field even
         // when a few adjacent columns differ by more than one.
         Candidate relaxed = materialize(
            frame,
            domain,
            desired,
            desired,
            corners,
            maxBlocks,
            effectiveObserver,
            false
         );
         if (relaxed != null) {
            return BresenhamFaceSweep.Attempt.success(new BresenhamFaceSweep.Result(
               relaxed.blocks(),
               relaxed.outline()
            ));
         }
         return BresenhamFaceSweep.Attempt.failed(BresenhamFaceSweep.Status.NO_VALID_CANDIDATE);
      }

      Candidate best = null;
      for (Map<Pixel, Integer> heights : heightCandidates) {
         Candidate candidate = materialize(
            frame, domain, heights, desired, corners, maxBlocks, effectiveObserver
         );
         // Reflection symmetry is deliberately a soft objective.  Discrete
         // Bresenham edges (especially exact half ties and unequal projected
         // spans) are not generally reflection symmetric, while the actual
         // surface contract only requires a single-valued, connected field
         // with fixed boundary samples.  Rejecting every non-symmetric
         // candidate made otherwise valid experimental scans report NO_VALID_CANDIDATE.
         if (candidate != null
            && (best == null || compareCandidates(candidate, best, effectiveMode) < 0)) {
            best = candidate;
         }
      }
      if (best == null) {
         Candidate relaxed = materialize(
            frame,
            domain,
            desired,
            desired,
            corners,
            maxBlocks,
            effectiveObserver,
            false
         );
         if (relaxed != null) {
            return BresenhamFaceSweep.Attempt.success(new BresenhamFaceSweep.Result(
               relaxed.blocks(),
               relaxed.outline()
            ));
         }
         BresenhamFaceSweep.Status status = (long)maxBlocks < (long)domain.size() * 2L
            ? BresenhamFaceSweep.Status.LIMIT_EXCEEDED
            : BresenhamFaceSweep.Status.NO_VALID_CANDIDATE;
         return BresenhamFaceSweep.Attempt.failed(status);
      }
      return BresenhamFaceSweep.Attempt.success(new BresenhamFaceSweep.Result(
         best.blocks(),
         best.outline()
      ));
   }

   private static int compareCandidates(
      Candidate first,
      Candidate second,
      FaceRasterizationMode mode
   ) {
      if (mode == FaceRasterizationMode.GRADIENT_CROSS_INTERPOLATED_EXPERIMENTAL) {
         return first.compareTo(second);
      }
      int compared = Long.compare(first.targetError(), second.targetError());
      if (compared == 0) {
         compared = Integer.compare(first.symmetryErrors(), second.symmetryErrors());
      }
      if (compared == 0) {
         compared = Double.compare(first.maximumResidual(), second.maximumResidual());
      }
      if (compared == 0) {
         compared = Double.compare(first.totalResidual(), second.totalResidual());
      }
      return compared != 0 ? compared : Integer.compare(first.blocks().size(), second.blocks().size());
   }

   private static Candidate materialize(
      ProjectedBresenhamFace.Frame frame,
      Set<Pixel> domain,
      Map<Pixel, Integer> heights,
      Map<Pixel, Integer> desired,
      List<BlockPos> corners,
      int maxBlocks,
      BlockGenerationObserver observer
   ) {
      return materialize(frame, domain, heights, desired, corners, maxBlocks, observer, true);
   }

   private static Candidate materialize(
      ProjectedBresenhamFace.Frame frame,
      Set<Pixel> domain,
      Map<Pixel, Integer> heights,
      Map<Pixel, Integer> desired,
      List<BlockPos> corners,
      int maxBlocks,
      BlockGenerationObserver observer,
      boolean requireOneLipschitz
   ) {
      Symmetry symmetry = Symmetry.create(frame, corners);
      if (symmetry == null) {
         return null;
      }
      if (domain.size() > maxBlocks || (requireOneLipschitz && !isOneLipschitz(domain, heights))) {
         return null;
      }
      LinkedHashSet<BlockPos> blocks = new LinkedHashSet<>();
      LinkedHashSet<BlockPos> outline = new LinkedHashSet<>();
      double maximumResidual = 0.0;
      double totalResidual = 0.0;
      long targetError = 0L;
      for (Pixel pixel : sorted(domain)) {
         observer.checkCancelled();
         int height = heights.get(pixel);
         targetError += Math.abs((long)height - desired.get(pixel));
         BlockPos block = frame.restore(new ProjectedBresenhamFace.Int3(pixel.u(), pixel.v(), height));
         blocks.add(block);
         double residual = Math.abs(height - realHeight(frame, pixel));
         maximumResidual = Math.max(maximumResidual, residual);
         totalResidual += residual;
      }
      if (blocks.size() != domain.size()) {
         return null;
      }
      for (Pixel pixel : cornerOutline(frame, domain, corners, observer)) {
         int height = heights.get(pixel);
         outline.add(frame.restore(new ProjectedBresenhamFace.Int3(pixel.u(), pixel.v(), height)));
      }
      int symmetryErrors = symmetry.errorCount(frame, blocks);
      return new Candidate(
         Collections.unmodifiableSet(blocks),
         Collections.unmodifiableSet(outline),
         targetError,
         maximumResidual,
         totalResidual,
         symmetryErrors
      );
   }

   private static Set<Pixel> cornerOutline(
      ProjectedBresenhamFace.Frame frame,
      Set<Pixel> domain,
      List<BlockPos> corners,
      BlockGenerationObserver observer
   ) {
      List<Pixel> projectedCorners = corners.stream().map(corner -> project(frame, corner)).toList();
      LinkedHashSet<Pixel> result = new LinkedHashSet<>();
      for (int index = 0; index < projectedCorners.size(); index++) {
         Pixel from = projectedCorners.get(index);
         Pixel to = projectedCorners.get((index + 1) % projectedCorners.size());
         result.addAll(shortestDomainPath(domain, from, to, observer));
      }
      return Collections.unmodifiableSet(result);
   }

   private static List<Pixel> shortestDomainPath(
      Set<Pixel> domain,
      Pixel from,
      Pixel to,
      BlockGenerationObserver observer
   ) {
      ArrayDeque<Pixel> open = new ArrayDeque<>();
      HashMap<Pixel, Pixel> previous = new HashMap<>();
      open.add(from);
      previous.put(from, null);
      while (!open.isEmpty() && !previous.containsKey(to)) {
         observer.checkCancelled();
         Pixel current = open.removeFirst();
         for (Pixel step : STEPS) {
            Pixel next = current.add(step);
            if (domain.contains(next) && !previous.containsKey(next)) {
               previous.put(next, current);
               open.addLast(next);
            }
         }
      }
      if (!previous.containsKey(to)) {
         return List.of();
      }
      ArrayList<Pixel> reversed = new ArrayList<>();
      for (Pixel current = to; current != null; current = previous.get(current)) {
         reversed.add(current);
      }
      Collections.reverse(reversed);
      return List.copyOf(reversed);
   }

   private static Map<Pixel, Integer> lowerLipschitzEnvelope(
      Set<Pixel> domain,
      Map<Pixel, Integer> desired,
      BlockGenerationObserver observer
   ) {
      return distanceEnvelope(domain, desired, 1, observer);
   }

   private static Map<Pixel, Integer> symmetricAnalyticHeights(
      ProjectedBresenhamFace.Frame frame,
      Set<Pixel> domain,
      Symmetry symmetry
   ) {
      LinkedHashMap<Pixel, Integer> result = new LinkedHashMap<>();
      for (Pixel pixel : sorted(domain)) {
         if (result.containsKey(pixel)) {
            continue;
         }
         Pixel reflected = symmetry.reflect(pixel);
         if (!domain.contains(reflected)) {
            return null;
         }
         if (pixel.equals(reflected)) {
            if ((symmetry.sumHeight() & 1) != 0) {
               return null;
            }
            result.put(pixel, symmetry.sumHeight() / 2);
            continue;
         }
         double pairedTarget = (
            realHeight(frame, pixel)
               + symmetry.sumHeight()
               - realHeight(frame, reflected)
         ) * 0.5;
         int height = safeInt((long)Math.floor(pairedTarget + 0.5));
         result.put(pixel, height);
         result.put(reflected, safeInt((long)symmetry.sumHeight() - height));
      }
      return result;
   }

   private static Map<Pixel, Integer> upperLipschitzEnvelope(
      Set<Pixel> domain,
      Map<Pixel, Integer> desired,
      BlockGenerationObserver observer
   ) {
      LinkedHashMap<Pixel, Integer> negated = new LinkedHashMap<>();
      desired.forEach((pixel, value) -> negated.put(pixel, -value));
      Map<Pixel, Integer> lower = distanceEnvelope(domain, negated, 1, observer);
      LinkedHashMap<Pixel, Integer> result = new LinkedHashMap<>();
      lower.forEach((pixel, value) -> result.put(pixel, -value));
      return Collections.unmodifiableMap(result);
   }

   private static Map<Pixel, Integer> distanceEnvelope(
      Set<Pixel> domain,
      Map<Pixel, Integer> source,
      int stepCost,
      BlockGenerationObserver observer
   ) {
      LinkedHashMap<Pixel, Integer> distance = new LinkedHashMap<>(source);
      java.util.PriorityQueue<HeightNode> open = new java.util.PriorityQueue<>();
      source.forEach((pixel, value) -> open.add(new HeightNode(pixel, value)));
      while (!open.isEmpty()) {
         observer.checkCancelled();
         HeightNode current = open.remove();
         if (distance.get(current.pixel()) != current.height()) {
            continue;
         }
         for (Pixel neighbor : STEPS) {
            Pixel next = current.pixel().add(neighbor);
            if (!domain.contains(next)) {
               continue;
            }
            int candidate = safeInt((long)current.height() + stepCost);
            Integer previous = distance.get(next);
            if (previous == null || candidate < previous) {
               distance.put(next, candidate);
               open.add(new HeightNode(next, candidate));
            }
         }
      }
      return Collections.unmodifiableMap(distance);
   }

   private static boolean preservesCorners(Map<Pixel, Integer> heights, Map<Pixel, Integer> corners) {
      return corners.entrySet().stream().allMatch(entry -> heights.get(entry.getKey()).equals(entry.getValue()));
   }

   private static boolean preservesFixed(Map<Pixel, Integer> heights, Map<Pixel, Integer> fixed) {
      return fixed.entrySet().stream().allMatch(entry -> entry.getValue().equals(heights.get(entry.getKey())));
   }

   private static boolean isOneLipschitz(Set<Pixel> domain, Map<Pixel, Integer> heights) {
      for (Pixel pixel : domain) {
         for (Pixel step : STEPS) {
            Pixel neighbor = pixel.add(step);
            if (domain.contains(neighbor)
               && Math.abs((long)heights.get(pixel) - heights.get(neighbor)) > 1L) {
               return false;
            }
         }
      }
      return true;
   }

   private static LinkedHashSet<Pixel> fillScanRows(
      ProjectedBresenhamFace.Frame frame,
      Set<Pixel> source,
      int maxBlocks,
      BlockGenerationObserver observer
   ) {
      TreeMap<Integer, int[]> rows = new TreeMap<>();
      for (Pixel pixel : source) {
         rows.compute(pixel.v(), (ignored, span) -> {
            if (span == null) {
               return new int[]{pixel.u(), pixel.u()};
            }
            span[0] = Math.min(span[0], pixel.u());
            span[1] = Math.max(span[1], pixel.u());
            return span;
         });
      }
      connectDiagonalRows(frame, rows);
      LinkedHashSet<Pixel> result = new LinkedHashSet<>();
      for (Map.Entry<Integer, int[]> entry : rows.entrySet()) {
         int[] span = entry.getValue();
         for (long u = span[0]; u <= span[1]; u++) {
            observer.checkCancelled();
            result.add(new Pixel((int)u, entry.getKey()));
            if (result.size() > maxBlocks) {
               return result;
            }
         }
      }
      return result;
   }

   private static void connectDiagonalRows(
      ProjectedBresenhamFace.Frame frame,
      NavigableMap<Integer, int[]> rows
   ) {
      Map.Entry<Integer, int[]> previous = null;
      for (Map.Entry<Integer, int[]> current : rows.entrySet()) {
         if (previous != null && (long)previous.getKey() + 1L == current.getKey()) {
            int[] first = previous.getValue();
            int[] second = current.getValue();
            if ((long)first[1] + 1L == second[0]) {
               bridgeDiagonal(frame, previous.getKey(), first, current.getKey(), second, first[1], second[0]);
            } else if ((long)second[1] + 1L == first[0]) {
               bridgeDiagonal(frame, previous.getKey(), first, current.getKey(), second, first[0], second[1]);
            }
         }
         previous = current;
      }
   }

   private static void bridgeDiagonal(
      ProjectedBresenhamFace.Frame frame,
      int firstV,
      int[] first,
      int secondV,
      int[] second,
      int firstU,
      int secondU
   ) {
      Pixel onFirstRow = new Pixel(secondU, firstV);
      Pixel onSecondRow = new Pixel(firstU, secondV);
      Pixel selected = distanceToProjectedCenter(frame, onFirstRow)
         <= distanceToProjectedCenter(frame, onSecondRow)
         ? onFirstRow
         : onSecondRow;
      int[] row = selected.v() == firstV ? first : second;
      row[0] = Math.min(row[0], selected.u());
      row[1] = Math.max(row[1], selected.u());
   }

   private static double distanceToProjectedCenter(ProjectedBresenhamFace.Frame frame, Pixel pixel) {
      long twiceU = 2L * pixel.u();
      long twiceV = 2L * pixel.v();
      long sumU = 0L;
      long sumV = 0L;
      for (ProjectedBresenhamFace.Int3 vertex : frame.vertices()) {
         sumU += vertex.x();
         sumV += vertex.y();
      }
      long deltaU = twiceU * frame.vertices().size() - 2L * sumU;
      long deltaV = twiceV * frame.vertices().size() - 2L * sumV;
      return (double)deltaU * deltaU + (double)deltaV * deltaV;
   }

   private static void translateInto(Set<BlockPos> output, List<BlockPos> source, BlockPos offset) {
      source.forEach(point -> output.add(point.offset(offset)));
   }

   private static List<BlockPos> orientedPath(BlockPos from, BlockPos to, LineTieBias tieBias) {
      ArrayList<BlockPos> result = new ArrayList<>(LineGenerator.path(from, to, tieBias));
      if (!result.isEmpty() && !result.getFirst().equals(from)) {
         Collections.reverse(result);
      }
      return List.copyOf(result);
   }

   private static BlockPos delta(BlockPos from, BlockPos to) {
      return new BlockPos(to.getX() - from.getX(), to.getY() - from.getY(), to.getZ() - from.getZ());
   }

   private static Pixel project(ProjectedBresenhamFace.Frame frame, BlockPos position) {
      long[] delta = {
         (long)position.getX() - frame.anchor().getX(),
         (long)position.getY() - frame.anchor().getY(),
         (long)position.getZ() - frame.anchor().getZ()
      };
      return new Pixel(
         safeInt(delta[frame.order()[0]] * frame.signs()[0]),
         safeInt(delta[frame.order()[1]] * frame.signs()[1])
      );
   }

   private static int localHeight(ProjectedBresenhamFace.Frame frame, BlockPos position) {
      long[] delta = {
         (long)position.getX() - frame.anchor().getX(),
         (long)position.getY() - frame.anchor().getY(),
         (long)position.getZ() - frame.anchor().getZ()
      };
      return safeInt(delta[frame.order()[2]] * frame.signs()[2]);
   }

   private static double realHeight(ProjectedBresenhamFace.Frame frame, Pixel pixel) {
      return -(frame.normal().x * pixel.u() + frame.normal().y * pixel.v()) / frame.normal().z;
   }

   private static boolean isParallelogram(List<BlockPos> points) {
      return (long)points.get(0).getX() + points.get(2).getX() == (long)points.get(1).getX() + points.get(3).getX()
         && (long)points.get(0).getY() + points.get(2).getY() == (long)points.get(1).getY() + points.get(3).getY()
         && (long)points.get(0).getZ() + points.get(2).getZ() == (long)points.get(1).getZ() + points.get(3).getZ();
   }

   private static List<Pixel> sorted(Set<Pixel> pixels) {
      return pixels.stream().sorted(PIXEL_ORDER).toList();
   }

   private static int safeInt(long value) {
      return Math.toIntExact(value);
   }

   private static int roundedHeight(double value) {
      if (!Double.isFinite(value)) {
         return 0;
      }
      double rounded = Math.floor(value + 0.5);
      if (rounded <= Integer.MIN_VALUE) {
         return Integer.MIN_VALUE;
      }
      if (rounded >= Integer.MAX_VALUE) {
         return Integer.MAX_VALUE;
      }
      return (int)rounded;
   }

   private static void repairNonFiniteTargets(
      ProjectedBresenhamFace.Frame frame,
      Set<Pixel> domain,
      Map<Pixel, Double> targets
   ) {
      for (Pixel pixel : sorted(domain)) {
         Double target = targets.get(pixel);
         if (target == null || !Double.isFinite(target)) {
            targets.put(pixel, (double)frame.height(pixel.u(), pixel.v()));
         }
      }
   }

   private record Pixel(int u, int v) {
      Pixel add(Pixel other) {
         return new Pixel(Math.addExact(this.u, other.u), Math.addExact(this.v, other.v));
      }
   }

   private record SweepField(
      Pixel origin,
      Pixel firstEnd,
      Pixel secondEnd,
      int originHeight,
      List<Integer> firstHeights,
      List<Integer> secondHeights,
      Map<Pixel, TreeSet<Integer>> directHeights
   ) {
      static SweepField create(
         ProjectedBresenhamFace.Frame frame,
         BlockPos origin,
         List<BlockPos> firstEdge,
         List<BlockPos> secondEdge,
         int maxBlocks,
         BlockGenerationObserver observer
      ) {
         Pixel projectedOrigin = project(frame, origin);
         List<Integer> firstHeights = firstEdge.stream().map(point -> localHeight(frame, point)).toList();
         List<Integer> secondHeights = secondEdge.stream().map(point -> localHeight(frame, point)).toList();
         LinkedHashMap<Pixel, TreeSet<Integer>> direct = new LinkedHashMap<>();
         long directLimit = Math.min(MAX_DIRECT_SWEEP_SAMPLES, (long)maxBlocks * 4L);
         boolean collectDirect = !firstEdge.isEmpty()
            && !secondEdge.isEmpty()
            && firstEdge.size() <= directLimit / secondEdge.size();
         if (collectDirect) {
            for (int firstIndex = 0; firstIndex < firstEdge.size(); firstIndex++) {
               Pixel firstPixel = project(frame, firstEdge.get(firstIndex));
               int firstHeight = firstHeights.get(firstIndex);
               for (int secondIndex = 0; secondIndex < secondEdge.size(); secondIndex++) {
                  observer.checkCancelled();
                  observer.onScanned(1L);
                  Pixel secondPixel = project(frame, secondEdge.get(secondIndex));
                  Pixel sweptPixel = new Pixel(
                     safeInt((long)firstPixel.u() + secondPixel.u() - projectedOrigin.u()),
                     safeInt((long)firstPixel.v() + secondPixel.v() - projectedOrigin.v())
                  );
                  int sweptHeight = safeInt(
                     (long)firstHeight + secondHeights.get(secondIndex) - localHeight(frame, origin)
                  );
                  direct.computeIfAbsent(sweptPixel, ignored -> new TreeSet<>()).add(sweptHeight);
               }
            }
         }
         return new SweepField(
            projectedOrigin,
            project(frame, firstEdge.getLast()),
            project(frame, secondEdge.getLast()),
            localHeight(frame, origin),
            List.copyOf(firstHeights),
            List.copyOf(secondHeights),
            Collections.unmodifiableMap(direct)
         );
      }

      double height(Pixel pixel) {
         double interpolated = this.bilinearHeight(pixel);
         TreeSet<Integer> direct = this.directHeights.get(pixel);
         if (direct == null || direct.isEmpty()) {
            return interpolated;
         }
         return direct.stream()
            .min(Comparator.<Integer>comparingDouble(height -> Math.abs(height - interpolated))
               .thenComparingInt(Integer::intValue))
            .orElseThrow();
      }

      private double bilinearHeight(Pixel pixel) {
         double firstU = this.firstEnd.u() - (double)this.origin.u();
         double firstV = this.firstEnd.v() - (double)this.origin.v();
         double secondU = this.secondEnd.u() - (double)this.origin.u();
         double secondV = this.secondEnd.v() - (double)this.origin.v();
         double deltaU = pixel.u() - (double)this.origin.u();
         double deltaV = pixel.v() - (double)this.origin.v();
         double determinant = firstU * secondV - firstV * secondU;
         if (Math.abs(determinant) < 1.0E-12) {
            return this.originHeight;
         }
         double firstParameter = Math.clamp(
            (deltaU * secondV - deltaV * secondU) / determinant,
            0.0,
            1.0
         );
         double secondParameter = Math.clamp(
            (firstU * deltaV - firstV * deltaU) / determinant,
            0.0,
            1.0
         );
         return interpolateEdge(this.firstHeights, firstParameter)
            + interpolateEdge(this.secondHeights, secondParameter)
            - this.originHeight;
      }

      private static double interpolateEdge(List<Integer> heights, double parameter) {
         if (heights.size() == 1) {
            return heights.getFirst();
         }
         double index = parameter * (heights.size() - 1.0);
         int lower = (int)Math.floor(index);
         int upper = Math.min(heights.size() - 1, lower + 1);
         return lerp(heights.get(lower), heights.get(upper), index - lower);
      }
   }

   /** Fills contour-direction scanlines, perpendicular to the plane's steepest descent. */
   private static final class CrossBoundaryField {
      private static final double EPSILON = 1.0E-9;
      private final ProjectedBresenhamFace.Frame frame;
      private final Direction2 descent;
      private final Direction2 contour;
      private final Set<Pixel> boundaryPixels;
      private final Map<Pixel, Integer> fixedBoundary;
      private final int maximumRaySteps;
      private final boolean constantHeight;

      private CrossBoundaryField(
         ProjectedBresenhamFace.Frame frame,
         Direction2 descent,
         Direction2 contour,
         Set<Pixel> boundaryPixels,
         Map<Pixel, Integer> fixedBoundary,
         int maximumRaySteps,
         boolean constantHeight
      ) {
         this.frame = frame;
         this.descent = descent;
         this.contour = contour;
         this.boundaryPixels = boundaryPixels;
         this.fixedBoundary = fixedBoundary;
         this.maximumRaySteps = maximumRaySteps;
         this.constantHeight = constantHeight;
      }

      static CrossBoundaryField create(
         ProjectedBresenhamFace.Frame frame,
         Map<Pixel, TreeSet<Integer>> boundaryHeights,
         int maxBlocks,
         BlockGenerationObserver observer
      ) {
         double descentU = frame.normal().x / frame.normal().z;
         double descentV = frame.normal().y / frame.normal().z;
         double length = Math.hypot(descentU, descentV);
         boolean constantHeight = length <= EPSILON;
         Direction2 descent = constantHeight
            ? new Direction2(1.0, 0.0)
            : new Direction2(descentU / length, descentV / length);
         Direction2 contour = new Direction2(-descent.v(), descent.u());
         LinkedHashMap<Pixel, Integer> selectedBoundary = new LinkedHashMap<>();
         for (Map.Entry<Pixel, TreeSet<Integer>> entry : boundaryHeights.entrySet()) {
            Pixel pixel = entry.getKey();
            double analytic = realHeight(frame, pixel);
            int height = entry.getValue().stream()
               .min(Comparator.<Integer>comparingDouble(value -> Math.abs(value - analytic))
                  .thenComparingInt(Integer::intValue))
               .orElseThrow();
            selectedBoundary.put(pixel, height);
         }
         int minimumU = boundaryHeights.keySet().stream().mapToInt(Pixel::u).min().orElse(0);
         int maximumU = boundaryHeights.keySet().stream().mapToInt(Pixel::u).max().orElse(0);
         int minimumV = boundaryHeights.keySet().stream().mapToInt(Pixel::v).min().orElse(0);
         int maximumV = boundaryHeights.keySet().stream().mapToInt(Pixel::v).max().orElse(0);
         int maximumRaySteps = (int)Math.ceil(Math.hypot(
            (double)maximumU - minimumU,
            (double)maximumV - minimumV
         )) + 8;
         return new CrossBoundaryField(
            frame,
            descent,
            contour,
            Collections.unmodifiableSet(new LinkedHashSet<>(boundaryHeights.keySet())),
            Collections.unmodifiableMap(selectedBoundary),
            maximumRaySteps,
            constantHeight
         );
      }

      double height(Pixel pixel) {
         Integer fixed = this.fixedBoundary.get(pixel);
         if (fixed != null) {
            return fixed;
         }
         if (this.constantHeight) {
            return realHeight(this.frame, pixel);
         }
         BoundaryHit positiveContour = this.firstHit(pixel, this.contour);
         BoundaryHit negativeContour = this.firstHit(pixel, this.contour.negated());
         if (positiveContour == null || negativeContour == null) {
            return Double.NaN;
         }
         double total = (positiveContour.u() - negativeContour.u()) * this.contour.u()
            + (positiveContour.v() - negativeContour.v()) * this.contour.v();
         if (!(total > EPSILON)) {
            return Double.NaN;
         }
         double fromNegative = (pixel.u() + 0.5 - negativeContour.u()) * this.contour.u()
            + (pixel.v() + 0.5 - negativeContour.v()) * this.contour.v();
         double parameter = Math.clamp(fromNegative / total, 0.0, 1.0);
         return lerp(negativeContour.height(), positiveContour.height(), parameter);
      }

      LinkedHashMap<Pixel, Double> interpolateHoles(
         Set<Pixel> domain,
         Map<Pixel, Double> source
      ) {
         LinkedHashMap<Pixel, Double> result = new LinkedHashMap<>(source);
         int[] step = primitiveStep(this.descent);
         for (int pass = 0; pass < 2; pass++) {
            Map<Pixel, Double> snapshot = new LinkedHashMap<>(result);
            boolean changed = false;
            for (Pixel pixel : sorted(domain)) {
               Double current = snapshot.get(pixel);
               if (current != null && Double.isFinite(current)) {
                  continue;
               }
               Sample negative = nearestFinite(pixel, -step[0], -step[1], domain, snapshot);
               Sample positive = nearestFinite(pixel, step[0], step[1], domain, snapshot);
               double interpolated;
               if (negative != null && positive != null) {
                  interpolated = (
                     positive.distance() * negative.value()
                        + negative.distance() * positive.value()
                  ) / (negative.distance() + positive.distance());
               } else if (negative != null) {
                  interpolated = negative.value();
               } else if (positive != null) {
                  interpolated = positive.value();
               } else {
                  continue;
               }
               result.put(pixel, interpolated);
               changed = true;
            }
            if (!changed) {
               break;
            }
         }
         return result;
      }

      private Sample nearestFinite(
         Pixel origin,
         int stepU,
         int stepV,
         Set<Pixel> domain,
         Map<Pixel, Double> values
      ) {
         for (int distance = 1; distance <= this.maximumRaySteps; distance++) {
            Pixel candidate = new Pixel(
               safeInt((long)origin.u() + (long)stepU * distance),
               safeInt((long)origin.v() + (long)stepV * distance)
            );
            if (!domain.contains(candidate)) {
               continue;
            }
            Double value = values.get(candidate);
            if (value != null && Double.isFinite(value)) {
               return new Sample(distance, value);
            }
         }
         return null;
      }

      private static int[] primitiveStep(Direction2 direction) {
         int bestU = 0;
         int bestV = 0;
         double bestDot = Double.NEGATIVE_INFINITY;
         for (int candidateU = -1; candidateU <= 1; candidateU++) {
            for (int candidateV = -1; candidateV <= 1; candidateV++) {
               if (candidateU == 0 && candidateV == 0) {
                  continue;
               }
               double dot = candidateU * direction.u() + candidateV * direction.v();
               if (dot > bestDot + EPSILON
                  || (Math.abs(dot - bestDot) <= EPSILON
                     && PIXEL_ORDER.compare(new Pixel(candidateU, candidateV), new Pixel(bestU, bestV)) < 0)) {
                  bestDot = dot;
                  bestU = candidateU;
                  bestV = candidateV;
               }
            }
         }
         return new int[]{bestU, bestV};
      }

      private BoundaryHit firstHit(Pixel origin, Direction2 vector) {
         double originU = origin.u() + 0.5;
         double originV = origin.v() + 0.5;
         double absoluteU = Math.abs(vector.u());
         double absoluteV = Math.abs(vector.v());
         if (absoluteU <= EPSILON && absoluteV <= EPSILON) {
            return null;
         }
         int coordinateU = origin.u();
         int coordinateV = origin.v();
         int signU = Double.compare(vector.u(), 0.0);
         int signV = Double.compare(vector.v(), 0.0);
         double nextU = absoluteU <= EPSILON ? Double.POSITIVE_INFINITY : 0.5 / absoluteU;
         double nextV = absoluteV <= EPSILON ? Double.POSITIVE_INFINITY : 0.5 / absoluteV;
         double deltaU = absoluteU <= EPSILON ? Double.POSITIVE_INFINITY : 1.0 / absoluteU;
         double deltaV = absoluteV <= EPSILON ? Double.POSITIVE_INFINITY : 1.0 / absoluteV;
         int maximumEvents = this.maximumRaySteps * 2 + 8;
         for (int event = 0; event < maximumEvents; event++) {
            if (nextU < nextV - EPSILON) {
               double distance = nextU;
               coordinateU = Math.addExact(coordinateU, signU);
               nextU += deltaU;
               BoundaryHit hit = hitCell(new Pixel(coordinateU, coordinateV), distance, originU, originV, vector);
               if (hit != null) {
                  return hit;
               }
            } else if (nextV < nextU - EPSILON) {
               double distance = nextV;
               coordinateV = Math.addExact(coordinateV, signV);
               nextV += deltaV;
               BoundaryHit hit = hitCell(new Pixel(coordinateU, coordinateV), distance, originU, originV, vector);
               if (hit != null) {
                  return hit;
               }
            } else {
               double distance = Math.min(nextU, nextV);
               Pixel sideU = new Pixel(Math.addExact(coordinateU, signU), coordinateV);
               Pixel sideV = new Pixel(coordinateU, Math.addExact(coordinateV, signV));
               Pixel diagonal = new Pixel(sideU.u(), sideV.v());
               BoundaryHit best = null;
               for (Pixel cell : List.of(sideU, sideV, diagonal)) {
                  BoundaryHit hit = hitCell(cell, distance, originU, originV, vector);
                  if (hit != null && (best == null || compareHit(hit, best) < 0)) {
                     best = hit;
                  }
               }
               if (best != null) {
                  return best;
               }
               coordinateU = sideU.u();
               coordinateV = sideV.v();
               nextU += deltaU;
               nextV += deltaV;
            }
            if (Math.min(nextU, nextV) > this.maximumRaySteps) {
               break;
            }
         }
         return null;
      }

      private BoundaryHit hitCell(
         Pixel cell,
         double distance,
         double originU,
         double originV,
         Direction2 vector
      ) {
         if (!this.boundaryPixels.contains(cell) || distance <= EPSILON || distance > this.maximumRaySteps) {
            return null;
         }
         Integer height = this.fixedBoundary.get(cell);
         if (height == null) {
            return null;
         }
         return new BoundaryHit(
            originU + vector.u() * distance,
            originV + vector.v() * distance,
            height,
            distance,
            cell
         );
      }

      private int compareHit(BoundaryHit first, BoundaryHit second) {
         int compared = Double.compare(first.distance(), second.distance());
         if (Math.abs(first.distance() - second.distance()) <= EPSILON) {
            double analytic = realHeight(this.frame, new Pixel(
               safeInt((long)Math.floor(first.u())),
               safeInt((long)Math.floor(first.v()))
            ));
            compared = Double.compare(
               Math.abs(first.height() - analytic),
               Math.abs(second.height() - analytic)
            );
            if (compared == 0) {
               compared = PIXEL_ORDER.compare(first.cell(), second.cell());
            }
         }
         return compared;
      }

      private static double inverseBilinearHeight(
         Pixel point,
         BoundaryHit q00,
         BoundaryHit q10,
         BoundaryHit q11,
         BoundaryHit q01
      ) {
         double s = 0.5;
         double t = 0.5;
         for (int iteration = 0; iteration < 12; iteration++) {
            double x = bilinear(q00.u(), q10.u(), q11.u(), q01.u(), s, t);
            double y = bilinear(q00.v(), q10.v(), q11.v(), q01.v(), s, t);
            double errorX = x - point.u();
            double errorY = y - point.v();
            if (Math.hypot(errorX, errorY) <= 1.0E-8) {
               break;
            }
            double dxds = (1.0 - t) * (q10.u() - q00.u()) + t * (q11.u() - q01.u());
            double dyds = (1.0 - t) * (q10.v() - q00.v()) + t * (q11.v() - q01.v());
            double dxdt = (1.0 - s) * (q01.u() - q00.u()) + s * (q11.u() - q10.u());
            double dydt = (1.0 - s) * (q01.v() - q00.v()) + s * (q11.v() - q10.v());
            double determinant = dxds * dydt - dxdt * dyds;
            if (Math.abs(determinant) <= 1.0E-12) {
               return Double.NaN;
            }
            double deltaS = (errorX * dydt - errorY * dxdt) / determinant;
            double deltaT = (dxds * errorY - dyds * errorX) / determinant;
            s -= deltaS;
            t -= deltaT;
         }
         if (s < -1.0E-6 || s > 1.0 + 1.0E-6 || t < -1.0E-6 || t > 1.0 + 1.0E-6) {
            return Double.NaN;
         }
         s = Math.clamp(s, 0.0, 1.0);
         t = Math.clamp(t, 0.0, 1.0);
         return bilinear(q00.height(), q10.height(), q11.height(), q01.height(), s, t);
      }

      private static double bilinear(
         double q00,
         double q10,
         double q11,
         double q01,
         double s,
         double t
      ) {
         return (1.0 - s) * (1.0 - t) * q00
            + s * (1.0 - t) * q10
            + s * t * q11
            + (1.0 - s) * t * q01;
      }

      private record Direction2(double u, double v) {
         double dot(double x, double y) {
            return this.u * x + this.v * y;
         }

         Direction2 negated() {
            return new Direction2(-this.u, -this.v);
         }
      }

      private record BoundaryHit(double u, double v, double height, double distance, Pixel cell) {
      }

      private record Sample(int distance, double value) {
      }
   }

   private record HeightNode(Pixel pixel, int height) implements Comparable<HeightNode> {
      @Override
      public int compareTo(HeightNode other) {
         int compared = Integer.compare(this.height, other.height);
         return compared != 0 ? compared : PIXEL_ORDER.compare(this.pixel, other.pixel);
      }
   }

   private record Symmetry(int sumU, int sumV, int sumHeight) {
      static Symmetry create(ProjectedBresenhamFace.Frame frame, List<BlockPos> corners) {
         Pixel first = project(frame, corners.get(0));
         Pixel opposite = project(frame, corners.get(2));
         Pixel second = project(frame, corners.get(1));
         Pixel otherOpposite = project(frame, corners.get(3));
         long sumU = (long)first.u() + opposite.u();
         long sumV = (long)first.v() + opposite.v();
         long sumHeight = (long)localHeight(frame, corners.get(0)) + localHeight(frame, corners.get(2));
         if (sumU != (long)second.u() + otherOpposite.u()
            || sumV != (long)second.v() + otherOpposite.v()
            || sumHeight != (long)localHeight(frame, corners.get(1)) + localHeight(frame, corners.get(3))) {
            return null;
         }
         return new Symmetry(safeInt(sumU), safeInt(sumV), safeInt(sumHeight));
      }

      int errorCount(ProjectedBresenhamFace.Frame frame, Set<BlockPos> blocks) {
         int errors = 0;
         for (BlockPos block : blocks) {
            Pixel pixel = project(frame, block);
            BlockPos reflected = frame.restore(new ProjectedBresenhamFace.Int3(
               safeInt((long)this.sumU - pixel.u()),
               safeInt((long)this.sumV - pixel.v()),
               safeInt((long)this.sumHeight - localHeight(frame, block))
            ));
            if (!blocks.contains(reflected)) {
               errors++;
            }
         }
         return errors;
      }

      private Pixel reflect(Pixel pixel) {
         return new Pixel(
            safeInt((long)this.sumU - pixel.u()),
            safeInt((long)this.sumV - pixel.v())
         );
      }
   }

   private record Candidate(
      Set<BlockPos> blocks,
      Set<BlockPos> outline,
      long targetError,
      double maximumResidual,
      double totalResidual,
      int symmetryErrors
   ) implements Comparable<Candidate> {
      @Override
      public int compareTo(Candidate other) {
         int compared = Integer.compare(this.symmetryErrors, other.symmetryErrors);
         if (compared == 0) {
            compared = Long.compare(this.targetError, other.targetError);
         }
         if (compared == 0) {
            compared = Double.compare(this.maximumResidual, other.maximumResidual);
         }
         if (compared == 0) {
            compared = Double.compare(this.totalResidual, other.totalResidual);
         }
         return compared != 0 ? compared : Integer.compare(this.blocks.size(), other.blocks.size());
      }
   }

   private static double lerp(double first, double second, double t) {
      return first + (second - first) * t;
   }
}
