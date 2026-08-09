package io.github.fastformer.fastplace.geometry.generation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import net.minecraft.core.BlockPos;

/**
 * Builds a strict one-block-thick integer height field while preserving the
 * four authoritative {@link LineGenerator} edges of a parallelogram.
 */
final class BoundaryConstrainedFaceRasterizer {
   private static final int DEFAULT_MAX_COLUMNS = 1_000_000;
   private static final Comparator<Column> COLUMN_ORDER = Comparator
      .comparingInt(Column::u)
      .thenComparingInt(Column::v);
   private static final List<Column> CARDINAL_STEPS = List.of(
      new Column(-1, 0),
      new Column(0, -1),
      new Column(0, 1),
      new Column(1, 0)
   );

   private BoundaryConstrainedFaceRasterizer() {
   }

   static SolveResult solve(ProjectedBresenhamFace.Frame frame) {
      return solve(frame, DEFAULT_MAX_COLUMNS);
   }

   static SolveResult solve(ProjectedBresenhamFace.Frame frame, int maxColumns) {
      if (frame == null || frame.vertices().size() != 4 || maxColumns <= 0) {
         return Failure.of(FailureKind.DEGENERATE_FRAME, "a four-vertex frame is required");
      }

      PreparedBoundary boundary;
      try {
         boundary = prepareBoundary(frame);
      } catch (ArithmeticException exception) {
         return Failure.of(FailureKind.HEIGHT_OVERFLOW, exception.getMessage());
      }
      if (boundary.failure() != null) {
         return boundary.failure();
      }

      DomainResult domainResult = buildDomain(boundary.rows(), boundary.projectedVertices(), maxColumns);
      if (domainResult.failure() != null) {
         return domainResult.failure();
      }
      Set<Column> domain = domainResult.domain();
      if (!domain.containsAll(boundary.heights().keySet())) {
         return Failure.of(FailureKind.BOUNDARY_OUTSIDE_DOMAIN, "an exact edge column is outside the projected domain");
      }
      if (!isFourConnected(domain)) {
         return Failure.of(FailureKind.EMPTY_OR_DISCONNECTED_DOMAIN, "the projected domain is not four-neighbor connected");
      }

      Map<Column, Long> upper = shortestEnvelope(domain, boundary.heights(), false);
      Map<Column, Long> negativeLower = shortestEnvelope(domain, boundary.heights(), true);
      if (upper.size() != domain.size() || negativeLower.size() != domain.size()) {
         return Failure.of(FailureKind.EMPTY_OR_DISCONNECTED_DOMAIN, "a projected component has no boundary constraint");
      }

      LinkedHashMap<Column, Long> lower = new LinkedHashMap<>();
      for (Column column : sorted(domain)) {
         long minimum = Math.negateExact(negativeLower.get(column));
         long maximum = upper.get(column);
         if (minimum > maximum) {
            return new Failure(
               FailureKind.NON_LIPSCHITZ_BOUNDARY,
               column,
               null,
               "boundary heights cannot extend with unit local steps"
            );
         }
         lower.put(column, minimum);
      }

      for (Map.Entry<Column, Integer> entry : boundary.heights().entrySet()) {
         long height = entry.getValue();
         if (lower.get(entry.getKey()) != height || upper.get(entry.getKey()) != height) {
            return new Failure(
               FailureKind.NON_LIPSCHITZ_BOUNDARY,
               entry.getKey(),
               null,
               "an exact edge height conflicts with another edge constraint"
            );
         }
      }

      LinkedHashMap<Column, Long> heights = new LinkedHashMap<>();
      LinkedHashMap<Column, Double> guide = new LinkedHashMap<>();
      try {
         for (Column column : sorted(domain)) {
            long sum = Math.addExact(lower.get(column), upper.get(column));
            heights.put(column, Math.floorDiv(sum, 2L));
            guide.put(column, guideHeight(frame, boundary.edges(), boundary.projectedVertices(), column));
         }
      } catch (ArithmeticException exception) {
         return Failure.of(FailureKind.HEIGHT_OVERFLOW, exception.getMessage());
      }
      boundary.heights().forEach((column, height) -> heights.put(column, (long)height));

      long guideMoves = optimize(domain, boundary.heights().keySet(), lower, upper, guide, heights);
      SmoothResult smooth = smoothStrictExtrema(
         domain,
         boundary.heights().keySet(),
         lower,
         upper,
         heights
      );
      if (!smooth.success()) {
         return new Failure(
            FailureKind.NON_SMOOTH_HEIGHT_FIELD,
            smooth.witness(),
            null,
            "a strict interior extremum is forced by the boundary constraints"
         );
      }
      LinkedHashMap<Column, BlockPos> primary = new LinkedHashMap<>();
      LinkedHashSet<BlockPos> blocks = new LinkedHashSet<>();
      try {
         for (Column column : sorted(domain)) {
            long height = heights.get(column);
            if (height < Integer.MIN_VALUE || height > Integer.MAX_VALUE) {
               return new Failure(FailureKind.HEIGHT_OVERFLOW, column, null, "local height is outside the integer range");
            }
            BlockPos block = frame.restore(new ProjectedBresenhamFace.Int3(column.u(), column.v(), (int)height));
            if (!blocks.add(block)) {
               return new Failure(FailureKind.HEIGHT_OVERFLOW, column, null, "two projected columns restored to one block");
            }
            primary.put(column, block);
         }
      } catch (ArithmeticException exception) {
         return Failure.of(FailureKind.HEIGHT_OVERFLOW, exception.getMessage());
      }

      if (!blocks.containsAll(boundary.outline())) {
         return Failure.of(FailureKind.NON_LIPSCHITZ_BOUNDARY, "the solved height field did not preserve every exact edge block");
      }
      return new Success(
         Collections.unmodifiableMap(primary),
         Collections.unmodifiableSet(new LinkedHashSet<>(boundary.outline())),
         Collections.unmodifiableSet(new LinkedHashSet<>(domain)),
         new Diagnostics(guideMoves, smooth.moves(), maximumGuideError(heights, guide))
      );
   }

   private static PreparedBoundary prepareBoundary(ProjectedBresenhamFace.Frame frame) {
      List<BlockPos> vertices = frame.vertices().stream().map(frame::restore).toList();
      if (!isParallelogram(vertices)) {
         return PreparedBoundary.failed(Failure.of(FailureKind.DEGENERATE_FRAME, "vertices do not form a parallelogram"));
      }

      ArrayList<ExactEdge> edges = new ArrayList<>(4);
      LinkedHashSet<BlockPos> outline = new LinkedHashSet<>();
      LinkedHashMap<Column, Integer> heights = new LinkedHashMap<>();
      NavigableMap<Integer, Span> rows = new TreeMap<>();
      ArrayList<Column> projectedVertices = new ArrayList<>(4);
      for (BlockPos vertex : vertices) {
         projectedVertices.add(local(frame, vertex).column());
      }

      for (int side = 0; side < vertices.size(); side++) {
         List<BlockPos> path = directedPath(vertices.get(side), vertices.get((side + 1) % vertices.size()));
         ArrayList<BoundarySample> samples = new ArrayList<>(path.size());
         Column projectedStart = projectedVertices.get(side);
         Column projectedEnd = projectedVertices.get((side + 1) % projectedVertices.size());
         for (BlockPos block : path) {
            LocalPoint point = local(frame, block);
            Integer previous = heights.putIfAbsent(point.column(), point.height());
            if (previous != null && previous != point.height()) {
               return PreparedBoundary.failed(new Failure(
                  FailureKind.MULTI_HEIGHT_BOUNDARY,
                  point.column(),
                  null,
                  "one projected edge column owns heights " + previous + " and " + point.height()
               ));
            }
            rows.compute(
               point.column().v(),
               (ignored, span) -> span == null
                  ? new Span(point.column().u(), point.column().u())
                  : span.include(point.column().u())
            );
            outline.add(block.immutable());
            samples.add(new BoundarySample(
               point.column(),
               point.height(),
               point.height() - realHeight(frame, point.column()),
               edgeParameter(projectedStart, projectedEnd, point.column())
            ));
         }
         samples.sort(Comparator.comparingDouble(BoundarySample::parameter).thenComparing(BoundarySample::column, COLUMN_ORDER));
         edges.add(new ExactEdge(side, List.copyOf(samples)));
      }
      return new PreparedBoundary(
         List.copyOf(edges),
         Collections.unmodifiableSet(outline),
         Collections.unmodifiableMap(heights),
         rows,
         List.copyOf(projectedVertices),
         null
      );
   }

   private static DomainResult buildDomain(
      NavigableMap<Integer, Span> sourceRows,
      List<Column> polygon,
      int maxColumns
   ) {
      if (sourceRows.isEmpty()) {
         return DomainResult.failed(Failure.of(FailureKind.EMPTY_OR_DISCONNECTED_DOMAIN, "the projected boundary is empty"));
      }
      TreeMap<Integer, Span> rows = new TreeMap<>(sourceRows);
      Integer previousV = null;
      for (Map.Entry<Integer, Span> entry : rows.entrySet()) {
         if (previousV != null) {
            if ((long)entry.getKey() - previousV != 1L) {
               return DomainResult.failed(Failure.of(
                  FailureKind.EMPTY_OR_DISCONNECTED_DOMAIN,
                  "the projected boundary skips row " + ((long)previousV + 1L)
               ));
            }
            Span previous = rows.get(previousV);
            Span current = entry.getValue();
            if ((long)previous.maximum() + 1L == current.minimum()) {
               connectDiagonalRows(rows, previousV, entry.getKey(), previous.maximum(), current.minimum(), polygon);
            } else if ((long)current.maximum() + 1L == previous.minimum()) {
               connectDiagonalRows(rows, previousV, entry.getKey(), previous.minimum(), current.maximum(), polygon);
            } else if (previous.maximum() < current.minimum() || current.maximum() < previous.minimum()) {
               return DomainResult.failed(Failure.of(
                  FailureKind.EMPTY_OR_DISCONNECTED_DOMAIN,
                  "adjacent projected rows have a gap wider than one column"
               ));
            }
         }
         previousV = entry.getKey();
      }

      long count = 0L;
      for (Span span : rows.values()) {
         count = Math.addExact(count, (long)span.maximum() - span.minimum() + 1L);
         if (count > maxColumns) {
            return DomainResult.failed(Failure.of(
               FailureKind.DOMAIN_TOO_LARGE,
               "projected domain has " + count + " columns, limit " + maxColumns
            ));
         }
      }

      LinkedHashSet<Column> domain = new LinkedHashSet<>((int)count);
      for (Map.Entry<Integer, Span> entry : rows.entrySet()) {
         for (long u = entry.getValue().minimum(); u <= entry.getValue().maximum(); u++) {
            domain.add(new Column((int)u, entry.getKey()));
         }
      }
      return new DomainResult(Collections.unmodifiableSet(domain), null);
   }

   private static void connectDiagonalRows(
      NavigableMap<Integer, Span> rows,
      int previousV,
      int currentV,
      int previousU,
      int currentU,
      List<Column> polygon
   ) {
      Column inPrevious = new Column(currentU, previousV);
      Column inCurrent = new Column(previousU, currentV);
      boolean previousInside = contains(polygon, inPrevious.u(), inPrevious.v());
      boolean currentInside = contains(polygon, inCurrent.u(), inCurrent.v());
      if (previousInside != currentInside ? previousInside : COLUMN_ORDER.compare(inPrevious, inCurrent) <= 0) {
         rows.put(previousV, rows.get(previousV).include(currentU));
      } else {
         rows.put(currentV, rows.get(currentV).include(previousU));
      }
   }

   private static Map<Column, Long> shortestEnvelope(
      Set<Column> domain,
      Map<Column, Integer> boundary,
      boolean negateSeeds
   ) {
      HashMap<Column, Long> distance = new HashMap<>();
      PriorityQueue<DistanceNode> open = new PriorityQueue<>();
      for (Map.Entry<Column, Integer> entry : boundary.entrySet()) {
         long value = negateSeeds ? -(long)entry.getValue() : entry.getValue();
         Long previous = distance.putIfAbsent(entry.getKey(), value);
         if (previous == null || value < previous) {
            distance.put(entry.getKey(), value);
            open.add(new DistanceNode(entry.getKey(), value));
         } else if (previous == value) {
            open.add(new DistanceNode(entry.getKey(), value));
         }
      }
      while (!open.isEmpty()) {
         DistanceNode current = open.remove();
         if (distance.getOrDefault(current.column(), Long.MAX_VALUE) != current.value()) {
            continue;
         }
         for (Column neighbor : neighbors(current.column())) {
            if (!domain.contains(neighbor)) {
               continue;
            }
            long candidate = Math.addExact(current.value(), 1L);
            if (candidate < distance.getOrDefault(neighbor, Long.MAX_VALUE)) {
               distance.put(neighbor, candidate);
               open.add(new DistanceNode(neighbor, candidate));
            }
         }
      }
      return distance;
   }

   private static long optimize(
      Set<Column> domain,
      Set<Column> fixed,
      Map<Column, Long> lower,
      Map<Column, Long> upper,
      Map<Column, Double> guide,
      Map<Column, Long> heights
   ) {
      TreeSet<Column> pending = new TreeSet<>(COLUMN_ORDER);
      pending.addAll(domain);
      long moves = 0L;
      while (!pending.isEmpty()) {
         Column column = pending.pollFirst();
         if (fixed.contains(column)) {
            continue;
         }
         long current = heights.get(column);
         long target = roundedGuide(guide.get(column));
         int direction = Long.compare(target, current);
         if (direction == 0) {
            continue;
         }
         long candidate = current + direction;
         if (candidate < lower.get(column) || candidate > upper.get(column)) {
            continue;
         }
         boolean allowed = true;
         for (Column neighbor : neighbors(column)) {
            Long neighborHeight = heights.get(neighbor);
            if (neighborHeight != null && Math.abs(candidate - neighborHeight) > 1L) {
               allowed = false;
               break;
            }
         }
         if (!allowed) {
            continue;
         }
         heights.put(column, candidate);
         moves++;
         pending.add(column);
         for (Column neighbor : neighbors(column)) {
            if (domain.contains(neighbor) && !fixed.contains(neighbor)) {
               pending.add(neighbor);
            }
         }
      }
      return moves;
   }

   /** Each accepted move turns four unit slopes into a plateau, so total variation strictly decreases. */
   private static SmoothResult smoothStrictExtrema(
      Set<Column> domain,
      Set<Column> fixed,
      Map<Column, Long> lower,
      Map<Column, Long> upper,
      Map<Column, Long> heights
   ) {
      TreeSet<Column> pending = new TreeSet<>(COLUMN_ORDER);
      pending.addAll(domain);
      long moves = 0L;
      while (!pending.isEmpty()) {
         Column column = pending.pollFirst();
         List<Column> neighbors = neighbors(column);
         if (!domain.containsAll(neighbors)) {
            continue;
         }
         long height = heights.get(column);
         boolean strictMinimum = true;
         boolean strictMaximum = true;
         for (Column neighbor : neighbors) {
            long neighborHeight = heights.get(neighbor);
            strictMinimum &= neighborHeight > height;
            strictMaximum &= neighborHeight < height;
         }
         if (!strictMinimum && !strictMaximum) {
            continue;
         }
         if (fixed.contains(column)) {
            return new SmoothResult(false, moves, column);
         }
         long candidate = strictMinimum ? height + 1L : height - 1L;
         if (candidate < lower.get(column) || candidate > upper.get(column)) {
            return new SmoothResult(false, moves, column);
         }
         heights.put(column, candidate);
         moves++;
         pending.add(column);
         for (Column neighbor : neighbors) {
            pending.add(neighbor);
         }
      }
      return new SmoothResult(true, moves, null);
   }

   private static double guideHeight(
      ProjectedBresenhamFace.Frame frame,
      List<ExactEdge> edges,
      List<Column> vertices,
      Column column
   ) {
      Column origin = vertices.getFirst();
      Column first = vertices.get(1);
      Column fourth = vertices.get(3);
      double firstU = (double)first.u() - origin.u();
      double firstV = (double)first.v() - origin.v();
      double fourthU = (double)fourth.u() - origin.u();
      double fourthV = (double)fourth.v() - origin.v();
      double relativeU = (double)column.u() - origin.u();
      double relativeV = (double)column.v() - origin.v();
      double determinant = firstU * fourthV - firstV * fourthU;
      if (!Double.isFinite(determinant) || Math.abs(determinant) < 1.0E-12) {
         return realHeight(frame, column);
      }
      double s = clamp01((relativeU * fourthV - relativeV * fourthU) / determinant);
      double t = clamp01((firstU * relativeV - firstV * relativeU) / determinant);

      double bottom = sampleDeviation(edges.get(0), s);
      double right = sampleDeviation(edges.get(1), t);
      double top = sampleDeviation(edges.get(2), 1.0 - s);
      double left = sampleDeviation(edges.get(3), 1.0 - t);
      double d00 = sampleDeviation(edges.get(0), 0.0);
      double d10 = sampleDeviation(edges.get(0), 1.0);
      double d11 = sampleDeviation(edges.get(1), 1.0);
      double d01 = sampleDeviation(edges.get(2), 1.0);
      double cornerBlend = (1.0 - s) * (1.0 - t) * d00
         + s * (1.0 - t) * d10
         + s * t * d11
         + (1.0 - s) * t * d01;
      double deviation = (1.0 - t) * bottom + t * top + (1.0 - s) * left + s * right - cornerBlend;
      double result = realHeight(frame, column) + deviation;
      return Double.isFinite(result) ? result : realHeight(frame, column);
   }

   private static double sampleDeviation(ExactEdge edge, double parameter) {
      List<BoundarySample> samples = edge.samples();
      if (samples.size() == 1) {
         return samples.getFirst().deviation();
      }
      double target = clamp01(parameter);
      if (target <= samples.getFirst().parameter()) {
         return samples.getFirst().deviation();
      }
      for (int index = 1; index < samples.size(); index++) {
         BoundarySample following = samples.get(index);
         if (target <= following.parameter()) {
            BoundarySample previous = samples.get(index - 1);
            double span = following.parameter() - previous.parameter();
            if (span <= 1.0E-12) {
               return (previous.deviation() + following.deviation()) * 0.5;
            }
            double fraction = (target - previous.parameter()) / span;
            return previous.deviation() * (1.0 - fraction) + following.deviation() * fraction;
         }
      }
      return samples.getLast().deviation();
   }

   private static double edgeParameter(Column start, Column end, Column point) {
      double deltaU = (double)end.u() - start.u();
      double deltaV = (double)end.v() - start.v();
      double denominator = deltaU * deltaU + deltaV * deltaV;
      if (!Double.isFinite(denominator) || denominator < 1.0E-12) {
         return 0.0;
      }
      double relativeU = (double)point.u() - start.u();
      double relativeV = (double)point.v() - start.v();
      return clamp01((relativeU * deltaU + relativeV * deltaV) / denominator);
   }

   private static double realHeight(ProjectedBresenhamFace.Frame frame, Column column) {
      double result = -(frame.normal().x * column.u() + frame.normal().y * column.v()) / frame.normal().z;
      return Double.isFinite(result) ? result : 0.0;
   }

   private static long roundedGuide(double value) {
      if (!Double.isFinite(value)) {
         return 0L;
      }
      if (value <= Long.MIN_VALUE) {
         return Long.MIN_VALUE;
      }
      if (value >= Long.MAX_VALUE) {
         return Long.MAX_VALUE;
      }
      return (long)Math.floor(value + 0.5);
   }

   private static double maximumGuideError(Map<Column, Long> heights, Map<Column, Double> guide) {
      double maximum = 0.0;
      for (Map.Entry<Column, Long> entry : heights.entrySet()) {
         maximum = Math.max(maximum, Math.abs(entry.getValue() - guide.get(entry.getKey())));
      }
      return maximum;
   }

   private static LocalPoint local(ProjectedBresenhamFace.Frame frame, BlockPos position) {
      long[] delta = {
         (long)position.getX() - frame.anchor().getX(),
         (long)position.getY() - frame.anchor().getY(),
         (long)position.getZ() - frame.anchor().getZ()
      };
      int[] order = frame.order();
      int[] signs = frame.signs();
      int u = Math.toIntExact(delta[order[0]] * signs[0]);
      int v = Math.toIntExact(delta[order[1]] * signs[1]);
      int height = Math.toIntExact(delta[order[2]] * signs[2]);
      return new LocalPoint(new Column(u, v), height);
   }

   private static List<BlockPos> directedPath(BlockPos from, BlockPos to) {
      ArrayList<BlockPos> result = new ArrayList<>(LineGenerator.path(from, to));
      if (!result.isEmpty() && !result.getFirst().equals(from)) {
         Collections.reverse(result);
      }
      return List.copyOf(result);
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

   private static boolean isFourConnected(Set<Column> domain) {
      if (domain.isEmpty()) {
         return false;
      }
      HashSet<Column> visited = new HashSet<>();
      ArrayDeque<Column> open = new ArrayDeque<>();
      open.add(domain.iterator().next());
      while (!open.isEmpty()) {
         Column current = open.removeFirst();
         if (!visited.add(current)) {
            continue;
         }
         for (Column neighbor : neighbors(current)) {
            if (domain.contains(neighbor) && !visited.contains(neighbor)) {
               open.addLast(neighbor);
            }
         }
      }
      return visited.size() == domain.size();
   }

   private static boolean contains(List<Column> polygon, double u, double v) {
      boolean inside = false;
      for (int current = 0, previous = polygon.size() - 1; current < polygon.size(); previous = current++) {
         Column first = polygon.get(previous);
         Column second = polygon.get(current);
         boolean crosses = (first.v() > v) != (second.v() > v);
         if (crosses) {
            double intersection = first.u() + (v - first.v()) * (second.u() - first.u())
               / (double)(second.v() - first.v());
            if (u < intersection) {
               inside = !inside;
            }
         }
      }
      return inside;
   }

   private static List<Column> neighbors(Column column) {
      return CARDINAL_STEPS.stream().map(column::add).toList();
   }

   private static List<Column> sorted(Set<Column> columns) {
      return columns.stream().sorted(COLUMN_ORDER).toList();
   }

   private static double clamp01(double value) {
      return Math.max(0.0, Math.min(1.0, value));
   }

   sealed interface SolveResult permits Success, Failure {
   }

   record Success(
      Map<Column, BlockPos> primary,
      Set<BlockPos> outline,
      Set<Column> domain,
      Diagnostics diagnostics
   ) implements SolveResult {
   }

   record Failure(FailureKind kind, Column first, Column second, String detail) implements SolveResult {
      static Failure of(FailureKind kind, String detail) {
         return new Failure(kind, null, null, detail == null ? "" : detail);
      }
   }

   enum FailureKind {
      DEGENERATE_FRAME,
      EMPTY_OR_DISCONNECTED_DOMAIN,
      BOUNDARY_OUTSIDE_DOMAIN,
      MULTI_HEIGHT_BOUNDARY,
      NON_LIPSCHITZ_BOUNDARY,
      NON_SMOOTH_HEIGHT_FIELD,
      HEIGHT_OVERFLOW,
      DOMAIN_TOO_LARGE
   }

   record Column(int u, int v) {
      Column add(Column offset) {
         return new Column(Math.addExact(this.u, offset.u), Math.addExact(this.v, offset.v));
      }
   }

   record Diagnostics(long guideMoves, long smoothedExtrema, double maximumGuideError) {
   }

   private record LocalPoint(Column column, int height) {
   }

   private record BoundarySample(Column column, int height, double deviation, double parameter) {
   }

   private record ExactEdge(int side, List<BoundarySample> samples) {
   }

   private record PreparedBoundary(
      List<ExactEdge> edges,
      Set<BlockPos> outline,
      Map<Column, Integer> heights,
      NavigableMap<Integer, Span> rows,
      List<Column> projectedVertices,
      Failure failure
   ) {
      static PreparedBoundary failed(Failure failure) {
         return new PreparedBoundary(List.of(), Set.of(), Map.of(), new TreeMap<>(), List.of(), failure);
      }
   }

   private record DomainResult(Set<Column> domain, Failure failure) {
      static DomainResult failed(Failure failure) {
         return new DomainResult(Set.of(), failure);
      }
   }

   private record Span(int minimum, int maximum) {
      Span include(int value) {
         return new Span(Math.min(this.minimum, value), Math.max(this.maximum, value));
      }
   }

   private record DistanceNode(Column column, long value) implements Comparable<DistanceNode> {
      @Override
      public int compareTo(DistanceNode other) {
         int compared = Long.compare(this.value, other.value);
         return compared != 0 ? compared : COLUMN_ORDER.compare(this.column, other.column);
      }
   }

   private record SmoothResult(boolean success, long moves, Column witness) {
   }
}
