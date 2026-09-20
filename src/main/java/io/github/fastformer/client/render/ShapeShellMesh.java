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
      Builder builder = builder(parts);
      while (!builder.complete()) {
         builder.step(Integer.MAX_VALUE);
      }
      return builder.mesh();
   }

   public static Builder builder(List<Part> parts) {
      return new Builder(parts);
   }

   private static void addBoxFaces(Map<Plane, List<RawFace>> planes, AABB box, Part part) {
      long x0 = q(box.minX);
      long y0 = q(box.minY);
      long z0 = q(box.minZ);
      long x1 = q(box.maxX);
      long y1 = q(box.maxY);
      long z1 = q(box.maxZ);
      if (!part.hiddenFaces().contains(Direction.DOWN)) addFace(planes, Direction.DOWN, y0, x0, x1, z0, z1, part);
      if (!part.hiddenFaces().contains(Direction.UP)) addFace(planes, Direction.UP, y1, x0, x1, z0, z1, part);
      if (!part.hiddenFaces().contains(Direction.NORTH)) addFace(planes, Direction.NORTH, z0, x0, x1, y0, y1, part);
      if (!part.hiddenFaces().contains(Direction.SOUTH)) addFace(planes, Direction.SOUTH, z1, x0, x1, y0, y1, part);
      if (!part.hiddenFaces().contains(Direction.WEST)) addFace(planes, Direction.WEST, x0, z0, z1, y0, y1, part);
      if (!part.hiddenFaces().contains(Direction.EAST)) addFace(planes, Direction.EAST, x1, z0, z1, y0, y1, part);
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
      Plane plane = new Plane(direction.getAxis(), planeCoordinate, part.cullGroup());
      planes.computeIfAbsent(plane, ignored -> new ArrayList<>()).add(
         new RawFace(direction, u0, u1, v0, v1, part.faceColor(), part.outlineColor(), part.outline())
      );
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

   /** Incrementally builds a mesh. Sorting and final immutable copies are not budgeted. */
   public static final class Builder {
      private final List<Part> parts;
      private final Map<Plane, List<RawFace>> planes = new HashMap<>();
      private final ArrayList<Face> faces = new ArrayList<>();
      private final Set<PlaneEdge> coplanarBoundary = new HashSet<>();
      private final Map<StyledLine, List<Interval>> lines = new HashMap<>();
      private final ArrayList<StyledEdge> edges = new ArrayList<>();
      private Stage stage = Stage.BOXES;
      private int partIndex;
      private int boxIndex;
      private java.util.Iterator<Map.Entry<Plane, List<RawFace>>> planeIterator;
      private Map.Entry<Plane, List<RawFace>> planeEntry;
      private List<Long> us;
      private List<Long> vs;
      private Map<Long, Integer> uIndices;
      private Map<Long, Integer> vIndices;
      private HashMap<Cell, RawFace> parity;
      private TreeSet<Long> uCoordinates;
      private TreeSet<Long> vCoordinates;
      private int coordinateFaceIndex;
      private int rawFaceIndex;
      private int cellU;
      private int cellV;
      private java.util.Iterator<Map.Entry<Cell, RawFace>> visibleCellIterator;
      private int faceIndex;
      private int faceEdgeIndex;
      private List<Edge> faceEdges = List.of();
      private java.util.Iterator<PlaneEdge> boundaryIterator;
      private java.util.Iterator<Map.Entry<StyledLine, List<Interval>>> lineIterator;
      private Map.Entry<StyledLine, List<Interval>> lineEntry;
      private int intervalIndex;
      private long intervalStart;
      private long intervalEnd;
      private Mesh mesh;

      private Builder(List<Part> parts) {
         this.parts = List.copyOf(parts);
      }

      /** Advances at most {@code workUnits} loop items and reports completion. */
      public boolean step(int workUnits) {
         if (workUnits <= 0) {
            throw new IllegalArgumentException("workUnits must be positive");
         }
         int remaining = workUnits;
         while (this.stage != Stage.COMPLETE && remaining > 0) {
            remaining -= switch (this.stage) {
               case BOXES -> this.addNextBox();
               case PLANE_COORDINATES -> this.collectNextPlaneCoordinates();
               case CELLS -> this.toggleNextCell();
               case VISIBLE_FACES -> this.addNextVisibleFace();
               case FACE_EDGES -> this.toggleNextFaceEdge();
               case GROUP_LINES -> this.groupNextBoundaryEdge();
               case MERGE_LINES -> this.mergeNextInterval();
               case COMPLETE -> 0;
            };
         }
         return this.complete();
      }

      public boolean complete() {
         return this.stage == Stage.COMPLETE;
      }

      public Mesh mesh() {
         if (!this.complete()) {
            throw new IllegalStateException("mesh is not complete");
         }
         return this.mesh;
      }

      private int addNextBox() {
         if (this.partIndex < this.parts.size()) {
            Part part = this.parts.get(this.partIndex);
            if (this.boxIndex < part.boxes().size()) {
               addBoxFaces(this.planes, part.boxes().get(this.boxIndex++), part);
               return 1;
            }
            this.partIndex++;
            this.boxIndex = 0;
            return 1;
         }
         this.planeIterator = this.planes.entrySet().iterator();
         this.startNextPlane();
         return 0;
      }

      private int toggleNextCell() {
         if (this.rawFaceIndex >= this.planeEntry.getValue().size()) {
            this.visibleCellIterator = this.parity.entrySet().iterator();
            this.stage = Stage.VISIBLE_FACES;
            return 0;
         }
         RawFace source = this.planeEntry.getValue().get(this.rawFaceIndex);
         if (this.uIndices.get(source.u0()).equals(this.uIndices.get(source.u1()))
            || this.vIndices.get(source.v0()).equals(this.vIndices.get(source.v1()))) {
            this.rawFaceIndex++;
            this.initializeCellCursor();
            return 1;
         }
         Cell cell = new Cell(this.cellU, this.cellV);
         if (this.parity.containsKey(cell)) {
            this.parity.remove(cell);
         } else {
            this.parity.put(cell, source);
         }
         this.cellV++;
         if (this.cellV >= this.vIndices.get(source.v1())) {
            this.cellU++;
            this.cellV = this.vIndices.get(source.v0());
            if (this.cellU >= this.uIndices.get(source.u1())) {
               this.rawFaceIndex++;
               this.initializeCellCursor();
            }
         }
         return 1;
      }

      private int collectNextPlaneCoordinates() {
         if (this.coordinateFaceIndex < this.planeEntry.getValue().size()) {
            RawFace face = this.planeEntry.getValue().get(this.coordinateFaceIndex++);
            this.uCoordinates.add(face.u0());
            this.uCoordinates.add(face.u1());
            this.vCoordinates.add(face.v0());
            this.vCoordinates.add(face.v1());
            return 1;
         }
         this.us = List.copyOf(this.uCoordinates);
         this.vs = List.copyOf(this.vCoordinates);
         this.uIndices = indices(this.us);
         this.vIndices = indices(this.vs);
         this.parity = new HashMap<>();
         this.rawFaceIndex = 0;
         this.initializeCellCursor();
         this.stage = Stage.CELLS;
         return 0;
      }

      private int addNextVisibleFace() {
         if (this.visibleCellIterator.hasNext()) {
            Map.Entry<Cell, RawFace> entry = this.visibleCellIterator.next();
            Cell cell = entry.getKey();
            RawFace source = entry.getValue();
            this.faces.add(new Face(source.direction(), this.planeEntry.getKey(),
               this.us.get(cell.u()), this.us.get(cell.u() + 1), this.vs.get(cell.v()), this.vs.get(cell.v() + 1),
               source.faceColor(), source.outlineColor(), source.outline()));
            return 1;
         }
         this.startNextPlane();
         return 0;
      }

      private void startNextPlane() {
         if (!this.planeIterator.hasNext()) {
            this.stage = Stage.FACE_EDGES;
            return;
         }
         this.planeEntry = this.planeIterator.next();
         this.uCoordinates = new TreeSet<>();
         this.vCoordinates = new TreeSet<>();
         this.coordinateFaceIndex = 0;
         this.stage = Stage.PLANE_COORDINATES;
      }

      private void initializeCellCursor() {
         if (this.rawFaceIndex < this.planeEntry.getValue().size()) {
            RawFace face = this.planeEntry.getValue().get(this.rawFaceIndex);
            this.cellU = this.uIndices.get(face.u0());
            this.cellV = this.vIndices.get(face.v0());
         }
      }

      private int toggleNextFaceEdge() {
         if (this.faceIndex < this.faces.size()) {
            Face face = this.faces.get(this.faceIndex);
            if (!face.outline()) {
               this.faceIndex++;
               return 1;
            }
            if (this.faceEdges.isEmpty()) {
               this.faceEdges = face.edges();
            }
            PlaneEdge edge = new PlaneEdge(face.plane(), this.faceEdges.get(this.faceEdgeIndex++), face.outlineColor());
            if (!this.coplanarBoundary.add(edge)) {
               this.coplanarBoundary.remove(edge);
            }
            if (this.faceEdgeIndex == this.faceEdges.size()) {
               this.faceIndex++;
               this.faceEdgeIndex = 0;
               this.faceEdges = List.of();
            }
            return 1;
         }
         this.boundaryIterator = this.coplanarBoundary.iterator();
         this.stage = Stage.GROUP_LINES;
         return 0;
      }

      private int groupNextBoundaryEdge() {
         if (this.boundaryIterator.hasNext()) {
            PlaneEdge planeEdge = this.boundaryIterator.next();
            StyledLine line = StyledLine.of(planeEdge.edge(), planeEdge.color());
            this.lines.computeIfAbsent(line, ignored -> new ArrayList<>()).add(line.interval(planeEdge.edge()));
            return 1;
         }
         this.lineIterator = this.lines.entrySet().iterator();
         this.stage = Stage.MERGE_LINES;
         return 0;
      }

      private int mergeNextInterval() {
         if (this.lineEntry == null) {
            if (!this.lineIterator.hasNext()) {
               this.mesh = new Mesh(this.faces, this.edges);
               this.stage = Stage.COMPLETE;
               return 0;
            }
            this.lineEntry = this.lineIterator.next();
            List<Interval> intervals = this.lineEntry.getValue();
            intervals.sort(Comparator.comparingLong(Interval::start));
            this.intervalStart = intervals.getFirst().start();
            this.intervalEnd = intervals.getFirst().end();
            this.intervalIndex = 1;
         }
         List<Interval> intervals = this.lineEntry.getValue();
         if (this.intervalIndex < intervals.size()) {
            Interval next = intervals.get(this.intervalIndex++);
            if (next.start() <= this.intervalEnd) {
               this.intervalEnd = Math.max(this.intervalEnd, next.end());
            } else {
               this.edges.add(this.lineEntry.getKey().edge(this.intervalStart, this.intervalEnd));
               this.intervalStart = next.start();
               this.intervalEnd = next.end();
            }
            return 1;
         }
         this.edges.add(this.lineEntry.getKey().edge(this.intervalStart, this.intervalEnd));
         this.lineEntry = null;
         return 1;
      }

      private enum Stage {
         BOXES,
         PLANE_COORDINATES,
         CELLS,
         VISIBLE_FACES,
         FACE_EDGES,
         GROUP_LINES,
         MERGE_LINES,
         COMPLETE
      }
   }

   public record Color(float red, float green, float blue) {
      public static final Color WHITE = new Color(1.0F, 1.0F, 1.0F);
      public static final Color BLACK = new Color(0.0F, 0.0F, 0.0F);
   }

   public record Part(
      List<AABB> boxes,
      Color faceColor,
      Color outlineColor,
      boolean outline,
      Set<Direction> hiddenFaces,
      long cullGroup
   ) {
      public Part {
         boxes = List.copyOf(boxes);
         hiddenFaces = Set.copyOf(hiddenFaces);
      }

      public Part(
         List<AABB> boxes,
         Color faceColor,
         Color outlineColor,
         boolean outline,
         Set<Direction> hiddenFaces
      ) {
         this(boxes, faceColor, outlineColor, outline, hiddenFaces, 0L);
      }

      public Part(List<AABB> boxes, Color faceColor, Color outlineColor, boolean outline) {
         this(boxes, faceColor, outlineColor, outline, Set.of(), 0L);
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

   private record Plane(Direction.Axis axis, long coordinate, long cullGroup) {
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
