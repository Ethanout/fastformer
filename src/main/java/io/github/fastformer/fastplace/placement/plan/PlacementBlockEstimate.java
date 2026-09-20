package io.github.fastformer.fastplace.placement.plan;

import io.github.fastformer.fastplace.FastPlaceGeometry;
import io.github.fastformer.fastplace.quickshape.FaceMode;
import io.github.fastformer.fastplace.FillMode;
import io.github.fastformer.fastplace.quickshape.PolygonVolumeShape;
import io.github.fastformer.fastplace.geometry.generation.LineGenerator;
import io.github.fastformer.fastplace.geometry.generation.PlanarFaceGeometry;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Computes a bounded upper estimate for the ordinary placement output set. */
public final class PlacementBlockEstimate {
   private PlacementBlockEstimate() {
   }

   public static long upperBound(
      List<BlockPos> points,
      FastPlaceGeometry.Modes modes,
      boolean polygonHeightConfirmed,
      PolygonVolumeShape polygonVolumeShape,
      int maxPlacement
   ) {
      if (points == null || points.isEmpty()) {
         return 0L;
      }
      long generationLimit = (long)maxPlacement + 1L;
      if (points.size() <= 2) {
         BlockPos end = points.size() == 1 ? points.getFirst() : points.get(1);
         return Math.min(LineGenerator.estimateBlocks(points.getFirst(), end), generationLimit);
      }
      if (modes.fillMode() == FillMode.OUTLINE && modes.faceMode() != FaceMode.POLYGON) {
         return Math.min(ordinaryOutlineUpperBound(points, modes), generationLimit);
      }
      return Math.min(
         derivedBoundsVolume(points, modes, polygonHeightConfirmed, polygonVolumeShape),
         generationLimit
      );
   }

   private static long ordinaryOutlineUpperBound(List<BlockPos> points, FastPlaceGeometry.Modes modes) {
      List<Vec3> base = PlanarFaceGeometry.vertices(points, modes.faceMode());
      long baseEdges = perimeterUpperBound(base);
      if (points.size() == 3) {
         return baseEdges;
      }

      Vec3 extrusion = Vec3.atCenterOf(points.get(3)).subtract(Vec3.atCenterOf(points.get(2)));
      long uprightEdge = lineUpperBound(Vec3.ZERO, extrusion);
      return addSaturated(
         multiplySaturated(baseEdges, 2L),
         multiplySaturated(uprightEdge, base.size())
      );
   }

   private static long perimeterUpperBound(List<Vec3> vertices) {
      long total = 0L;
      for (int index = 0; index < vertices.size(); index++) {
         total = addSaturated(
            total,
            lineUpperBound(vertices.get(index), vertices.get((index + 1) % vertices.size()))
         );
      }
      return total;
   }

   private static long lineUpperBound(Vec3 from, Vec3 to) {
      return LineGenerator.estimateBlocks(BlockPos.containing(from), BlockPos.containing(to));
   }

   private static long derivedBoundsVolume(
      List<BlockPos> points,
      FastPlaceGeometry.Modes modes,
      boolean polygonHeightConfirmed,
      PolygonVolumeShape polygonVolumeShape
   ) {
      List<BlockPos> boundsPoints = new ArrayList<>();
      if (modes.faceMode() == FaceMode.POLYGON) {
         addPolygonBounds(points, polygonHeightConfirmed, polygonVolumeShape, boundsPoints);
      } else {
         List<Vec3> base = PlanarFaceGeometry.vertices(points, modes.faceMode());
         base.stream().map(BlockPos::containing).forEach(boundsPoints::add);
         if (points.size() >= 4) {
            Vec3 extrusion = Vec3.atCenterOf(points.get(3)).subtract(Vec3.atCenterOf(points.get(2)));
            base.stream().map(vertex -> BlockPos.containing(vertex.add(extrusion))).forEach(boundsPoints::add);
         }
      }
      return boundsVolume(boundsPoints);
   }

   private static void addPolygonBounds(
      List<BlockPos> points,
      boolean polygonHeightConfirmed,
      PolygonVolumeShape polygonVolumeShape,
      List<BlockPos> output
   ) {
      if (!polygonHeightConfirmed || points.size() < 4) {
         output.addAll(points);
         return;
      }

      List<Vec3> base = points.subList(0, points.size() - 1).stream().map(Vec3::atCenterOf).toList();
      Vec3 anchor = Vec3.atCenterOf(points.get(Math.min(2, points.size() - 2)));
      Vec3 extrusion = Vec3.atCenterOf(points.getLast()).subtract(anchor);
      base.stream().map(BlockPos::containing).forEach(output::add);
      if (polygonVolumeShape == PolygonVolumeShape.EXTRUDE) {
         base.stream().map(vertex -> BlockPos.containing(vertex.add(extrusion))).forEach(output::add);
         return;
      }

      Vec3 center = Vec3.ZERO;
      for (Vec3 vertex : base) {
         center = center.add(vertex);
      }
      center = center.scale(1.0 / base.size());
      Vec3 normal = PlanarFaceGeometry.normal(base);
      Vec3 apex = center.add(normal.scale(extrusion.dot(normal)));
      output.add(BlockPos.containing(apex));
   }

   private static long boundsVolume(List<BlockPos> points) {
      long minX = points.stream().mapToLong(BlockPos::getX).min().orElse(0L);
      long minY = points.stream().mapToLong(BlockPos::getY).min().orElse(0L);
      long minZ = points.stream().mapToLong(BlockPos::getZ).min().orElse(0L);
      long maxX = points.stream().mapToLong(BlockPos::getX).max().orElse(0L);
      long maxY = points.stream().mapToLong(BlockPos::getY).max().orElse(0L);
      long maxZ = points.stream().mapToLong(BlockPos::getZ).max().orElse(0L);
      long width = maxX - minX + 1L;
      long height = maxY - minY + 1L;
      long depth = maxZ - minZ + 1L;
      return multiplySaturated(multiplySaturated(width, height), depth);
   }

   private static long addSaturated(long first, long second) {
      return first > Long.MAX_VALUE - second ? Long.MAX_VALUE : first + second;
   }

   private static long multiplySaturated(long first, long second) {
      return first != 0L && second > Long.MAX_VALUE / first ? Long.MAX_VALUE : first * second;
   }
}
