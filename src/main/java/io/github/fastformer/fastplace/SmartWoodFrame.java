package io.github.fastformer.fastplace;

import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Set;
import io.github.fastformer.fastplace.geometry.generation.PlanarFaceGeometry;
import io.github.fastformer.fastplace.geometry.generation.LineGenerator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** Applies stable axis orientation to blocks exposing the vanilla AXIS property. */
final class SmartWoodFrame {
   private SmartWoodFrame() {
   }

   record Edge(BlockPos from, BlockPos to) {
      Edge {
         from = from == null ? BlockPos.ZERO : from.immutable();
         to = to == null ? from : to.immutable();
      }

      Vec3 direction() {
         return SmartWoodFrame.direction(from, to);
      }
   }

   record Config(Direction.Axis baseAxis, List<BlockPos> guidePoints, List<Edge> edges) {
      Config(Direction.Axis baseAxis, List<BlockPos> guidePoints) {
         this(baseAxis, guidePoints, List.of());
      }

      Config {
         baseAxis = baseAxis == null ? Direction.Axis.Y : baseAxis;
         guidePoints = guidePoints == null ? List.of() : List.copyOf(guidePoints);
         edges = edges == null ? List.of() : List.copyOf(edges);
      }
   }

   /** Builds the actual outline edges used by the geometry generators. */
   static List<Edge> edgeGuides(
      List<BlockPos> points,
      io.github.fastformer.fastplace.FaceMode faceMode,
      boolean polygonHeightConfirmed,
      PolygonVolumeShape volumeShape
   ) {
      if (points == null || points.isEmpty()) {
         return List.of();
      }
      if (points.size() == 1) {
         return List.of();
      }
      if (points.size() == 2) {
         return List.of(new Edge(points.get(0), points.get(1)));
      }

      List<BlockPos> base;
      boolean volume = polygonHeightConfirmed && faceMode == io.github.fastformer.fastplace.FaceMode.POLYGON
         || faceMode != io.github.fastformer.fastplace.FaceMode.POLYGON && points.size() >= 4;
      if (faceMode == io.github.fastformer.fastplace.FaceMode.POLYGON) {
         int count = volume ? points.size() - 1 : points.size();
         base = points.subList(0, Math.max(0, count));
      } else {
         base = PlanarFaceGeometry.vertices(points, faceMode).stream().map(BlockPos::containing).toList();
      }
      if (base.size() < 2) {
         return List.of();
      }

      java.util.ArrayList<Edge> result = new java.util.ArrayList<>();
      for (int index = 0; index < base.size(); index++) {
         result.add(new Edge(base.get(index), base.get((index + 1) % base.size())));
      }
      if (!volume) {
         return List.copyOf(result);
      }

      BlockPos anchor = points.get(Math.min(2, base.size() - 1));
      // Non-polygon prism generation consumes point 3 as its extrusion tip;
      // polygon volumes reserve the final point for that same role.
      BlockPos tip = faceMode == io.github.fastformer.fastplace.FaceMode.POLYGON
         ? points.getLast()
         : points.get(3);
      BlockPos offset = tip.subtract(anchor);
      if (faceMode == io.github.fastformer.fastplace.FaceMode.POLYGON
         && volumeShape == PolygonVolumeShape.APEX) {
         List<Vec3> baseVectors = base.stream().map(Vec3::atCenterOf).toList();
         Vec3 normal = PlanarFaceGeometry.normal(baseVectors);
         Vec3 center = baseVectors.stream().reduce(Vec3.ZERO, Vec3::add)
            .scale(1.0 / baseVectors.size());
         Vec3 extrusion = Vec3.atCenterOf(tip).subtract(Vec3.atCenterOf(anchor));
         Vec3 apex = center.add(normal.scale(extrusion.dot(normal)));
         BlockPos apexPos = BlockPos.containing(apex);
         for (BlockPos corner : base) {
            result.add(new Edge(corner, apexPos));
         }
      } else {
         for (Edge edge : List.copyOf(result)) {
            result.add(new Edge(edge.from().offset(offset), edge.to().offset(offset)));
         }
         for (BlockPos corner : base) {
            result.add(new Edge(corner, corner.offset(offset)));
         }
      }
      return List.copyOf(result);
   }

   static Map<BlockPos, BlockState> resolve(Set<BlockPos> positions, BlockState prototype, Config config) {
      if (config == null || !prototype.hasProperty(BlockStateProperties.AXIS)) {
         return Map.of();
      }
      Map<BlockPos, BlockState> result = new HashMap<>();
      Map<BlockPos, Direction.Axis> edgeAxes = resolveEdgeAxes(positions, config);
      for (BlockPos pos : positions) {
         // Smart orientation is an edge property.  A position which is not
         // assigned to an edge keeps the configured placement axis instead of
         // inferring a direction from its individual neighbours.
         Direction.Axis axis = edgeAxes.getOrDefault(pos, config.baseAxis());
         result.put(pos, prototype.setValue(BlockStateProperties.AXIS, axis));
      }
      return Map.copyOf(result);
   }

   static Direction.Axis axisForTest(Set<BlockPos> positions, BlockPos pos, Config config) {
      return resolveEdgeAxes(positions, config).getOrDefault(pos, config.baseAxis());
   }

   private static Map<BlockPos, Direction.Axis> resolveEdgeAxes(
      Set<BlockPos> positions, Config config
   ) {
      List<Edge> edges = config.edges().isEmpty()
         ? inferredEdges(positions, config.guidePoints())
         : config.edges();
      if (edges.isEmpty()) {
         return Map.of();
      }

      // First assign exact rasterized members.  Keep the complete edge
      // membership: a corner can belong to two or three edges, and choosing
      // the configured face axis there makes its wood direction arbitrary.
      Map<BlockPos, List<Edge>> memberships = new HashMap<>();
      Map<Edge, Direction.Axis> edgeAxes = new HashMap<>();
      Map<Edge, Vec3> edgeDirections = new HashMap<>();
      for (Edge edge : edges) {
         Vec3 direction = edge.direction();
         edgeDirections.put(edge, direction);
         Direction.Axis axis = direction.lengthSqr() < 1.0E-7
            ? config.baseAxis()
            : dominantComponent(direction.x, direction.y, direction.z);
         edgeAxes.put(edge, axis);
         for (BlockPos member : LineGenerator.path(edge.from(), edge.to())) {
            if (!positions.contains(member)) {
               continue;
            }
            memberships.computeIfAbsent(member, ignored -> new java.util.ArrayList<>()).add(edge);
         }
      }
      Map<BlockPos, Direction.Axis> result = new HashMap<>();
      for (Map.Entry<BlockPos, List<Edge>> entry : memberships.entrySet()) {
         List<Edge> incident = entry.getValue();
         result.put(entry.getKey(), incident.size() == 1
            ? edgeAxes.get(incident.getFirst())
            : dominantIncidentAxis(incident, edgeDirections, config.baseAxis()));
      }
      // Rasterizers may emit a connector block one voxel away from the exact
      // digital edge path.  It still inherits the already-computed axis of the
      // nearest edge; no direction is inferred from this block's neighbours.
      for (BlockPos position : positions) {
         if (!result.containsKey(position)) {
            result.put(position, nearestEdgeAxis(position, edges, edgeAxes, config.baseAxis()));
         }
      }
      return result;
   }

   /**
    * Chooses the axis from the largest absolute component of all incident
    * normalized edge directions (n1/n2/n3 at a corner).  The configured base
    * axis is used only when all incident edges are degenerate.
    */
   private static Direction.Axis dominantIncidentAxis(
      List<Edge> edges, Map<Edge, Vec3> directions, Direction.Axis fallback
   ) {
      double[] scores = new double[Direction.Axis.values().length];
      for (Edge edge : edges) {
         Vec3 direction = directions.getOrDefault(edge, Vec3.ZERO);
         scores[0] = Math.max(scores[0], Math.abs(direction.x));
         scores[1] = Math.max(scores[1], Math.abs(direction.y));
         scores[2] = Math.max(scores[2], Math.abs(direction.z));
      }
      double maximum = Math.max(scores[0], Math.max(scores[1], scores[2]));
      if (maximum <= 1.0E-7) {
         return fallback;
      }
      double epsilon = 1.0E-7;
      // A true orthogonal corner has equal components (for example X/Y/Z all
      // equal to one).  Keep the tie break independent from placement face
      // or base axis so the same frame always produces the same result.
      for (Direction.Axis axis : Direction.Axis.values()) {
         if (scores[axis.ordinal()] >= maximum - epsilon) {
            return axis;
         }
      }
      return fallback;
   }

   private static List<Edge> guideEdges(List<BlockPos> points) {
      if (points.size() < 2) {
         return List.of();
      }
      java.util.ArrayList<Edge> edges = new java.util.ArrayList<>();
      int count = points.size() == 2 ? 1 : points.size();
      for (int index = 0; index < count; index++) {
         edges.add(new Edge(points.get(index), points.get((index + 1) % points.size())));
      }
      return List.copyOf(edges);
   }

   /**
    * Builds authored-like edges for legacy callers that only supplied guide
    * points.  Runs are extracted once from the complete position set; blocks
    * are never assigned an axis from their immediate neighbours.
    */
   private static List<Edge> inferredEdges(Set<BlockPos> positions, List<BlockPos> guidePoints) {
      java.util.ArrayList<Edge> edges = new java.util.ArrayList<>();
      for (Direction.Axis axis : Direction.Axis.values()) {
         for (BlockPos position : positions) {
            if (positions.contains(offset(position, axis, -1))) {
               continue;
            }
            BlockPos end = position;
            int length = 1;
            while (positions.contains(offset(end, axis, 1))) {
               end = offset(end, axis, 1);
               length++;
            }
            if (length >= 2) {
               edges.add(new Edge(position, end));
            }
         }
      }
      // A sloped/digital guide has no single-axis run representing the whole
      // edge.  Add its authored segment as one edge, which gives every raster
      // step the same inherited axis.
      for (Edge guide : guideEdges(guidePoints)) {
         if (!edges.contains(guide)) {
            edges.add(guide);
         }
      }
      return List.copyOf(edges);
   }

   private static BlockPos offset(BlockPos pos, Direction.Axis axis, int amount) {
      return switch (axis) {
         case X -> pos.offset(amount, 0, 0);
         case Y -> pos.offset(0, amount, 0);
         case Z -> pos.offset(0, 0, amount);
      };
   }

   private static Direction.Axis nearestEdgeAxis(
      BlockPos position, List<Edge> edges, Map<Edge, Direction.Axis> axes, Direction.Axis fallback
   ) {
      double bestDistance = Double.POSITIVE_INFINITY;
      Direction.Axis bestAxis = fallback;
      for (Edge edge : edges) {
         double distance = distanceToSegmentSqr(position, edge.from(), edge.to());
         Direction.Axis axis = axes.getOrDefault(edge, fallback);
         if (distance + 1.0E-7 < bestDistance) {
            bestDistance = distance;
            bestAxis = axis;
         }
      }
      // A rasterizer can emit a connector voxel that is equidistant from two
      // authored edges. Keep that voxel on the first authored edge at the
      // minimum distance instead of falling back to the clicked/base axis.
      return bestAxis;
   }

   private static double distanceToSegmentSqr(BlockPos point, BlockPos from, BlockPos to) {
      double dx = to.getX() - from.getX();
      double dy = to.getY() - from.getY();
      double dz = to.getZ() - from.getZ();
      double lengthSqr = dx * dx + dy * dy + dz * dz;
      if (lengthSqr < 1.0E-7) {
         return point.distSqr(from);
      }
      double px = point.getX() - from.getX();
      double py = point.getY() - from.getY();
      double pz = point.getZ() - from.getZ();
      double t = Math.clamp((px * dx + py * dy + pz * dz) / lengthSqr, 0.0, 1.0);
      double x = from.getX() + dx * t - point.getX();
      double y = from.getY() + dy * t - point.getY();
      double z = from.getZ() + dz * t - point.getZ();
      return x * x + y * y + z * z;
   }

   private static Vec3 direction(BlockPos from, BlockPos to) {
      Vec3 vector = new Vec3(
         to.getX() - from.getX(),
         to.getY() - from.getY(),
         to.getZ() - from.getZ()
      );
      return vector.lengthSqr() < 1.0E-7 ? Vec3.ZERO : vector.normalize();
   }

   private static Direction.Axis dominantComponent(double x, double y, double z) {
      double maximum = Math.max(Math.abs(x), Math.max(Math.abs(y), Math.abs(z)));
      double epsilon = 1.0E-7;
      if (Math.abs(x) >= maximum - epsilon) {
         return Direction.Axis.X;
      }
      if (Math.abs(y) >= maximum - epsilon) {
         return Direction.Axis.Y;
      }
      return Direction.Axis.Z;
   }

}
