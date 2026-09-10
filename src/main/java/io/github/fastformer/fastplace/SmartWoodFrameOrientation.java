package io.github.fastformer.fastplace;

import io.github.fastformer.fastplace.geometry.generation.LineGenerator;
import io.github.fastformer.fastplace.geometry.generation.LineTieBias;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/** Assigns final target blocks to authored frame edges. */
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

      List<OrientedEdge> orientedEdges = edges.stream()
         .map(edge -> new OrientedEdge(
            edge,
            edge.direction(),
            axisForDirection(edge.direction(), config.baseAxis())
         ))
         .toList();
      Map<BlockPos, List<OrientedEdge>> memberships = memberships(
         positions, orientedEdges, config.tieBias()
      );
      Map<BlockPos, Direction.Axis> result = new HashMap<>();
      for (Map.Entry<BlockPos, List<OrientedEdge>> entry : memberships.entrySet()) {
         result.put(entry.getKey(), axisAtIntersection(entry.getValue(), config.baseAxis()));
      }
      return Map.copyOf(result);
   }

   private static Map<BlockPos, List<OrientedEdge>> memberships(
      Set<BlockPos> positions,
      List<OrientedEdge> edges,
      LineTieBias tieBias
   ) {
      Map<BlockPos, List<OrientedEdge>> result = new HashMap<>();
      Set<BlockPos> exactTargets = new java.util.HashSet<>();
      // Claim raster points that already are final targets first. This keeps
      // a fuzzy point from stealing a block that belongs to a parallel edge.
      for (OrientedEdge edge : edges) {
         for (BlockPos member : path(edge, tieBias)) {
            if (positions.contains(member)) {
               result.computeIfAbsent(member, ignored -> new ArrayList<>()).add(edge);
               exactTargets.add(member);
            }
         }
      }
      for (OrientedEdge edge : edges) {
         for (BlockPos member : path(edge, tieBias)) {
            if (positions.contains(member)) {
               continue;
            }
            BlockPos target = nearestTarget(member, edge.edge(), positions, exactTargets);
            if (target != null) {
               result.computeIfAbsent(target, ignored -> new ArrayList<>()).add(edge);
               exactTargets.add(target);
            }
         }
      }
      return result;
   }

   private static BlockPos nearestTarget(
      BlockPos rasterizedMember,
      SmartWoodFrame.Edge edge,
      Set<BlockPos> targets,
      Set<BlockPos> claimed
   ) {
      if (targets.contains(rasterizedMember)) {
         return rasterizedMember;
      }
      BlockPos best = null;
      double bestEdgeDistance = Double.POSITIVE_INFINITY;
      int bestMemberDistance = Integer.MAX_VALUE;
      for (int x = -1; x <= 1; x++) {
         for (int y = -1; y <= 1; y++) {
            for (int z = -1; z <= 1; z++) {
               BlockPos candidate = rasterizedMember.offset(x, y, z);
               if (!targets.contains(candidate) || claimed.contains(candidate)) {
                  continue;
               }
               double edgeDistance = distanceToSegmentSqr(candidate, edge);
               int memberDistance = x * x + y * y + z * z;
               if (isBetterCandidate(candidate, edgeDistance, memberDistance, best, bestEdgeDistance, bestMemberDistance)) {
                  best = candidate.immutable();
                  bestEdgeDistance = edgeDistance;
                  bestMemberDistance = memberDistance;
               }
            }
         }
      }
      return best;
   }

   private static boolean isBetterCandidate(
      BlockPos candidate,
      double edgeDistance,
      int memberDistance,
      BlockPos best,
      double bestEdgeDistance,
      int bestMemberDistance
   ) {
      if (edgeDistance + EPSILON < bestEdgeDistance) {
         return true;
      }
      if (Math.abs(edgeDistance - bestEdgeDistance) > EPSILON) {
         return false;
      }
      if (memberDistance != bestMemberDistance) {
         return memberDistance < bestMemberDistance;
      }
      return best == null || compare(candidate, best) < 0;
   }

   private static int compare(BlockPos first, BlockPos second) {
      int x = Integer.compare(first.getX(), second.getX());
      if (x != 0) {
         return x;
      }
      int y = Integer.compare(first.getY(), second.getY());
      return y != 0 ? y : Integer.compare(first.getZ(), second.getZ());
   }

   private static Direction.Axis axisAtIntersection(
      List<OrientedEdge> edges, Direction.Axis fallback
   ) {
      if (edges.isEmpty()) {
         return fallback;
      }
      if (edges.size() == 1) {
         return edges.getFirst().axis();
      }
      double x = 0.0;
      double y = 0.0;
      double z = 0.0;
      for (OrientedEdge edge : edges) {
         x = Math.max(x, Math.abs(edge.direction().x));
         y = Math.max(y, Math.abs(edge.direction().y));
         z = Math.max(z, Math.abs(edge.direction().z));
      }
      double maximum = Math.max(x, Math.max(y, z));
      if (maximum <= EPSILON) {
         return fallback;
      }
      for (Direction.Axis axis : Direction.Axis.values()) {
         double score = switch (axis) {
            case X -> x;
            case Y -> y;
            case Z -> z;
         };
         if (score >= maximum - EPSILON) {
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

   private static double distanceToSegmentSqr(
      BlockPos position, SmartWoodFrame.Edge edge
   ) {
      Vec3 point = Vec3.atCenterOf(position);
      Vec3 from = Vec3.atCenterOf(edge.from());
      Vec3 segment = Vec3.atCenterOf(edge.to()).subtract(from);
      double lengthSqr = segment.lengthSqr();
      if (lengthSqr <= EPSILON) {
         return point.distanceToSqr(from);
      }
      double parameter = Math.clamp(
         point.subtract(from).dot(segment) / lengthSqr, 0.0, 1.0
      );
      return point.distanceToSqr(from.add(segment.scale(parameter)));
   }

   private static List<BlockPos> path(
      OrientedEdge edge, LineTieBias tieBias
   ) {
      return LineGenerator.path(edge.edge().from(), edge.edge().to(), tieBias);
   }

   private record OrientedEdge(
      SmartWoodFrame.Edge edge, Vec3 direction, Direction.Axis axis
   ) {
   }
}
