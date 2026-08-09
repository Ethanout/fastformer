package io.github.fastformer.fastplace.geometry;

import io.github.fastformer.fastplace.geometry.generation.PlanarFaceGeometry;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** A polygonal base extruded along its normal. */
public record SelectionPrism(List<Vec3> base, Vec3 extrusion) {
   private static final double EPSILON = 1.0E-7;

   public SelectionPrism {
      base = List.copyOf(base);
   }

   public static SelectionPrism fromPoints(
      List<BlockPos> points, int basePointCount, BlockPos minOffset, BlockPos maxOffset
   ) {
      if (points == null || basePointCount < 3 || points.size() <= basePointCount) {
         return null;
      }
      List<Vec3> rawBase = points.subList(0, basePointCount).stream().map(Vec3::atCenterOf).toList();
      Vec3 normal = PlanarFaceGeometry.normal(rawBase);
      if (normal.lengthSqr() < EPSILON) {
         return null;
      }
      Vec3 unitNormal = normal.normalize();
      Vec3 anchor = rawBase.getFirst();
      List<Vec3> planarBase = rawBase.stream()
         .map(point -> point.subtract(unitNormal.scale(point.subtract(anchor).dot(unitNormal))))
         .toList();
      int heightAxis = dominantAxis(unitNormal);
      double normalComponent = coordinate(unitNormal, heightAxis);
      double signedHeight = (
         coordinate(Vec3.atCenterOf(points.get(basePointCount)), heightAxis) - coordinate(anchor, heightAxis)
      ) / normalComponent;
      if (Math.abs(signedHeight) < EPSILON) {
         return null;
      }
      Vec3 direction = unitNormal.scale(Math.copySign(1.0, signedHeight));
      double step = normalStep(direction);
      BlockPos safeMin = minOffset == null ? BlockPos.ZERO : minOffset;
      BlockPos safeMax = maxOffset == null ? BlockPos.ZERO : maxOffset;
      double bottom = safeMin.getZ() * step;
      double height = Math.abs(signedHeight) + (safeMax.getZ() - safeMin.getZ()) * step;
      if (height < EPSILON) {
         return null;
      }
      List<Vec3> shiftedBase = planarBase.stream().map(point -> point.add(direction.scale(bottom))).toList();
      return new SelectionPrism(shiftedBase, direction.scale(height));
   }

   public static BlockPos resolveHeightPoint(
      List<BlockPos> basePoints, Vec3 eye, Vec3 view, double maxRayDistance
   ) {
      if (basePoints == null || basePoints.size() < 3) {
         return null;
      }
      List<Vec3> base = basePoints.stream().map(Vec3::atCenterOf).toList();
      Vec3 normal = PlanarFaceGeometry.normal(base);
      if (normal.lengthSqr() < EPSILON) {
         return null;
      }
      normal = normal.normalize();
      Vec3 anchor = base.getFirst();
      double limit = Double.isFinite(maxRayDistance) ? Math.max(0.0, maxRayDistance) : 0.0;
      double offset = GeometryConstraints.rayAxisOffset(eye, view, anchor, normal, limit, limit);
      double step = normalStep(normal);
      int steps = (int)Math.clamp(Math.round(offset / step), Integer.MIN_VALUE + 1L, Integer.MAX_VALUE - 1L);
      Vec3 resolved = anchor.add(normal.scale(steps * step));
      BlockPos approximate = BlockPos.containing(resolved);
      int dominant = dominantAxis(normal);
      int dominantCoordinate = coordinate(basePoints.getFirst(), dominant) + steps * (coordinate(normal, dominant) >= 0.0 ? 1 : -1);
      return withCoordinate(approximate, dominant, dominantCoordinate);
   }

   public static BlockPos resolveBasePlanePoint(
      List<BlockPos> basePoints, Vec3 eye, Vec3 view, double maxRayDistance
   ) {
      if (basePoints == null || basePoints.size() < 3 || eye == null || view == null) {
         return null;
      }
      List<Vec3> definingPoints = basePoints.stream().map(Vec3::atCenterOf).toList();
      Vec3 normal = PlanarFaceGeometry.normal(definingPoints);
      Vec3 ray = view.lengthSqr() < EPSILON ? Vec3.ZERO : view.normalize();
      double denominator = normal.dot(ray);
      if (normal.lengthSqr() < EPSILON || ray.lengthSqr() < EPSILON || Math.abs(denominator) < EPSILON) {
         return null;
      }
      GridPlane plane = GridPlane.from(definingPoints);
      if (plane == null) {
         return null;
      }
      Vec3 anchor = plane.anchor();
      double distance = anchor.subtract(eye).dot(normal) / denominator;
      double limit = Double.isFinite(maxRayDistance) ? Math.max(0.0, maxRayDistance) : 0.0;
      if (distance < 0.0 || distance > limit) {
         return null;
      }

      return plane.snap(BlockPos.containing(eye.add(ray.scale(distance))));
   }

   public static GridPlane gridPlane(List<BlockPos> basePoints) {
      return basePoints == null || basePoints.size() < 3
         ? null
         : GridPlane.from(basePoints.stream().map(Vec3::atCenterOf).toList());
   }

   public static GridLine heightGridLine(List<BlockPos> basePoints) {
      if (basePoints == null || basePoints.size() < 3) {
         return null;
      }
      List<Vec3> base = basePoints.stream().map(Vec3::atCenterOf).toList();
      Vec3 normal = PlanarFaceGeometry.normal(base);
      return normal.lengthSqr() < EPSILON ? null : GridLine.from(basePoints.getFirst(), normal);
   }

   public static EdgeInsertion resolveEdgeInsertion(
      List<BlockPos> points, int closedBasePointCount, Vec3 eye, Vec3 view, double maxRayDistance
   ) {
      if (points == null || points.size() < 2 || eye == null || view == null) {
         return null;
      }
      boolean closed = closedBasePointCount >= 3;
      int baseCount = closed ? Math.min(closedBasePointCount, points.size()) : points.size();
      if (baseCount < 3 || gridPlane(points.subList(0, baseCount)) == null) {
         return null;
      }
      int edgeCount = closed ? baseCount : baseCount - 1;
      EdgeInsertion best = null;
      for (int edge = 0; edge < edgeCount; edge++) {
         int next = (edge + 1) % baseCount;
         OperationGeometry.RaySegmentClosest hit = OperationGeometry.closestRaySegment(
            eye,
            view,
            Vec3.atCenterOf(points.get(edge)),
            Vec3.atCenterOf(points.get(next)),
            maxRayDistance
         );
         if (hit == null) {
            continue;
         }
         double radius = Math.clamp(hit.rayDistance() * 0.012, 0.35, 1.25);
         BlockPos candidate = BlockPos.containing(hit.segmentPoint());
         if (hit.distanceSqr() > radius * radius
            || candidate.equals(points.get(edge))
            || candidate.equals(points.get(next))
            || points.contains(candidate)) {
            continue;
         }
         int insertionIndex = edge + 1;
         EdgeInsertion insertion = new EdgeInsertion(insertionIndex, candidate.immutable(), hit.rayDistance(), hit.distanceSqr());
         if (best == null
            || insertion.distanceSqr() < best.distanceSqr() - EPSILON
            || Math.abs(insertion.distanceSqr() - best.distanceSqr()) <= EPSILON
               && insertion.rayDistance() < best.rayDistance()) {
            best = insertion;
         }
      }
      return best;
   }

   public AABB bounds() {
      List<Vec3> vertices = new ArrayList<>(this.base.size() * 2);
      vertices.addAll(this.base);
      this.base.forEach(point -> vertices.add(point.add(this.extrusion)));
      return new AABB(
         vertices.stream().mapToDouble(point -> point.x - 0.5).min().orElse(0.0),
         vertices.stream().mapToDouble(point -> point.y - 0.5).min().orElse(0.0),
         vertices.stream().mapToDouble(point -> point.z - 0.5).min().orElse(0.0),
         vertices.stream().mapToDouble(point -> point.x + 0.5).max().orElse(0.0),
         vertices.stream().mapToDouble(point -> point.y + 0.5).max().orElse(0.0),
         vertices.stream().mapToDouble(point -> point.z + 0.5).max().orElse(0.0)
      );
   }

   public boolean contains(Vec3 point) {
      Vec3 direction = this.axis(2);
      double height = this.extrusion.length();
      double along = point.subtract(this.base.getFirst()).dot(direction);
      if (along < -EPSILON || along > height + EPSILON) {
         return false;
      }
      return insideBase(point.subtract(direction.scale(along)));
   }

   public boolean intersects(AABB box) {
      List<Vec3> corners = boxCorners(box);
      if (corners.stream().anyMatch(this::contains)) {
         return true;
      }
      for (Vec3 point : this.base) {
         if (containsInclusive(box, point) || containsInclusive(box, point.add(this.extrusion))) {
            return true;
         }
      }
      for (int index = 0; index < this.base.size(); index++) {
         int next = (index + 1) % this.base.size();
         Vec3 bottom = this.base.get(index);
         Vec3 bottomNext = this.base.get(next);
         Vec3 top = bottom.add(this.extrusion);
         Vec3 topNext = bottomNext.add(this.extrusion);
         if (segmentIntersectsBox(bottom, bottomNext, box)
            || segmentIntersectsBox(top, topNext, box)
            || segmentIntersectsBox(bottom, top, box)) {
            return true;
         }
      }
      int[][] edges = {
         {0, 1}, {1, 3}, {3, 2}, {2, 0},
         {4, 5}, {5, 7}, {7, 6}, {6, 4},
         {0, 4}, {1, 5}, {2, 6}, {3, 7}
      };
      for (int[] edge : edges) {
         if (segmentIntersectsSurface(corners.get(edge[0]), corners.get(edge[1]))) {
            return true;
         }
      }
      return false;
   }

   public OperationGeometry.RayHit raycast(Vec3 origin, Vec3 rayDirection, double maxDistance) {
      Vec3 ray = rayDirection.lengthSqr() < EPSILON ? Vec3.ZERO : rayDirection.normalize();
      if (ray.lengthSqr() < EPSILON) {
         return null;
      }
      Vec3 direction = this.axis(2);
      OperationGeometry.RayHit bottom = capHit(origin, ray, this.base.getFirst(), direction.scale(-1.0), maxDistance);
      OperationGeometry.RayHit top = capHit(
         origin, ray, this.base.getFirst().add(this.extrusion), direction, maxDistance
      );
      OperationGeometry.RayHit best = bottom == null ? top : top == null || bottom.distance() <= top.distance() ? bottom : top;
      Vec3 center = this.base.stream().reduce(Vec3.ZERO, Vec3::add)
         .scale(1.0 / this.base.size())
         .add(this.extrusion.scale(0.5));
      for (int index = 0; index < this.base.size(); index++) {
         Vec3 a = this.base.get(index);
         Vec3 b = this.base.get((index + 1) % this.base.size());
         Vec3 normal = b.subtract(a).cross(this.extrusion);
         if (normal.lengthSqr() < EPSILON) {
            continue;
         }
         normal = normal.normalize();
         Vec3 midpoint = a.add(b).scale(0.5).add(this.extrusion.scale(0.5));
         if (normal.dot(center.subtract(midpoint)) > 0.0) {
            normal = normal.scale(-1.0);
         }
         OperationGeometry.RayHit side = quadHit(origin, ray, a, b, b.add(this.extrusion), a.add(this.extrusion), normal, maxDistance, index + 3);
         if (side != null && (best == null || side.distance() < best.distance())) {
            best = side;
         }
      }
      return best;
   }

   public Vec3 axis(int axis) {
      Vec3 vertical = this.extrusion.normalize();
      if (axis == 2) {
         return vertical;
      }
      Vec3 first = this.base.size() < 2 ? Vec3.ZERO : this.base.get(1).subtract(this.base.getFirst());
      Vec3 horizontal = first.subtract(vertical.scale(first.dot(vertical)));
      if (horizontal.lengthSqr() < EPSILON) {
         return Vec3.ZERO;
      }
      horizontal = horizontal.normalize();
      return axis == 0 ? horizontal : axis == 1 ? vertical.cross(horizontal).normalize() : Vec3.ZERO;
   }

   public SelectionPrism move(Vec3 offset) {
      return new SelectionPrism(this.base.stream().map(point -> point.add(offset)).toList(), this.extrusion);
   }

   public List<GuideLine> edges() {
      ArrayList<GuideLine> result = new ArrayList<>(this.base.size() * 3);
      for (int index = 0; index < this.base.size(); index++) {
         int next = (index + 1) % this.base.size();
         Vec3 bottom = this.base.get(index);
         Vec3 bottomNext = this.base.get(next);
         Vec3 top = bottom.add(this.extrusion);
         Vec3 topNext = bottomNext.add(this.extrusion);
         result.add(new GuideLine(bottom, bottomNext));
         result.add(new GuideLine(top, topNext));
         result.add(new GuideLine(bottom, top));
      }
      return List.copyOf(result);
   }

   private OperationGeometry.RayHit capHit(
      Vec3 origin, Vec3 ray, Vec3 planePoint, Vec3 outwardNormal, double maxDistance
   ) {
      double denominator = ray.dot(outwardNormal);
      if (Math.abs(denominator) < EPSILON) {
         return null;
      }
      double distance = planePoint.subtract(origin).dot(outwardNormal) / denominator;
      if (distance < 0.0 || distance > maxDistance) {
         return null;
      }
      Vec3 point = origin.add(ray.scale(distance));
      Vec3 basePoint = outwardNormal.dot(this.extrusion) > 0.0 ? point.subtract(this.extrusion) : point;
      return insideBase(basePoint) ? new OperationGeometry.RayHit(point, outwardNormal, distance, 2) : null;
   }

   private static OperationGeometry.RayHit quadHit(
      Vec3 origin,
      Vec3 ray,
      Vec3 a,
      Vec3 b,
      Vec3 c,
      Vec3 d,
      Vec3 normal,
      double maxDistance,
      int faceIndex
   ) {
      double denominator = ray.dot(normal);
      if (Math.abs(denominator) < EPSILON) {
         return null;
      }
      double distance = a.subtract(origin).dot(normal) / denominator;
      if (distance < 0.0 || distance > maxDistance) {
         return null;
      }
      Vec3 point = origin.add(ray.scale(distance));
      return insideTriangle(point, a, b, c) || insideTriangle(point, a, c, d)
         ? new OperationGeometry.RayHit(point, normal, distance, faceIndex)
         : null;
   }

   private static boolean insideTriangle(Vec3 point, Vec3 a, Vec3 b, Vec3 c) {
      Vec3 v0 = c.subtract(a);
      Vec3 v1 = b.subtract(a);
      Vec3 v2 = point.subtract(a);
      double dot00 = v0.dot(v0);
      double dot01 = v0.dot(v1);
      double dot02 = v0.dot(v2);
      double dot11 = v1.dot(v1);
      double dot12 = v1.dot(v2);
      double denominator = dot00 * dot11 - dot01 * dot01;
      if (Math.abs(denominator) < EPSILON) {
         return false;
      }
      double u = (dot11 * dot02 - dot01 * dot12) / denominator;
      double v = (dot00 * dot12 - dot01 * dot02) / denominator;
      return u >= -EPSILON && v >= -EPSILON && u + v <= 1.0 + EPSILON;
   }

   private boolean insideBase(Vec3 point) {
      Vec3 normal = this.axis(2);
      int droppedAxis = dominantAxis(normal);
      double px = coordinate(point, (droppedAxis + 1) % 3);
      double py = coordinate(point, (droppedAxis + 2) % 3);
      boolean inside = false;
      for (int index = 0, previous = this.base.size() - 1; index < this.base.size(); previous = index++) {
         Vec3 a = this.base.get(previous);
         Vec3 b = this.base.get(index);
         double ax = coordinate(a, (droppedAxis + 1) % 3);
         double ay = coordinate(a, (droppedAxis + 2) % 3);
         double bx = coordinate(b, (droppedAxis + 1) % 3);
         double by = coordinate(b, (droppedAxis + 2) % 3);
         if (onSegment(px, py, ax, ay, bx, by)) {
            return true;
         }
         if ((ay > py) != (by > py) && px < (bx - ax) * (py - ay) / (by - ay) + ax) {
            inside = !inside;
         }
      }
      return inside;
   }

   private boolean segmentIntersectsSurface(Vec3 from, Vec3 to) {
      Vec3 direction = this.axis(2);
      if (segmentIntersectsCap(from, to, this.base.getFirst(), direction)
         || segmentIntersectsCap(from, to, this.base.getFirst().add(this.extrusion), direction)) {
         return true;
      }
      for (int index = 0; index < this.base.size(); index++) {
         Vec3 a = this.base.get(index);
         Vec3 b = this.base.get((index + 1) % this.base.size());
         Vec3 c = b.add(this.extrusion);
         Vec3 d = a.add(this.extrusion);
         if (segmentIntersectsTriangle(from, to, a, b, c) || segmentIntersectsTriangle(from, to, a, c, d)) {
            return true;
         }
      }
      return false;
   }

   private boolean segmentIntersectsCap(Vec3 from, Vec3 to, Vec3 planePoint, Vec3 normal) {
      double fromDistance = from.subtract(planePoint).dot(normal);
      double toDistance = to.subtract(planePoint).dot(normal);
      double denominator = fromDistance - toDistance;
      if (Math.abs(denominator) < EPSILON) {
         return Math.abs(fromDistance) <= EPSILON && (insideBase(from) || insideBase(to));
      }
      double t = fromDistance / denominator;
      if (t < -EPSILON || t > 1.0 + EPSILON) {
         return false;
      }
      Vec3 point = from.add(to.subtract(from).scale(Math.clamp(t, 0.0, 1.0)));
      Vec3 basePoint = normal.dot(this.extrusion) > 0.0 ? point.subtract(this.extrusion) : point;
      return insideBase(basePoint);
   }

   private static boolean segmentIntersectsTriangle(Vec3 from, Vec3 to, Vec3 a, Vec3 b, Vec3 c) {
      Vec3 segment = to.subtract(from);
      Vec3 edge1 = b.subtract(a);
      Vec3 edge2 = c.subtract(a);
      Vec3 p = segment.cross(edge2);
      double determinant = edge1.dot(p);
      if (Math.abs(determinant) < EPSILON) {
         return false;
      }
      double inverse = 1.0 / determinant;
      Vec3 t = from.subtract(a);
      double u = t.dot(p) * inverse;
      if (u < -EPSILON || u > 1.0 + EPSILON) {
         return false;
      }
      Vec3 q = t.cross(edge1);
      double v = segment.dot(q) * inverse;
      if (v < -EPSILON || u + v > 1.0 + EPSILON) {
         return false;
      }
      double distance = edge2.dot(q) * inverse;
      return distance >= -EPSILON && distance <= 1.0 + EPSILON;
   }

   private static boolean segmentIntersectsBox(Vec3 from, Vec3 to, AABB box) {
      Vec3 delta = to.subtract(from);
      double minimum = 0.0;
      double maximum = 1.0;
      double[] origins = {from.x, from.y, from.z};
      double[] directions = {delta.x, delta.y, delta.z};
      double[] minima = {box.minX, box.minY, box.minZ};
      double[] maxima = {box.maxX, box.maxY, box.maxZ};
      for (int axis = 0; axis < 3; axis++) {
         if (Math.abs(directions[axis]) < EPSILON) {
            if (origins[axis] < minima[axis] - EPSILON || origins[axis] > maxima[axis] + EPSILON) {
               return false;
            }
            continue;
         }
         double first = (minima[axis] - origins[axis]) / directions[axis];
         double second = (maxima[axis] - origins[axis]) / directions[axis];
         if (first > second) {
            double swap = first;
            first = second;
            second = swap;
         }
         minimum = Math.max(minimum, first);
         maximum = Math.min(maximum, second);
         if (minimum > maximum + EPSILON) {
            return false;
         }
      }
      return true;
   }

   private static boolean containsInclusive(AABB box, Vec3 point) {
      return point.x >= box.minX - EPSILON && point.x <= box.maxX + EPSILON
         && point.y >= box.minY - EPSILON && point.y <= box.maxY + EPSILON
         && point.z >= box.minZ - EPSILON && point.z <= box.maxZ + EPSILON;
   }

   private static List<Vec3> boxCorners(AABB box) {
      return List.of(
         new Vec3(box.minX, box.minY, box.minZ), new Vec3(box.maxX, box.minY, box.minZ),
         new Vec3(box.minX, box.maxY, box.minZ), new Vec3(box.maxX, box.maxY, box.minZ),
         new Vec3(box.minX, box.minY, box.maxZ), new Vec3(box.maxX, box.minY, box.maxZ),
         new Vec3(box.minX, box.maxY, box.maxZ), new Vec3(box.maxX, box.maxY, box.maxZ)
      );
   }

   private static boolean onSegment(double px, double py, double ax, double ay, double bx, double by) {
      double cross = (px - ax) * (by - ay) - (py - ay) * (bx - ax);
      return Math.abs(cross) <= EPSILON
         && px >= Math.min(ax, bx) - EPSILON && px <= Math.max(ax, bx) + EPSILON
         && py >= Math.min(ay, by) - EPSILON && py <= Math.max(ay, by) + EPSILON;
   }

   private static int dominantAxis(Vec3 normal) {
      double x = Math.abs(normal.x);
      double y = Math.abs(normal.y);
      double z = Math.abs(normal.z);
      return x >= y && x >= z ? 0 : y >= z ? 1 : 2;
   }

   private static double coordinate(Vec3 point, int axis) {
      return axis == 0 ? point.x : axis == 1 ? point.y : point.z;
   }

   private static int coordinate(BlockPos point, int axis) {
      return axis == 0 ? point.getX() : axis == 1 ? point.getY() : point.getZ();
   }

   private static BlockPos withCoordinate(BlockPos point, int axis, int coordinate) {
      return switch (axis) {
         case 0 -> new BlockPos(coordinate, point.getY(), point.getZ());
         case 1 -> new BlockPos(point.getX(), coordinate, point.getZ());
         default -> new BlockPos(point.getX(), point.getY(), coordinate);
      };
   }

   private static double normalStep(Vec3 normal) {
      return 1.0 / Math.max(Math.abs(normal.x), Math.max(Math.abs(normal.y), Math.abs(normal.z)));
   }

   public record GridPlane(Vec3 anchor, Vec3 normal, int dependentAxis) {
      private static GridPlane from(List<Vec3> points) {
         Vec3 normal = PlanarFaceGeometry.normal(points);
         if (normal.lengthSqr() < EPSILON) {
            return null;
         }
         return new GridPlane(points.getFirst(), normal, dominantAxis(normal));
      }

      public BlockPos snap(BlockPos candidate) {
         Vec3 center = Vec3.atCenterOf(candidate);
         double dependentCenter = coordinate(this.anchor, this.dependentAxis);
         for (int axis = 0; axis < 3; axis++) {
            if (axis != this.dependentAxis) {
               dependentCenter -= coordinate(this.normal, axis)
                  * (coordinate(center, axis) - coordinate(this.anchor, axis))
                  / coordinate(this.normal, this.dependentAxis);
            }
         }
         int dependentBlock = (int)Math.clamp(
            Math.round(dependentCenter - 0.5), Integer.MIN_VALUE + 1L, Integer.MAX_VALUE - 1L
         );
         return withCoordinate(candidate, this.dependentAxis, dependentBlock);
      }

      public Vec3 rayIntersection(Vec3 eye, Vec3 view) {
         return GeometryConstraints.rayPlane(eye, view, this.anchor, this.normal).orElse(null);
      }

      public GridPlane through(BlockPos point) {
         return new GridPlane(Vec3.atCenterOf(point), this.normal, this.dependentAxis);
      }
   }

   public record GridLine(BlockPos anchorPoint, Vec3 direction, int drivingAxis, double step) {
      private static GridLine from(BlockPos anchorPoint, Vec3 direction) {
         Vec3 unit = direction.normalize();
         return new GridLine(anchorPoint.immutable(), unit, dominantAxis(unit), normalStep(unit));
      }

      public Vec3 anchor() {
         return Vec3.atCenterOf(this.anchorPoint);
      }

      public double offset(BlockPos point) {
         return Vec3.atCenterOf(point).subtract(this.anchor()).dot(this.direction);
      }

      public double rayOffset(Vec3 eye, Vec3 view) {
         return GeometryConstraints.rayAxisOffset(
            eye, view, this.anchor(), this.direction, eye.distanceTo(this.anchor())
         );
      }

      public BlockPos snap(BlockPos candidate) {
         return this.pointAtOffset(this.offset(candidate));
      }

      public BlockPos pointAtOffset(double offset) {
         long steps = Math.clamp(
            Math.round(offset / this.step), Integer.MIN_VALUE + 1L, Integer.MAX_VALUE - 1L
         );
         Vec3 resolved = this.anchor().add(this.direction.scale(steps * this.step));
         BlockPos approximate = BlockPos.containing(resolved);
         int sign = coordinate(this.direction, this.drivingAxis) >= 0.0 ? 1 : -1;
         long driven = Math.clamp(
            (long)coordinate(this.anchorPoint, this.drivingAxis) + steps * sign,
            Integer.MIN_VALUE + 1L,
            Integer.MAX_VALUE - 1L
         );
         return withCoordinate(approximate, this.drivingAxis, (int)driven);
      }

      public GridLine through(BlockPos point) {
         return new GridLine(point.immutable(), this.direction, this.drivingAxis, this.step);
      }
   }

   public record EdgeInsertion(int insertionIndex, BlockPos point, double rayDistance, double distanceSqr) {
   }
}
