package io.github.fastformer.fastplace.geometry.generation;

import io.github.fastformer.fastplace.FillMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

public final class ArbitraryConvexPolyhedronGenerator {
   private static final double EPSILON = 1.0E-7;
   private static final double PLANE_EPSILON = 1.0E-6;

   private ArbitraryConvexPolyhedronGenerator() {
   }

   public static boolean ready(List<Vec3> points) {
      return derive(points).ready();
   }

   public static Set<BlockPos> generate(List<Vec3> points, FillMode fillMode, int maxBlocks) {
      Hull hull = derive(points);
      if (!hull.ready() || maxBlocks <= 0) {
         return Set.of();
      }
      if (fillMode == FillMode.OUTLINE) {
         return outline(hull, maxBlocks);
      }

      LinkedHashSet<BlockPos> result = new LinkedHashSet<>();
      Bounds bounds = hull.bounds();
      for (int x = bounds.minX(); x <= bounds.maxX() && result.size() < maxBlocks; x++) {
         for (int y = bounds.minY(); y <= bounds.maxY() && result.size() < maxBlocks; y++) {
            IntSpan span = zSpan(hull, x, y);
            if (fillMode == FillMode.SOLID) {
               addRange(result, x, y, span.min(), span.max(), maxBlocks);
            } else {
               addBoundary(result, hull, x, y, span, maxBlocks);
            }
         }
      }
      return Set.copyOf(result);
   }

   /**
    * Visits the canonical solid in the same deterministic x/y/z order used by
    * {@link #generate}.  Returning {@code false} from the visitor stops before
    * any later voxel is produced.
    */
   static boolean visitSolid(
      List<Vec3> points,
      Predicate<BlockPos> visitor,
      BlockGenerationObserver observer
   ) {
      Hull hull = derive(points);
      if (!hull.ready() || visitor == null) {
         return false;
      }
      BlockGenerationObserver actualObserver = observer == null ? BlockGenerationObserver.NONE : observer;
      Bounds bounds = hull.bounds();
      for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
         for (int y = bounds.minY(); y <= bounds.maxY(); y++) {
            actualObserver.checkCancelled();
            IntSpan span = zSpan(hull, x, y);
            for (int z = span.min(); z <= span.max(); z++) {
               if (!visitor.test(new BlockPos(x, y, z))) {
                  return false;
               }
            }
         }
      }
      return true;
   }

   /**
    * Visits every non-empty integer column of the canonical solid without
    * constructing the column's individual block positions.  The two fixed
    * coordinates use the same cyclic order as {@code BresenhamColumnVolume}:
    * {@code (axis + 1) % 3}, then {@code (axis + 2) % 3}.
    */
   static boolean visitSolidSpans(
      List<Vec3> points,
      int axis,
      SolidSpanVisitor visitor,
      BlockGenerationObserver observer
   ) {
      if (axis < 0 || axis > 2) {
         throw new IllegalArgumentException("axis " + axis);
      }
      Hull hull = derive(points);
      if (!hull.ready() || visitor == null) {
         return false;
      }
      BlockGenerationObserver actualObserver = observer == null ? BlockGenerationObserver.NONE : observer;
      int firstAxis = (axis + 1) % 3;
      int secondAxis = (axis + 2) % 3;
      List<ProjectionHalfPlane> projection = projectedHalfPlanes(hull, firstAxis, secondAxis);
      if (projection.size() < 3) {
         return false;
      }
      long firstMinimum = coordinate(hull.bounds(), firstAxis, false);
      long firstMaximum = coordinate(hull.bounds(), firstAxis, true);
      for (long first = firstMinimum; first <= firstMaximum; first++) {
         IntSpan secondSpan = projectedSecondSpan(
            projection,
            first + 0.5,
            coordinate(hull.bounds(), secondAxis, false),
            coordinate(hull.bounds(), secondAxis, true)
         );
         if (secondSpan.empty()) {
            actualObserver.checkCancelled();
            continue;
         }
         for (long second = secondSpan.min(); second <= secondSpan.max(); second++) {
            actualObserver.checkCancelled();
            IntSpan span = axisSpan(hull, axis, firstAxis, (int)first, secondAxis, (int)second);
            if (!span.empty() && !visitor.visit((int)first, (int)second, span.min(), span.max())) {
               return false;
            }
         }
      }
      return true;
   }

   /**
    * Counts the projected columns that {@link #visitSolidSpans} would test with
    * {@code axisSpan}, without constructing spans or block positions.  The
    * result is an upper bound on non-empty visitor callbacks.  Once the count
    * exceeds {@code stopAfter}, this returns its saturated one-past value.
    */
   static long estimateSolidSpanScanColumns(
      List<Vec3> points,
      int axis,
      long stopAfter,
      BlockGenerationObserver observer
   ) {
      if (axis < 0 || axis > 2) {
         throw new IllegalArgumentException("axis " + axis);
      }
      if (stopAfter < 0L) {
         throw new IllegalArgumentException("negative stopAfter " + stopAfter);
      }
      Hull hull = derive(points);
      if (!hull.ready()) {
         return 0L;
      }
      int firstAxis = (axis + 1) % 3;
      int secondAxis = (axis + 2) % 3;
      List<ProjectionHalfPlane> projection = projectedHalfPlanes(hull, firstAxis, secondAxis);
      if (projection.size() < 3) {
         return 0L;
      }
      BlockGenerationObserver actualObserver = observer == null ? BlockGenerationObserver.NONE : observer;
      long result = 0L;
      long firstMinimum = coordinate(hull.bounds(), firstAxis, false);
      long firstMaximum = coordinate(hull.bounds(), firstAxis, true);
      for (long first = firstMinimum; first <= firstMaximum; first++) {
         actualObserver.checkCancelled();
         IntSpan secondSpan = projectedSecondSpan(
            projection,
            first + 0.5,
            coordinate(hull.bounds(), secondAxis, false),
            coordinate(hull.bounds(), secondAxis, true)
         );
         long rowLength = secondSpan.empty()
            ? 0L
            : (long)secondSpan.max() - secondSpan.min() + 1L;
         if (rowLength > stopAfter - result) {
            return stopAfter == Long.MAX_VALUE ? Long.MAX_VALUE : stopAfter + 1L;
         }
         result += rowLength;
      }
      return result;
   }

   @FunctionalInterface
   interface SolidSpanVisitor {
      boolean visit(int first, int second, int minimum, int maximum);
   }

   public static Set<BlockPos> previewOutline(List<Vec3> points, int maxBlocks) {
      Hull hull = derive(points);
      return hull.ready() && maxBlocks > 0 ? outline(hull, maxBlocks) : Set.of();
   }

   public static long estimateScanCells(List<Vec3> points) {
      Hull hull = derive(points);
      return hull.ready() ? hull.bounds().columnCount() : 0L;
   }

   private static Set<BlockPos> outline(Hull hull, int maxBlocks) {
      LinkedHashSet<BlockPos> result = new LinkedHashSet<>();
      for (Edge edge : hull.edges()) {
         LineGenerator.add(result, hull.vertices().get(edge.first()), hull.vertices().get(edge.second()), maxBlocks);
         if (result.size() >= maxBlocks) {
            break;
         }
      }
      return Set.copyOf(result);
   }

   private static void addBoundary(
      Set<BlockPos> output, Hull hull, int x, int y, IntSpan span, int maxBlocks
   ) {
      if (span.empty()) {
         return;
      }
      IntSpan left = zSpan(hull, x - 1, y);
      IntSpan right = zSpan(hull, x + 1, y);
      IntSpan down = zSpan(hull, x, y - 1);
      IntSpan up = zSpan(hull, x, y + 1);
      int interiorMin = Math.max(span.min() + 1, Math.max(Math.max(left.min(), right.min()), Math.max(down.min(), up.min())));
      int interiorMax = Math.min(span.max() - 1, Math.min(Math.min(left.max(), right.max()), Math.min(down.max(), up.max())));
      if (interiorMin > interiorMax) {
         addRange(output, x, y, span.min(), span.max(), maxBlocks);
         return;
      }
      addRange(output, x, y, span.min(), interiorMin - 1, maxBlocks);
      addRange(output, x, y, interiorMax + 1, span.max(), maxBlocks);
   }

   private static IntSpan zSpan(Hull hull, int x, int y) {
      Bounds bounds = hull.bounds();
      if (x < bounds.minX() || x > bounds.maxX() || y < bounds.minY() || y > bounds.maxY()) {
         return IntSpan.EMPTY;
      }
      double minimumCenter = bounds.minZ() + 0.5;
      double maximumCenter = bounds.maxZ() + 0.5;
      for (Plane plane : hull.planes()) {
         double fixed = plane.normal().x * (x + 0.5) + plane.normal().y * (y + 0.5);
         double coefficient = plane.normal().z;
         double remaining = plane.offset() - fixed;
         if (Math.abs(coefficient) <= EPSILON) {
            if (remaining < -EPSILON) {
               return IntSpan.EMPTY;
            }
         } else if (coefficient > 0.0) {
            maximumCenter = Math.min(maximumCenter, remaining / coefficient);
         } else {
            minimumCenter = Math.max(minimumCenter, remaining / coefficient);
         }
         if (minimumCenter > maximumCenter + EPSILON) {
            return IntSpan.EMPTY;
         }
      }
      int minimum = (int)Math.ceil(minimumCenter - 0.5 - EPSILON);
      int maximum = (int)Math.floor(maximumCenter - 0.5 + EPSILON);
      return minimum > maximum ? IntSpan.EMPTY : new IntSpan(minimum, maximum);
   }

   private static List<ProjectionPoint> projectedHull(Hull hull, int firstAxis, int secondAxis) {
      ArrayList<ProjectionPoint> points = new ArrayList<>(hull.vertices().size());
      for (Vec3 vertex : hull.vertices()) {
         ProjectionPoint point = new ProjectionPoint(
            canonicalZero(coordinate(vertex, firstAxis)),
            canonicalZero(coordinate(vertex, secondAxis))
         );
         if (points.stream().noneMatch(existing -> existing.sameCoordinates(point))) {
            points.add(point);
         }
      }
      points.sort(
         Comparator.comparingDouble(ProjectionPoint::first)
            .thenComparingDouble(ProjectionPoint::second)
      );
      if (points.size() < 3) {
         return List.of();
      }

      ArrayList<ProjectionPoint> lower = new ArrayList<>();
      for (ProjectionPoint point : points) {
         while (lower.size() >= 2
            && projectionCross(lower.get(lower.size() - 2), lower.getLast(), point) <= 0.0) {
            lower.removeLast();
         }
         lower.add(point);
      }
      ArrayList<ProjectionPoint> upper = new ArrayList<>();
      for (int index = points.size() - 1; index >= 0; index--) {
         ProjectionPoint point = points.get(index);
         while (upper.size() >= 2
            && projectionCross(upper.get(upper.size() - 2), upper.getLast(), point) <= 0.0) {
            upper.removeLast();
         }
         upper.add(point);
      }
      lower.removeLast();
      upper.removeLast();
      lower.addAll(upper);
      return List.copyOf(lower);
   }

   private static List<ProjectionHalfPlane> projectedHalfPlanes(
      Hull hull,
      int firstAxis,
      int secondAxis
   ) {
      List<ProjectionPoint> projection = projectedHull(hull, firstAxis, secondAxis);
      if (projection.size() < 3) {
         return List.of();
      }
      ArrayList<ProjectionHalfPlane> result = new ArrayList<>(projection.size());
      for (int index = 0; index < projection.size(); index++) {
         ProjectionPoint from = projection.get(index);
         ProjectionPoint to = projection.get((index + 1) % projection.size());
         double firstDelta = to.first() - from.first();
         double secondDelta = to.second() - from.second();
         double length = Math.hypot(firstDelta, secondDelta);
         if (!(length > 0.0) || !Double.isFinite(length)) {
            continue;
         }
         double firstCoefficient = -secondDelta / length;
         double secondCoefficient = firstDelta / length;
         result.add(new ProjectionHalfPlane(
            firstCoefficient,
            secondCoefficient,
            firstCoefficient * from.first() + secondCoefficient * from.second()
         ));
      }
      return List.copyOf(result);
   }

   private static IntSpan projectedSecondSpan(
      List<ProjectionHalfPlane> projection,
      double firstCenter,
      int secondMinimum,
      int secondMaximum
   ) {
      double minimumCenter = secondMinimum + 0.5;
      double maximumCenter = secondMaximum + 0.5;
      for (ProjectionHalfPlane halfPlane : projection) {
         // The monotone-chain hull is counter-clockwise.  Its unit inward
         // normal is (-dy, dx), so this is the same EPSILON-distance half-plane
         // convention used by axisSpan's normalized hull planes.
         double secondCoefficient = halfPlane.secondCoefficient();
         double fixed = halfPlane.firstCoefficient() * firstCenter;
         if (Math.abs(secondCoefficient) <= EPSILON) {
            if (fixed < halfPlane.offset() - EPSILON) {
               return IntSpan.EMPTY;
            }
            continue;
         }
         double bound = (halfPlane.offset() - EPSILON - fixed) / secondCoefficient;
         if (secondCoefficient > 0.0) {
            minimumCenter = Math.max(minimumCenter, bound);
         } else {
            maximumCenter = Math.min(maximumCenter, bound);
         }
         if (minimumCenter > maximumCenter + EPSILON) {
            return IntSpan.EMPTY;
         }
      }
      int minimum = Math.max(secondMinimum, (int)Math.ceil(minimumCenter - 0.5 - EPSILON));
      int maximum = Math.min(secondMaximum, (int)Math.floor(maximumCenter - 0.5 + EPSILON));
      return minimum > maximum ? IntSpan.EMPTY : new IntSpan(minimum, maximum);
   }

   private static double projectionCross(
      ProjectionPoint origin,
      ProjectionPoint first,
      ProjectionPoint second
   ) {
      return (first.first() - origin.first()) * (second.second() - origin.second())
         - (first.second() - origin.second()) * (second.first() - origin.first());
   }

   private static double canonicalZero(double value) {
      return value == 0.0 ? 0.0 : value;
   }

   private static IntSpan axisSpan(
      Hull hull,
      int axis,
      int firstAxis,
      int first,
      int secondAxis,
      int second
   ) {
      Bounds bounds = hull.bounds();
      double minimumCenter = coordinate(bounds, axis, false) + 0.5;
      double maximumCenter = coordinate(bounds, axis, true) + 0.5;
      for (Plane plane : hull.planes()) {
         double fixed = coordinate(plane.normal(), firstAxis) * (first + 0.5)
            + coordinate(plane.normal(), secondAxis) * (second + 0.5);
         double coefficient = coordinate(plane.normal(), axis);
         double remaining = plane.offset() - fixed;
         if (Math.abs(coefficient) <= EPSILON) {
            if (remaining < -EPSILON) {
               return IntSpan.EMPTY;
            }
         } else if (coefficient > 0.0) {
            maximumCenter = Math.min(maximumCenter, remaining / coefficient);
         } else {
            minimumCenter = Math.max(minimumCenter, remaining / coefficient);
         }
         if (minimumCenter > maximumCenter + EPSILON) {
            return IntSpan.EMPTY;
         }
      }
      int minimum = (int)Math.ceil(minimumCenter - 0.5 - EPSILON);
      int maximum = (int)Math.floor(maximumCenter - 0.5 + EPSILON);
      return minimum > maximum ? IntSpan.EMPTY : new IntSpan(minimum, maximum);
   }

   private static double coordinate(Vec3 vector, int axis) {
      return switch (axis) {
         case 0 -> vector.x;
         case 1 -> vector.y;
         case 2 -> vector.z;
         default -> throw new IllegalArgumentException("axis " + axis);
      };
   }

   private static int coordinate(Bounds bounds, int axis, boolean maximum) {
      return switch (axis) {
         case 0 -> maximum ? bounds.maxX() : bounds.minX();
         case 1 -> maximum ? bounds.maxY() : bounds.minY();
         case 2 -> maximum ? bounds.maxZ() : bounds.minZ();
         default -> throw new IllegalArgumentException("axis " + axis);
      };
   }

   private static void addRange(Set<BlockPos> output, int x, int y, int minZ, int maxZ, int maxBlocks) {
      for (int z = minZ; z <= maxZ && output.size() < maxBlocks; z++) {
         output.add(new BlockPos(x, y, z));
      }
   }

   private static Hull derive(List<Vec3> input) {
      List<Vec3> vertices = uniqueFinite(input);
      if (vertices.size() < 4) {
         return Hull.empty(vertices);
      }

      ArrayList<Plane> planes = new ArrayList<>();
      for (int first = 0; first < vertices.size() - 2; first++) {
         for (int second = first + 1; second < vertices.size() - 1; second++) {
            for (int third = second + 1; third < vertices.size(); third++) {
               Plane plane = supportingPlane(vertices, first, second, third);
               if (plane != null && planes.stream().noneMatch(existing -> samePlane(existing, plane))) {
                  planes.add(plane);
               }
            }
         }
      }
      if (planes.size() < 4) {
         return Hull.empty(vertices);
      }

      LinkedHashSet<Edge> edges = new LinkedHashSet<>();
      for (Plane plane : planes) {
         List<Integer> faceVertices = new ArrayList<>();
         for (int index = 0; index < vertices.size(); index++) {
            if (Math.abs(plane.normal().dot(vertices.get(index)) - plane.offset()) <= PLANE_EPSILON) {
               faceVertices.add(index);
            }
         }
         List<Integer> boundary = faceBoundary(vertices, faceVertices, plane.normal());
         for (int index = 0; index < boundary.size(); index++) {
            edges.add(Edge.of(boundary.get(index), boundary.get((index + 1) % boundary.size())));
         }
      }
      if (edges.size() < 6) {
         return Hull.empty(vertices);
      }
      return new Hull(vertices, List.copyOf(planes), Set.copyOf(edges), Bounds.of(vertices));
   }

   private static List<Vec3> uniqueFinite(List<Vec3> input) {
      if (input == null || input.isEmpty()) {
         return List.of();
      }
      ArrayList<Vec3> result = new ArrayList<>();
      for (Vec3 point : input) {
         if (point == null || !Double.isFinite(point.x) || !Double.isFinite(point.y) || !Double.isFinite(point.z)) {
            continue;
         }
         if (result.stream().noneMatch(existing -> existing.distanceToSqr(point) <= EPSILON * EPSILON)) {
            result.add(point);
         }
      }
      return List.copyOf(result);
   }

   private static Plane supportingPlane(List<Vec3> vertices, int first, int second, int third) {
      Vec3 a = vertices.get(first);
      Vec3 normal = vertices.get(second).subtract(a).cross(vertices.get(third).subtract(a));
      double length = normal.length();
      if (length <= EPSILON) {
         return null;
      }
      normal = normal.scale(1.0 / length);
      double minimum = Double.POSITIVE_INFINITY;
      double maximum = Double.NEGATIVE_INFINITY;
      for (Vec3 point : vertices) {
         double side = point.subtract(a).dot(normal);
         minimum = Math.min(minimum, side);
         maximum = Math.max(maximum, side);
      }
      if (minimum < -PLANE_EPSILON && maximum > PLANE_EPSILON) {
         return null;
      }
      if (Math.abs(minimum) <= PLANE_EPSILON && Math.abs(maximum) <= PLANE_EPSILON) {
         return null;
      }
      if (maximum > PLANE_EPSILON) {
         normal = normal.scale(-1.0);
      }
      return new Plane(normal, normal.dot(a));
   }

   private static boolean samePlane(Plane first, Plane second) {
      return first.normal().dot(second.normal()) >= 1.0 - PLANE_EPSILON
         && Math.abs(first.offset() - second.offset()) <= PLANE_EPSILON;
   }

   private static List<Integer> faceBoundary(List<Vec3> vertices, List<Integer> indices, Vec3 normal) {
      if (indices.size() < 3) {
         return List.of();
      }
      Vec3 reference = Math.abs(normal.x) < 0.8 ? new Vec3(1.0, 0.0, 0.0) : new Vec3(0.0, 1.0, 0.0);
      Vec3 axisU = normal.cross(reference).normalize();
      Vec3 axisV = normal.cross(axisU).normalize();
      ArrayList<ProjectedPoint> projected = new ArrayList<>();
      for (int index : indices) {
         Vec3 point = vertices.get(index);
         projected.add(new ProjectedPoint(index, point.dot(axisU), point.dot(axisV)));
      }
      projected.sort(Comparator.comparingDouble(ProjectedPoint::x).thenComparingDouble(ProjectedPoint::y));

      ArrayList<ProjectedPoint> lower = new ArrayList<>();
      for (ProjectedPoint point : projected) {
         while (lower.size() >= 2 && cross(lower.get(lower.size() - 2), lower.getLast(), point) <= PLANE_EPSILON) {
            lower.removeLast();
         }
         lower.add(point);
      }
      ArrayList<ProjectedPoint> upper = new ArrayList<>();
      for (int index = projected.size() - 1; index >= 0; index--) {
         ProjectedPoint point = projected.get(index);
         while (upper.size() >= 2 && cross(upper.get(upper.size() - 2), upper.getLast(), point) <= PLANE_EPSILON) {
            upper.removeLast();
         }
         upper.add(point);
      }
      lower.removeLast();
      upper.removeLast();
      lower.addAll(upper);
      return lower.stream().map(ProjectedPoint::index).toList();
   }

   private static double cross(ProjectedPoint origin, ProjectedPoint first, ProjectedPoint second) {
      return (first.x() - origin.x()) * (second.y() - origin.y())
         - (first.y() - origin.y()) * (second.x() - origin.x());
   }

   private record Hull(List<Vec3> vertices, List<Plane> planes, Set<Edge> edges, Bounds bounds) {
      static Hull empty(List<Vec3> vertices) {
         return new Hull(vertices, List.of(), Set.of(), Bounds.EMPTY);
      }

      boolean ready() {
         return this.planes.size() >= 4 && this.edges.size() >= 6 && !this.bounds.empty();
      }
   }

   private record Plane(Vec3 normal, double offset) {
   }

   private record Edge(int first, int second) {
      static Edge of(int first, int second) {
         return first <= second ? new Edge(first, second) : new Edge(second, first);
      }
   }

   private record ProjectedPoint(int index, double x, double y) {
   }

   private record ProjectionPoint(double first, double second) {
      boolean sameCoordinates(ProjectionPoint other) {
         return Double.compare(this.first, other.first) == 0
            && Double.compare(this.second, other.second) == 0;
      }
   }

   private record ProjectionHalfPlane(double firstCoefficient, double secondCoefficient, double offset) {
   }

   private record Bounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
      private static final Bounds EMPTY = new Bounds(0, 0, 0, -1, -1, -1);

      static Bounds of(List<Vec3> vertices) {
         if (vertices.isEmpty()) {
            return EMPTY;
         }
         double minimumX = vertices.stream().mapToDouble(point -> point.x).min().orElse(0.5);
         double minimumY = vertices.stream().mapToDouble(point -> point.y).min().orElse(0.5);
         double minimumZ = vertices.stream().mapToDouble(point -> point.z).min().orElse(0.5);
         double maximumX = vertices.stream().mapToDouble(point -> point.x).max().orElse(0.5);
         double maximumY = vertices.stream().mapToDouble(point -> point.y).max().orElse(0.5);
         double maximumZ = vertices.stream().mapToDouble(point -> point.z).max().orElse(0.5);
         return new Bounds(
            (int)Math.ceil(minimumX - 0.5 - EPSILON),
            (int)Math.ceil(minimumY - 0.5 - EPSILON),
            (int)Math.ceil(minimumZ - 0.5 - EPSILON),
            (int)Math.floor(maximumX - 0.5 + EPSILON),
            (int)Math.floor(maximumY - 0.5 + EPSILON),
            (int)Math.floor(maximumZ - 0.5 + EPSILON)
         );
      }

      boolean empty() {
         return this.minX > this.maxX || this.minY > this.maxY || this.minZ > this.maxZ;
      }

      long columnCount() {
         if (this.empty()) {
            return 0L;
         }
         long width = (long)this.maxX - this.minX + 1L;
         long height = (long)this.maxY - this.minY + 1L;
         if (width > Long.MAX_VALUE / height) {
            return Long.MAX_VALUE;
         }
         return width * height;
      }
   }

   private record IntSpan(int min, int max) {
      private static final IntSpan EMPTY = new IntSpan(Integer.MAX_VALUE, Integer.MIN_VALUE);

      boolean empty() {
         return this.min > this.max;
      }
   }
}
