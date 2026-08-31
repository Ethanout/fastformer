package io.github.fastformer.fastplace;

import io.github.fastformer.fastplace.geometry.generation.PlanarFaceGeometry;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

/** Builds the geometric frame and applies axes selected by {@link SmartWoodFrameOrientation}. */
final class SmartWoodFrame {
   private SmartWoodFrame() {
   }

   /** A continuous geometric edge. It does not contain a Minecraft wood axis. */
   record Edge(BlockPos from, BlockPos to) {
      Edge {
         from = from == null ? BlockPos.ZERO : from.immutable();
         to = to == null ? from : to.immutable();
      }

      Vec3 direction() {
         Vec3 vector = new Vec3(
            to.getX() - from.getX(),
            to.getY() - from.getY(),
            to.getZ() - from.getZ()
         );
         return vector.lengthSqr() < 1.0E-7 ? Vec3.ZERO : vector.normalize();
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

   /** Builds the authored geometric edges used by the shape generator. */
   static List<Edge> edgeGuides(
      List<BlockPos> points,
      FaceMode faceMode,
      boolean polygonHeightConfirmed,
      PolygonVolumeShape volumeShape
   ) {
      if (points == null || points.size() < 2) {
         return List.of();
      }
      if (points.size() == 2) {
         return List.of(new Edge(points.get(0), points.get(1)));
      }

      boolean volume = polygonHeightConfirmed && faceMode == FaceMode.POLYGON
         || faceMode != FaceMode.POLYGON && points.size() >= 4;
      List<BlockPos> base = faceMode == FaceMode.POLYGON
         ? points.subList(0, volume ? points.size() - 1 : points.size())
         : PlanarFaceGeometry.vertices(points, faceMode).stream().map(BlockPos::containing).toList();
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
      BlockPos tip = faceMode == FaceMode.POLYGON ? points.getLast() : points.get(3);
      BlockPos offset = tip.subtract(anchor);
      if (faceMode == FaceMode.POLYGON && volumeShape == PolygonVolumeShape.APEX) {
         List<Vec3> baseVectors = base.stream().map(Vec3::atCenterOf).toList();
         Vec3 normal = PlanarFaceGeometry.normal(baseVectors);
         Vec3 center = baseVectors.stream().reduce(Vec3.ZERO, Vec3::add)
            .scale(1.0 / baseVectors.size());
         Vec3 extrusion = Vec3.atCenterOf(tip).subtract(Vec3.atCenterOf(anchor));
         BlockPos apex = BlockPos.containing(center.add(normal.scale(extrusion.dot(normal))));
         for (BlockPos corner : base) {
            result.add(new Edge(corner, apex));
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
      if (positions == null || positions.isEmpty() || config == null || prototype == null
         || !prototype.hasProperty(BlockStateProperties.AXIS)) {
         return Map.of();
      }
      Map<BlockPos, Direction.Axis> axes = SmartWoodFrameOrientation.resolve(positions, config);
      Map<BlockPos, BlockState> result = new HashMap<>();
      for (BlockPos position : positions) {
         Direction.Axis axis = axes.getOrDefault(position, config.baseAxis());
         result.put(position, prototype.setValue(BlockStateProperties.AXIS, axis));
      }
      return Map.copyOf(result);
   }

   static Direction.Axis axisForTest(Set<BlockPos> positions, BlockPos position, Config config) {
      if (config == null || position == null) {
         return Direction.Axis.Y;
      }
      return SmartWoodFrameOrientation.resolve(positions, config)
         .getOrDefault(position, config.baseAxis());
   }
}
