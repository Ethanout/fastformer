package io.github.fastformer.client.render;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Builds a visible shell from block interaction-shape boxes. */
public final class ShapeShellMesh {
   private static final double SCALE = 1_000_000.0;

   private ShapeShellMesh() {
   }

   public static Mesh build(List<Part> parts) {
      if (parts.isEmpty()) {
         return Mesh.empty();
      }
      Map<Plane, List<RawFace>> planes = new HashMap<>();
      for (Part part : parts) {
         for (AABB box : part.boxes()) {
            addBoxFaces(planes, box, part);
         }
      }

      ArrayList<Face> faces = new ArrayList<>();
      for (Map.Entry<Plane, List<RawFace>> entry : planes.entrySet()) {
         splitVisibleFaces(entry.getKey(), entry.getValue(), faces);
      }

      Set<PlaneEdge> coplanarBoundary = new HashSet<>();
      for (Face face : faces) {
         if (!face.outline()) {
            continue;
         }
         for (Edge edge : face.edges()) {
            PlaneEdge planeEdge = new PlaneEdge(face.plane(), edge, face.outlineColor());
            if (!coplanarBoundary.add(planeEdge)) {
               coplanarBoundary.remove(planeEdge);
            }
         }
      }

      Map<StyledLine, List<Interval>> lines = new HashMap<>();
      for (PlaneEdge planeEdge : coplanarBoundary) {
         StyledLine line = StyledLine.of(planeEdge.edge(), planeEdge.color());
         lines.computeIfAbsent(line, ignored -> new ArrayList<>()).add(line.interval(planeEdge.edge()));
      }
      ArrayList<StyledEdge> edges = new ArrayList<>();
      for (Map.Entry<StyledLine, List<Interval>> entry : lines.entrySet()) {
         List<Interval> intervals = entry.getValue();
         intervals.sort(Comparator.comparingLong(Interval::start));
         long start = intervals.getFirst().start();
         long end = intervals.getFirst().end();
         for (int index = 1; index < intervals.size(); index++) {
            Interval next = intervals.get(index);
            if (next.start() <= end) {
               end = Math.max(end, next.end());
            } else {
               edges.add(entry.getKey().edge(start, end));
               start = next.start();
               end = next.end();
            }
         }
         edges.add(entry.getKey().edge(start, end));
      }
      return new Mesh(List.copyOf(faces), List.copyOf(edges));
   }

   private static void addBoxFaces(Map<Plane, List<RawFace>> planes, AABB box, Part part) {
      long x0 = q(box.minX);
      long y0 = q(box.minY);
      long z0 = q(box.minZ);
      long x1 = q(box.maxX);
      long y1 = q(box.maxY);
      long z1 = q(box.maxZ);
      addFace(planes, Direction.DOWN, y0, x0, x1, z0, z1, part);
      addFace(planes, Direction.UP, y1, x0, x1, z0, z1, part);
      addFace(planes, Direction.NORTH, z0, x0, x1, y0, y1, part);
      addFace(planes, Direction.SOUTH, z1, x0, x1, y0, y1, part);
      addFace(planes, Direction.WEST, x0, z0, z1, y0, y1, part);
      addFace(planes, Direction.EAST, x1, z0, z1, y0, y1, part);
   }

   private static void addFace(
      Map<Plane, List<RawFace>> planes,
      Direction direction,
      long planeCoordinate,
      long u0,
      long u1,
      long v0,
      long v1,
      Part part
   ) {
      Plane plane = new Plane(direction.getAxis(), planeCoordinate);
      planes.computeIfAbsent(plane, ignored -> new ArrayList<>()).add(
         new RawFace(direction, u0, u1, v0, v1, part.faceColor(), part.outlineColor(), part.outline())
      );
   }

   private static void splitVisibleFaces(Plane plane, List<RawFace> rawFaces, List<Face> output) {
      TreeSet<Long> uCoordinates = new TreeSet<>();
      TreeSet<Long> vCoordinates = new TreeSet<>();
      for (RawFace face : rawFaces) {
         uCoordinates.add(face.u0());
         uCoordinates.add(face.u1());
         vCoordinates.add(face.v0());
         vCoordinates.add(face.v1());
      }
      List<Long> us = List.copyOf(uCoordinates);
      List<Long> vs = List.copyOf(vCoordinates);
      Map<Long, Integer> uIndices = indices(us);
      Map<Long, Integer> vIndices = indices(vs);
      HashMap<Cell, RawFace> parity = new HashMap<>();
      for (RawFace face : rawFaces) {
         int u0 = uIndices.get(face.u0());
         int u1 = uIndices.get(face.u1());
         int v0 = vIndices.get(face.v0());
         int v1 = vIndices.get(face.v1());
         for (int u = u0; u < u1; u++) {
            for (int v = v0; v < v1; v++) {
               Cell cell = new Cell(u, v);
               if (parity.containsKey(cell)) {
                  parity.remove(cell);
               } else {
                  parity.put(cell, face);
               }
            }
         }
      }
      parity.forEach((cell, source) -> output.add(new Face(
         source.direction(),
         plane,
         us.get(cell.u()),
         us.get(cell.u() + 1),
         vs.get(cell.v()),
         vs.get(cell.v() + 1),
         source.faceColor(),
         source.outlineColor(),
         source.outline()
      )));
   }

   private static Map<Long, Integer> indices(List<Long> coordinates) {
      HashMap<Long, Integer> result = new HashMap<>();
      for (int index = 0; index < coordinates.size(); index++) {
         result.put(coordinates.get(index), index);
      }
      return result;
   }

   private static long q(double value) {
      return Math.round(value * SCALE);
   }

   private static double d(long value) {
      return value / SCALE;
   }

   public record Color(float red, float green, float blue) {
      public static final Color WHITE = new Color(1.0F, 1.0F, 1.0F);
      public static final Color BLACK = new Color(0.0F, 0.0F, 0.0F);
   }

   public record Part(List<AABB> boxes, Color faceColor, Color outlineColor, boolean outline) {
      public Part {
         boxes = List.copyOf(boxes);
      }
   }

   public record Mesh(List<Face> faces, List<StyledEdge> edges) {
      public Mesh {
         faces = List.copyOf(faces);
         edges = List.copyOf(edges);
      }

      public static Mesh empty() {
         return new Mesh(List.of(), List.of());
      }
   }

   public record Face(
      Direction direction,
      Plane plane,
      long u0,
      long u1,
      long v0,
      long v1,
      Color color,
      Color outlineColor,
      boolean outline
   ) {
      public List<Vec3> vertices() {
         double p = d(this.plane.coordinate());
         double u0d = d(this.u0);
         double u1d = d(this.u1);
         double v0d = d(this.v0);
         double v1d = d(this.v1);
         return switch (this.plane.axis()) {
            case Y -> List.of(
               new Vec3(u0d, p, v0d), new Vec3(u1d, p, v0d),
               new Vec3(u1d, p, v1d), new Vec3(u0d, p, v1d)
            );
            case Z -> List.of(
               new Vec3(u0d, v0d, p), new Vec3(u1d, v0d, p),
               new Vec3(u1d, v1d, p), new Vec3(u0d, v1d, p)
            );
            case X -> List.of(
               new Vec3(p, v0d, u0d), new Vec3(p, v0d, u1d),
               new Vec3(p, v1d, u1d), new Vec3(p, v1d, u0d)
            );
         };
      }

      List<Edge> edges() {
         List<Vec3> vertices = this.vertices();
         return List.of(
            Edge.of(vertices.get(0), vertices.get(1)),
            Edge.of(vertices.get(1), vertices.get(2)),
            Edge.of(vertices.get(2), vertices.get(3)),
            Edge.of(vertices.get(3), vertices.get(0))
         );
      }
   }

   private record Plane(Direction.Axis axis, long coordinate) {
   }

   private record RawFace(
      Direction direction,
      long u0,
      long u1,
      long v0,
      long v1,
      Color faceColor,
      Color outlineColor,
      boolean outline
   ) {
   }

   private record Cell(int u, int v) {
   }

   private record Point(long x, long y, long z) implements Comparable<Point> {
      static Point of(Vec3 point) {
         return new Point(q(point.x), q(point.y), q(point.z));
      }

      Vec3 vec3() {
         return new Vec3(d(this.x), d(this.y), d(this.z));
      }

      @Override
      public int compareTo(Point other) {
         int xComparison = Long.compare(this.x, other.x);
         if (xComparison != 0) {
            return xComparison;
         }
         int yComparison = Long.compare(this.y, other.y);
         return yComparison != 0 ? yComparison : Long.compare(this.z, other.z);
      }
   }

   private record Edge(Point from, Point to) {
      static Edge of(Vec3 first, Vec3 second) {
         Point a = Point.of(first);
         Point b = Point.of(second);
         return a.compareTo(b) <= 0 ? new Edge(a, b) : new Edge(b, a);
      }
   }

   private record PlaneEdge(Plane plane, Edge edge, Color color) {
   }

   private record Interval(long start, long end) {
   }

   private record StyledLine(Direction.Axis axis, long fixedA, long fixedB, Color color) {
      static StyledLine of(Edge edge, Color color) {
         Point from = edge.from();
         Point to = edge.to();
         if (from.x() != to.x()) {
            return new StyledLine(Direction.Axis.X, from.y(), from.z(), color);
         }
         if (from.y() != to.y()) {
            return new StyledLine(Direction.Axis.Y, from.x(), from.z(), color);
         }
         return new StyledLine(Direction.Axis.Z, from.x(), from.y(), color);
      }

      Interval interval(Edge edge) {
         return switch (this.axis) {
            case X -> new Interval(edge.from().x(), edge.to().x());
            case Y -> new Interval(edge.from().y(), edge.to().y());
            case Z -> new Interval(edge.from().z(), edge.to().z());
         };
      }

      StyledEdge edge(long start, long end) {
         return switch (this.axis) {
            case X -> new StyledEdge(
               new Point(start, this.fixedA, this.fixedB).vec3(),
               new Point(end, this.fixedA, this.fixedB).vec3(),
               this.color
            );
            case Y -> new StyledEdge(
               new Point(this.fixedA, start, this.fixedB).vec3(),
               new Point(this.fixedA, end, this.fixedB).vec3(),
               this.color
            );
            case Z -> new StyledEdge(
               new Point(this.fixedA, this.fixedB, start).vec3(),
               new Point(this.fixedA, this.fixedB, end).vec3(),
               this.color
            );
         };
      }
   }

   public record StyledEdge(Vec3 from, Vec3 to, Color color) {
   }
}
