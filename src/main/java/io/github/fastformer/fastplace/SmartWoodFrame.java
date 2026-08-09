package io.github.fastformer.fastplace;

import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** Applies stable axis orientation to blocks exposing the vanilla AXIS property. */
final class SmartWoodFrame {
   private SmartWoodFrame() {
   }

   record Config(Direction.Axis baseAxis, List<BlockPos> guidePoints) {
      Config {
         baseAxis = baseAxis == null ? Direction.Axis.Y : baseAxis;
         guidePoints = guidePoints == null ? List.of() : List.copyOf(guidePoints);
      }
   }

   static Map<BlockPos, BlockState> resolve(Set<BlockPos> positions, BlockState prototype, Config config) {
      if (config == null || !prototype.hasProperty(BlockStateProperties.AXIS)) {
         return Map.of();
      }
      Map<BlockPos, BlockState> result = new HashMap<>();
      for (BlockPos pos : positions) {
         Direction.Axis axis = axisAt(positions, pos, config);
         result.put(pos, prototype.setValue(BlockStateProperties.AXIS, axis));
      }
      return Map.copyOf(result);
   }

   static Direction.Axis axisForTest(Set<BlockPos> positions, BlockPos pos, Config config) {
      return axisAt(positions, pos, config);
   }

   private static Direction.Axis axisAt(Set<BlockPos> positions, BlockPos pos, Config config) {
      Direction.Axis guideAxis = nearestGuideAxis(pos, config.guidePoints(), config.baseAxis());
      if (guideAxis != null) {
         return guideAxis;
      }
      int[] nearby = new int[3];
      for (Direction direction : Direction.values()) {
         if (positions.contains(pos.relative(direction))) {
            nearby[direction.getAxis().ordinal()]++;
         }
      }
      int best = Math.max(nearby[0], Math.max(nearby[1], nearby[2]));
      if (best == 0) {
         return config.baseAxis();
      }
      int tied = 0;
      for (int value : nearby) {
         if (value == best) {
            tied++;
         }
      }
      if (tied > 1 && nearby[config.baseAxis().ordinal()] == best) {
         return config.baseAxis();
      }
      Direction.Axis result = Direction.Axis.X;
      for (Direction.Axis axis : Direction.Axis.values()) {
         if (nearby[axis.ordinal()] > nearby[result.ordinal()]) {
            result = axis;
         }
      }
      return result;
   }

   private static Direction.Axis nearestGuideAxis(BlockPos pos, List<BlockPos> points, Direction.Axis baseAxis) {
      if (points.size() < 2) {
         return null;
      }
      double bestDistance = Double.POSITIVE_INFINITY;
      java.util.EnumSet<Direction.Axis> axes = java.util.EnumSet.noneOf(Direction.Axis.class);
      int segmentCount = points.size() >= 3 ? points.size() : points.size() - 1;
      for (int index = 0; index < segmentCount; index++) {
         BlockPos from = points.get(index);
         BlockPos to = points.get((index + 1) % points.size());
         if (from.equals(to)) {
            continue;
         }
         double distance = distanceToSegmentSqr(pos, from, to);
         Direction.Axis axis = dominantAxis(from, to, baseAxis);
         if (distance + 1.0E-7 < bestDistance) {
            bestDistance = distance;
            axes.clear();
            axes.add(axis);
         } else if (Math.abs(distance - bestDistance) <= 1.0E-7) {
            axes.add(axis);
         }
      }
      if (axes.isEmpty()) {
         return null;
      }
      return axes.contains(baseAxis) ? baseAxis : axes.iterator().next();
   }

   private static Direction.Axis dominantAxis(BlockPos from, BlockPos to, Direction.Axis fallback) {
      int dx = Math.abs(to.getX() - from.getX());
      int dy = Math.abs(to.getY() - from.getY());
      int dz = Math.abs(to.getZ() - from.getZ());
      int maximum = Math.max(dx, Math.max(dy, dz));
      if (component(fallback, dx, dy, dz) == maximum) {
         return fallback;
      }
      return dx == maximum ? Direction.Axis.X : dy == maximum ? Direction.Axis.Y : Direction.Axis.Z;
   }

   private static int component(Direction.Axis axis, int x, int y, int z) {
      return axis == Direction.Axis.X ? x : axis == Direction.Axis.Y ? y : z;
   }

   private static double distanceToSegmentSqr(BlockPos point, BlockPos from, BlockPos to) {
      double dx = to.getX() - from.getX();
      double dy = to.getY() - from.getY();
      double dz = to.getZ() - from.getZ();
      double lengthSqr = dx * dx + dy * dy + dz * dz;
      double t = ((point.getX() - from.getX()) * dx
         + (point.getY() - from.getY()) * dy
         + (point.getZ() - from.getZ()) * dz) / lengthSqr;
      t = Math.clamp(t, 0.0, 1.0);
      double x = from.getX() + dx * t - point.getX();
      double y = from.getY() + dy * t - point.getY();
      double z = from.getZ() + dz * t - point.getZ();
      return x * x + y * y + z * z;
   }
}
