package io.github.fastformer.client.render;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** Builds the complete exposed unit grid, then merges only collinear runs. */
public final class PendingPreviewGrid {
   private PendingPreviewGrid() {
   }

   public static List<Segment> build(Set<BlockPos> blocks) {
      if (blocks.isEmpty()) {
         return List.of();
      }
      HashSet<Segment> unitEdges = new HashSet<>();
      int checked = 0;
      for (BlockPos pos : blocks) {
         if ((checked++ & 255) == 0 && Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Superseded preview mesh");
         }
         for (Direction direction : Direction.values()) {
            if (!blocks.contains(pos.relative(direction))) {
               for (Segment edge : faceEdges(pos, direction)) {
                  unitEdges.add(edge);
               }
            }
         }
      }
      return mergeCollinear(unitEdges);
   }

   private static Segment[] faceEdges(BlockPos pos, Direction direction) {
      int x0 = pos.getX();
      int y0 = pos.getY();
      int z0 = pos.getZ();
      int x1 = x0 + 1;
      int y1 = y0 + 1;
      int z1 = z0 + 1;
      Point a;
      Point b;
      Point c;
      Point d;
      switch (direction) {
         case DOWN -> {
            a = new Point(x0, y0, z0); b = new Point(x1, y0, z0);
            c = new Point(x1, y0, z1); d = new Point(x0, y0, z1);
         }
         case UP -> {
            a = new Point(x0, y1, z0); b = new Point(x0, y1, z1);
            c = new Point(x1, y1, z1); d = new Point(x1, y1, z0);
         }
         case NORTH -> {
            a = new Point(x0, y0, z0); b = new Point(x0, y1, z0);
            c = new Point(x1, y1, z0); d = new Point(x1, y0, z0);
         }
         case SOUTH -> {
            a = new Point(x0, y0, z1); b = new Point(x1, y0, z1);
            c = new Point(x1, y1, z1); d = new Point(x0, y1, z1);
         }
         case WEST -> {
            a = new Point(x0, y0, z0); b = new Point(x0, y0, z1);
            c = new Point(x0, y1, z1); d = new Point(x0, y1, z0);
         }
         case EAST -> {
            a = new Point(x1, y0, z0); b = new Point(x1, y1, z0);
            c = new Point(x1, y1, z1); d = new Point(x1, y0, z1);
         }
         default -> throw new IllegalStateException("Unknown direction " + direction);
      }
      return new Segment[]{Segment.of(a, b), Segment.of(b, c), Segment.of(c, d), Segment.of(d, a)};
   }

   private static List<Segment> mergeCollinear(Set<Segment> edges) {
      Map<Line, List<Interval>> lines = new HashMap<>();
      for (Segment edge : edges) {
         Line line = Line.of(edge);
         lines.computeIfAbsent(line, ignored -> new ArrayList<>()).add(line.interval(edge));
      }
      ArrayList<Segment> merged = new ArrayList<>();
      for (Map.Entry<Line, List<Interval>> entry : lines.entrySet()) {
         List<Interval> intervals = entry.getValue();
         intervals.sort(Comparator.comparingInt(Interval::start));
         int start = intervals.getFirst().start();
         int end = intervals.getFirst().end();
         for (int index = 1; index < intervals.size(); index++) {
            Interval next = intervals.get(index);
            if (next.start() <= end) {
               end = Math.max(end, next.end());
            } else {
               merged.add(entry.getKey().segment(start, end));
               start = next.start();
               end = next.end();
            }
         }
         merged.add(entry.getKey().segment(start, end));
      }
      return List.copyOf(merged);
   }

   public record Point(int x, int y, int z) implements Comparable<Point> {
      @Override
      public int compareTo(Point other) {
         int xComparison = Integer.compare(this.x, other.x);
         if (xComparison != 0) {
            return xComparison;
         }
         int yComparison = Integer.compare(this.y, other.y);
         return yComparison != 0 ? yComparison : Integer.compare(this.z, other.z);
      }
   }

   public record Segment(Point from, Point to) {
      public static Segment of(Point first, Point second) {
         return first.compareTo(second) <= 0 ? new Segment(first, second) : new Segment(second, first);
      }
   }

   private record Interval(int start, int end) {
   }

   private record Line(Direction.Axis axis, int fixedA, int fixedB) {
      static Line of(Segment segment) {
         Point from = segment.from();
         Point to = segment.to();
         if (from.x() != to.x()) {
            return new Line(Direction.Axis.X, from.y(), from.z());
         }
         if (from.y() != to.y()) {
            return new Line(Direction.Axis.Y, from.x(), from.z());
         }
         return new Line(Direction.Axis.Z, from.x(), from.y());
      }

      Interval interval(Segment segment) {
         return switch (this.axis) {
            case X -> new Interval(segment.from().x(), segment.to().x());
            case Y -> new Interval(segment.from().y(), segment.to().y());
            case Z -> new Interval(segment.from().z(), segment.to().z());
         };
      }

      Segment segment(int start, int end) {
         return switch (this.axis) {
            case X -> Segment.of(new Point(start, this.fixedA, this.fixedB), new Point(end, this.fixedA, this.fixedB));
            case Y -> Segment.of(new Point(this.fixedA, start, this.fixedB), new Point(this.fixedA, end, this.fixedB));
            case Z -> Segment.of(new Point(this.fixedA, this.fixedB, start), new Point(this.fixedA, this.fixedB, end));
         };
      }
   }
}
