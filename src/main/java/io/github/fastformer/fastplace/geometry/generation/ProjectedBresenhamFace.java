package io.github.fastformer.fastplace.geometry.generation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** A one-voxel digital face owned by one signed axis frame. */
final class ProjectedBresenhamFace {
   private static final int[][] ORDERS = {
      {0, 1, 2}, {0, 2, 1}, {1, 0, 2}, {1, 2, 0}, {2, 0, 1}, {2, 1, 0}
   };

   private ProjectedBresenhamFace() {
   }

   static Raster raster(List<Vec3> vertices) {
      Frame frame = Frame.create(vertices);
      if (frame == null) {
         return new Raster(Set.of(), Set.of(), Set.of(), Set.of(), null);
      }
      NavigableMap<Integer, Row> rows = new TreeMap<>();
      LinkedHashSet<Point2> ownedBoundaryColumns = new LinkedHashSet<>();
      for (int index = 0; index < frame.vertices().size(); index++) {
         Int3 first = frame.vertices().get(index);
         Int3 second = frame.vertices().get((index + 1) % frame.vertices().size());
         List<Point2> edge = path2d(new Point2(first.x(), first.y()), new Point2(second.x(), second.y()));
         ownedBoundaryColumns.addAll(edge);
         for (Point2 point : edge) {
            rows.compute(point.v(), (ignored, row) -> row == null ? new Row(point.u(), point.u()) : row.include(point.u()));
         }
      }

      GeneratedCells generated = scanCells(rows, frame);
      Set<BlockPos> cells = Collections.unmodifiableSet(generated.blocks());

      LinkedHashSet<BlockPos> logicalOutline = new LinkedHashSet<>();
      for (Point2 point : ownedBoundaryColumns) {
         logicalOutline.add(generated.requireBlock(point));
      }
      Set<BlockPos> outline = Collections.unmodifiableSet(logicalOutline);
      return new Raster(
         cells,
         cells,
         outline,
         outline,
         frame
      );
   }

   private static GeneratedCells scanCells(NavigableMap<Integer, Row> rows, Frame frame) {
      GeneratedCells result = new GeneratedCells();
      ScanRow previous = null;
      for (Map.Entry<Integer, Row> entry : rows.entrySet()) {
         ScanRow current = new ScanRow(entry.getKey(), entry.getValue());
         if (previous != null) {
            current = advance(previous, current, result, frame);
         }
         result.emit(current, frame);
         previous = current;
      }
      return result;
   }

   /** Emits a diagonal transition at the instant the scan advances to the next row. */
   private static ScanRow advance(
      ScanRow previous,
      ScanRow current,
      GeneratedCells output,
      Frame frame
   ) {
      Point2 from;
      Point2 to;
      if ((long)previous.span().maximum() + 1L == current.span().minimum()) {
         from = new Point2(previous.span().maximum(), previous.v());
         to = new Point2(current.span().minimum(), current.v());
      } else if ((long)current.span().maximum() + 1L == previous.span().minimum()) {
         from = new Point2(previous.span().minimum(), previous.v());
         to = new Point2(current.span().maximum(), current.v());
      } else {
         return current;
      }
      Point2 previousRoute = new Point2(to.u(), from.v());
      Point2 currentRoute = new Point2(from.u(), to.v());
      Point2 connector = chooseConnector(from, to, previousRoute, currentRoute, frame, output);
      if (connector.v() == to.v()) {
         return current.include(connector.u());
      }
      output.emit(connector, frame);
      return current;
   }

   private static Point2 chooseConnector(
      Point2 from,
      Point2 to,
      Point2 first,
      Point2 second,
      Frame frame,
      GeneratedCells generated
   ) {
      boolean firstInside = contains(frame.vertices(), first.u() + 0.5, first.v() + 0.5);
      boolean secondInside = contains(frame.vertices(), second.u() + 0.5, second.v() + 0.5);
      if (firstInside != secondInside) {
         return firstInside ? first : second;
      }
      RouteQuality firstQuality = RouteQuality.create(from, to, first, frame, generated);
      RouteQuality secondQuality = RouteQuality.create(from, to, second, frame, generated);
      return firstQuality.compareTo(secondQuality) <= 0 ? first : second;
   }

   private static boolean contains(List<Int3> polygon, double u, double v) {
      boolean inside = false;
      for (int current = 0, previous = polygon.size() - 1; current < polygon.size(); previous = current++) {
         Int3 a = polygon.get(previous);
         Int3 b = polygon.get(current);
         boolean crosses = (a.y() > v) != (b.y() > v);
         if (crosses) {
            double intersection = a.x() + (v - a.y()) * (b.x() - a.x()) / (double)(b.y() - a.y());
            if (u < intersection) {
               inside = !inside;
            }
         }
      }
      return inside;
   }

   private static List<Point2> path2d(Point2 from, Point2 to) {
      Point2 start = compare(from, to) <= 0 ? from : to;
      Point2 end = start == from ? to : from;
      long du = (long)end.u() - start.u();
      long dv = (long)end.v() - start.v();
      long au = Math.abs(du);
      long av = Math.abs(dv);
      boolean uMajor = au >= av;
      long major = uMajor ? au : av;
      long minor = uMajor ? av : au;
      int su = Long.compare(du, 0L);
      int sv = Long.compare(dv, 0L);
      ArrayList<Point2> result = new ArrayList<>((int)Math.min(Integer.MAX_VALUE, major + 1L));
      for (long step = 0; step <= major; step++) {
         long progress = major == 0L ? 0L : (step * minor + major / 2L) / major;
         long u = (long)start.u() + su * (uMajor ? step : progress);
         long v = (long)start.v() + sv * (uMajor ? progress : step);
         result.add(new Point2(safeInt(u), safeInt(v)));
      }
      if (start != from) {
         java.util.Collections.reverse(result);
      }
      return List.copyOf(result);
   }

   private static int compare(Point2 first, Point2 second) {
      int compared = Integer.compare(first.u(), second.u());
      return compared != 0 ? compared : Integer.compare(first.v(), second.v());
   }

   private static int compare(Int3 first, Int3 second) {
      int compared = Integer.compare(first.x(), second.x());
      if (compared == 0) {
         compared = Integer.compare(first.y(), second.y());
      }
      return compared != 0 ? compared : Integer.compare(first.z(), second.z());
   }

   private static int safeInt(long value) {
      return value < Integer.MIN_VALUE ? Integer.MIN_VALUE : value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int)value;
   }

   record Raster(
      Set<BlockPos> logical,
      Set<BlockPos> fill,
      Set<BlockPos> logicalOutline,
      Set<BlockPos> outline,
      Frame frame
   ) {
      boolean logicalSingleLayer() {
         if (this.frame == null) {
            return this.logical.isEmpty();
         }
         return this.logical.stream().map(this.frame::project).distinct().count() == this.logical.size();
      }

      int projectedColumnCount() {
         return this.frame == null ? 0 : (int)this.logical.stream().map(this.frame::project).distinct().count();
      }

      int maximumThickness() {
         if (this.frame == null) {
            return 0;
         }
         return this.fill.stream().collect(java.util.stream.Collectors.groupingBy(this.frame::project, java.util.stream.Collectors.counting()))
            .values().stream().mapToInt(Long::intValue).max().orElse(0);
      }
   }

   private record Point2(int u, int v) {
   }

   private record RouteQuality(int overshoot, int curvature, int travel, Point2 point)
      implements Comparable<RouteQuality> {
      static RouteQuality create(
         Point2 from,
         Point2 to,
         Point2 connector,
         Frame frame,
         GeneratedCells generated
      ) {
         int fromHeight = generated.height(from, frame);
         int connectorHeight = generated.height(connector, frame);
         int toHeight = generated.height(to, frame);
         int minimum = Math.min(fromHeight, toHeight);
         int maximum = Math.max(fromHeight, toHeight);
         int overshoot = connectorHeight < minimum
            ? minimum - connectorHeight
            : connectorHeight > maximum ? connectorHeight - maximum : 0;
         return new RouteQuality(
            overshoot,
            Math.abs((connectorHeight - fromHeight) - (toHeight - connectorHeight)),
            Math.abs(connectorHeight - fromHeight) + Math.abs(toHeight - connectorHeight),
            connector
         );
      }

      @Override
      public int compareTo(RouteQuality other) {
         int compared = Integer.compare(this.overshoot, other.overshoot);
         if (compared == 0) {
            compared = Integer.compare(this.curvature, other.curvature);
         }
         if (compared == 0) {
            compared = Integer.compare(this.travel, other.travel);
         }
         return compared != 0 ? compared : compare(this.point, other.point);
      }
   }

   private record Row(int minimum, int maximum) {
      Row include(int value) {
         return new Row(Math.min(this.minimum, value), Math.max(this.maximum, value));
      }
   }

   private record ScanRow(int v, Row span) {
      ScanRow include(int u) {
         return new ScanRow(this.v, this.span.include(u));
      }
   }

   /** Owns the one and only lift from a projected column to a world block. */
   private static final class GeneratedCells {
      private final Map<Point2, Integer> heights = new HashMap<>();
      private final Map<Point2, BlockPos> byColumn = new LinkedHashMap<>();
      private final LinkedHashSet<BlockPos> blocks = new LinkedHashSet<>();

      void emit(ScanRow row, Frame frame) {
         for (long u = row.span().minimum(); u <= row.span().maximum(); u++) {
            this.emit(new Point2((int)u, row.v()), frame);
         }
      }

      void emit(Point2 point, Frame frame) {
         if (this.byColumn.containsKey(point)) {
            throw new IllegalStateException("projection column emitted twice: " + point);
         }
         BlockPos block = frame.restore(new Int3(point.u(), point.v(), this.height(point, frame)));
         this.byColumn.put(point, block);
         if (!this.blocks.add(block)) {
            throw new IllegalStateException("two projection columns restored to " + block);
         }
      }

      int height(Point2 point, Frame frame) {
         return this.heights.computeIfAbsent(point, ignored -> frame.height(point.u(), point.v()));
      }

      BlockPos requireBlock(Point2 point) {
         BlockPos block = this.byColumn.get(point);
         if (block == null) {
            throw new IllegalStateException("owned boundary column was not generated: " + point);
         }
         return block;
      }

      LinkedHashSet<BlockPos> blocks() {
         return this.blocks;
      }
   }

   record Int3(int x, int y, int z) {
   }

   record Frame(BlockPos anchor, int[] order, int[] signs, List<Int3> vertices, Vec3 normal) {
      static Frame create(List<Vec3> source) {
         if (source.size() < 3) {
            return null;
         }
         List<BlockPos> blocks = source.stream().map(BlockPos::containing).toList();
         Candidate best = null;
         for (int anchorIndex = 0; anchorIndex < blocks.size(); anchorIndex++) {
            for (int direction : new int[]{1, -1}) {
               List<BlockPos> ordered = new ArrayList<>(blocks.size());
               for (int index = 0; index < blocks.size(); index++) {
                  ordered.add(blocks.get(Math.floorMod(anchorIndex + direction * index, blocks.size())));
               }
               for (int[] order : ORDERS) {
                  for (int mask = 0; mask < 8; mask++) {
                     int[] signs = {
                        (mask & 1) == 0 ? -1 : 1,
                        (mask & 2) == 0 ? -1 : 1,
                        (mask & 4) == 0 ? -1 : 1
                     };
                     Candidate candidate = Candidate.create(ordered, order, signs);
                     if (candidate != null && (best == null || candidate.compareTo(best) < 0)) {
                        best = candidate;
                     }
                  }
               }
            }
         }
         return best == null ? null : best.frame();
      }

      BlockPos restore(int u, int v) {
         return restore(new Int3(u, v, height(u, v)));
      }

      int height(int u, int v) {
         double realW = -(this.normal.x * u + this.normal.y * v) / this.normal.z;
         return safeInt((long)Math.floor(realW + 0.5));
      }

      BlockPos restore(Int3 point) {
         int[] local = {point.x(), point.y(), point.z()};
         int[] world = {this.anchor.getX(), this.anchor.getY(), this.anchor.getZ()};
         for (int axis = 0; axis < 3; axis++) {
            world[this.order[axis]] = safeInt((long)world[this.order[axis]] + (long)this.signs[axis] * local[axis]);
         }
         return new BlockPos(world[0], world[1], world[2]);
      }

      Point2 project(BlockPos position) {
         long[] delta = {
            (long)position.getX() - this.anchor.getX(),
            (long)position.getY() - this.anchor.getY(),
            (long)position.getZ() - this.anchor.getZ()
         };
         return new Point2(
            safeInt(delta[this.order[0]] * this.signs[0]),
            safeInt(delta[this.order[1]] * this.signs[1])
         );
      }
   }

   private record Candidate(Frame frame, List<Integer> key, List<Integer> frameConventionKey) implements Comparable<Candidate> {
      static Candidate create(List<BlockPos> ordered, int[] order, int[] signs) {
         BlockPos anchor = ordered.getFirst();
         ArrayList<Int3> local = new ArrayList<>(ordered.size());
         for (BlockPos point : ordered) {
            long[] delta = {
               (long)point.getX() - anchor.getX(),
               (long)point.getY() - anchor.getY(),
               (long)point.getZ() - anchor.getZ()
            };
            local.add(new Int3(
               safeInt(delta[order[0]] * signs[0]),
               safeInt(delta[order[1]] * signs[1]),
               safeInt(delta[order[2]] * signs[2])
            ));
         }
         Vec3 normal = normal(local);
         if (normal.lengthSqr() < 1.0E-12
            || Math.abs(normal.z) + 1.0E-9 < Math.max(Math.abs(normal.x), Math.abs(normal.y))) {
            return null;
         }
         if (normal.z < 0.0) {
            normal = normal.scale(-1.0);
         }
         ArrayList<Integer> key = new ArrayList<>();
         for (int index = 1; index < local.size(); index++) {
            Int3 point = local.get(index);
            key.add(point.x());
            key.add(point.y());
            key.add(point.z());
         }
         return new Candidate(
            new Frame(anchor, order.clone(), signs.clone(), List.copyOf(local), normal),
            List.copyOf(key),
            List.of(order[0], order[1], order[2], signs[0], signs[1], signs[2])
         );
      }

      @Override
      public int compareTo(Candidate other) {
         int size = Math.min(this.key.size(), other.key.size());
         for (int index = 0; index < size; index++) {
            int compared = Integer.compare(this.key.get(index), other.key.get(index));
            if (compared != 0) {
               return compared;
            }
         }
         int compared = Integer.compare(this.key.size(), other.key.size());
         if (compared != 0) {
            return compared;
         }
         for (int index = 0; index < this.frameConventionKey.size(); index++) {
            compared = Integer.compare(this.frameConventionKey.get(index), other.frameConventionKey.get(index));
            if (compared != 0) {
               return compared;
            }
         }
         return 0;
      }

      private static Vec3 normal(List<Int3> vertices) {
         Int3 origin = vertices.getFirst();
         for (int first = 1; first < vertices.size() - 1; first++) {
            for (int second = first + 1; second < vertices.size(); second++) {
               Vec3 a = vector(origin, vertices.get(first));
               Vec3 b = vector(origin, vertices.get(second));
               Vec3 normal = a.cross(b);
               if (normal.lengthSqr() >= 1.0E-12) {
                  return normal;
               }
            }
         }
         return Vec3.ZERO;
      }

      private static Vec3 vector(Int3 from, Int3 to) {
         return new Vec3(to.x() - from.x(), to.y() - from.y(), to.z() - from.z());
      }
   }
}
