package io.github.fastformer.fastplace;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/** Assigns each generated frame block to a geometric edge and a vanilla wood axis. */
final class SmartWoodFrameOrientation {
   private static final double EPSILON = 1.0E-7;

   private SmartWoodFrameOrientation() {
   }

   static Map<BlockPos, Direction.Axis> resolve(
      Set<BlockPos> positions, SmartWoodFrame.Config config
   ) {
      if (positions == null || positions.isEmpty() || config == null) {
         return Map.of();
      }
      List<SmartWoodFrame.Edge> edges = config.edges().isEmpty()
         ? inferredEdges(positions, config.guidePoints())
         : config.edges();
      if (edges.isEmpty()) {
         return Map.of();
      }

      Map<SmartWoodFrame.Edge, Direction.Axis> edgeAxes = new HashMap<>();
      for (SmartWoodFrame.Edge edge : edges) {
         edgeAxes.put(edge, axisForDirection(edge.direction(), config.baseAxis()));
      }

      Map<BlockPos, Direction.Axis> result = new HashMap<>();
      for (BlockPos position : positions) {
         List<SmartWoodFrame.Edge> nearest = nearestEdges(position, edges);
         result.put(position, nearest.isEmpty()
            ? config.baseAxis()
            : nearest.size() == 1
               ? edgeAxes.getOrDefault(nearest.getFirst(), config.baseAxis())
               : dominantIncidentAxis(nearest, config.baseAxis()));
      }
      return Map.copyOf(result);
   }

   private static List<SmartWoodFrame.Edge> nearestEdges(
      BlockPos position, List<SmartWoodFrame.Edge> edges
   ) {
      Vec3 blockCenter = Vec3.atCenterOf(position);
      double bestDistance = Double.POSITIVE_INFINITY;
      ArrayList<SmartWoodFrame.Edge> nearest = new ArrayList<>();
      for (SmartWoodFrame.Edge edge : edges) {
         double distance = distanceToSegmentSqr(blockCenter, edge);
         if (distance + EPSILON < bestDistance) {
            bestDistance = distance;
            nearest.clear();
            nearest.add(edge);
         } else if (Math.abs(distance - bestDistance) <= EPSILON) {
            nearest.add(edge);
         }
      }
      return List.copyOf(nearest);
   }

   private static Direction.Axis dominantIncidentAxis(
      List<SmartWoodFrame.Edge> edges,
      Direction.Axis fallback
   ) {
      double[] scores = new double[Direction.Axis.values().length];
      for (SmartWoodFrame.Edge edge : edges) {
         Vec3 direction = edge.direction();
         scores[0] = Math.max(scores[0], Math.abs(direction.x));
         scores[1] = Math.max(scores[1], Math.abs(direction.y));
         scores[2] = Math.max(scores[2], Math.abs(direction.z));
      }
      double maximum = Math.max(scores[0], Math.max(scores[1], scores[2]));
      if (maximum <= EPSILON) {
         return fallback;
      }
      for (Direction.Axis axis : Direction.Axis.values()) {
         if (scores[axis.ordinal()] >= maximum - EPSILON) {
            return axis;
         }
      }
      return fallback;
   }

   private static Direction.Axis axisForDirection(Vec3 direction, Direction.Axis fallback) {
      if (direction == null || direction.lengthSqr() <= EPSILON) {
         return fallback;
      }
      double maximum = Math.max(Math.abs(direction.x), Math.max(Math.abs(direction.y), Math.abs(direction.z)));
      if (Math.abs(direction.x) >= maximum - EPSILON) {
         return Direction.Axis.X;
      }
      if (Math.abs(direction.y) >= maximum - EPSILON) {
         return Direction.Axis.Y;
      }
      return Direction.Axis.Z;
   }

   private static List<SmartWoodFrame.Edge> inferredEdges(
      Set<BlockPos> positions, List<BlockPos> guidePoints
   ) {
      ArrayList<SmartWoodFrame.Edge> edges = new ArrayList<>();
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
               edges.add(new SmartWoodFrame.Edge(position, end));
            }
         }
      }
      for (SmartWoodFrame.Edge guide : guideEdges(guidePoints)) {
         if (!edges.contains(guide)) {
            edges.add(guide);
         }
      }
      return List.copyOf(edges);
   }

   private static List<SmartWoodFrame.Edge> guideEdges(List<BlockPos> points) {
      if (points == null || points.size() < 2) {
         return List.of();
      }
      ArrayList<SmartWoodFrame.Edge> edges = new ArrayList<>();
      int count = points.size() == 2 ? 1 : points.size();
      for (int index = 0; index < count; index++) {
         edges.add(new SmartWoodFrame.Edge(points.get(index), points.get((index + 1) % points.size())));
      }
      return List.copyOf(edges);
   }

   private static BlockPos offset(BlockPos position, Direction.Axis axis, int amount) {
      return switch (axis) {
         case X -> position.offset(amount, 0, 0);
         case Y -> position.offset(0, amount, 0);
         case Z -> position.offset(0, 0, amount);
      };
   }

   private static double distanceToSegmentSqr(Vec3 point, SmartWoodFrame.Edge edge) {
      Vec3 from = Vec3.atCenterOf(edge.from());
      Vec3 to = Vec3.atCenterOf(edge.to());
      Vec3 segment = to.subtract(from);
      double lengthSqr = segment.lengthSqr();
      if (lengthSqr <= EPSILON) {
         return point.distanceToSqr(from);
      }
      double parameter = Math.clamp(point.subtract(from).dot(segment) / lengthSqr, 0.0, 1.0);
      return point.distanceToSqr(from.add(segment.scale(parameter)));
   }
}
