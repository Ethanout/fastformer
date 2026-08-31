package io.github.fastformer.client.operation.transform;

import io.github.fastformer.client.operation.model.ClientBlockSnapshot;
import io.github.fastformer.fastplace.geometry.AxisGizmo;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Deterministic center-sampled voxel rotation shared by preview and submission planning. */
public final class VoxelRotation {
   private static final double QUARTER_TURN = Math.PI * 0.5;
   private static final double ORTHOGONAL_EPSILON = 1.0E-9;

   private VoxelRotation() {
   }

   public static Map<BlockPos, ClientBlockSnapshot> rotate(
      Map<BlockPos, ClientBlockSnapshot> source,
      Vec3 pivot,
      AxisGizmo.Axis axis,
      double radians
   ) {
      return rotate(source, pivot, axis, radians, snapshot -> rotateSnapshot(snapshot, axis, radians));
   }

   public static <T> Map<BlockPos, T> rotateValues(
      Map<BlockPos, T> source,
      Vec3 pivot,
      AxisGizmo.Axis axis,
      double radians
   ) {
      return rotate(source, pivot, axis, radians, Function.identity());
   }

   private static <T> Map<BlockPos, T> rotate(
      Map<BlockPos, T> source,
      Vec3 pivot,
      AxisGizmo.Axis axis,
      double radians,
      Function<T, T> valueTransform
   ) {
      LinkedHashMap<BlockPos, T> result = new LinkedHashMap<>();
      var entries = source.entrySet().stream()
         .sorted(Map.Entry.comparingByKey(Comparator
            .comparingInt((BlockPos pos) -> pos.getX())
            .thenComparingInt(pos -> pos.getY())
            .thenComparingInt(pos -> pos.getZ())))
         .toList();
      for (Map.Entry<BlockPos, T> entry : entries) {
         result.putIfAbsent(rotatedCell(entry.getKey(), pivot, axis, radians), valueTransform.apply(entry.getValue()));
      }
      // Center sampling makes a diagonal staircase from two originally touching cells.
      // Fill only that staircase, never arbitrary gaps, so sparse shapes remain sparse.
      for (Map.Entry<BlockPos, T> entry : entries) {
         BlockPos start = entry.getKey();
         BlockPos rotatedStart = rotatedCell(start, pivot, axis, radians);
         for (BlockPos offset : List.of(new BlockPos(1, 0, 0), new BlockPos(0, 1, 0), new BlockPos(0, 0, 1))) {
            if (!source.containsKey(start.offset(offset))) {
               continue;
            }
            bridgeFaceNeighbours(result, rotatedStart, rotatedCell(start.offset(offset), pivot, axis, radians), valueTransform.apply(entry.getValue()));
         }
      }
      return Map.copyOf(result);
   }

   private static BlockPos rotatedCell(BlockPos source, Vec3 pivot, AxisGizmo.Axis axis, double radians) {
      Vec3 rotated = rotateVector(Vec3.atCenterOf(source).subtract(pivot), axis, radians).add(pivot);
      return BlockPos.containing(Math.floor(rotated.x), Math.floor(rotated.y), Math.floor(rotated.z));
   }

   private static <T> void bridgeFaceNeighbours(Map<BlockPos, T> result, BlockPos from, BlockPos to, T value) {
      BlockPos cursor = from;
      for (AxisGizmo.Axis bridgeAxis : AxisGizmo.Axis.values()) {
         int difference = coordinate(to, bridgeAxis) - coordinate(cursor, bridgeAxis);
         if (difference != 0) {
            cursor = withCoordinate(cursor, bridgeAxis, coordinate(to, bridgeAxis));
            if (!cursor.equals(to)) {
               result.putIfAbsent(cursor, value);
            }
         }
      }
   }

   private static int coordinate(BlockPos position, AxisGizmo.Axis axis) {
      return switch (axis) {
         case X -> position.getX();
         case Y -> position.getY();
         case Z -> position.getZ();
      };
   }

   private static BlockPos withCoordinate(BlockPos position, AxisGizmo.Axis axis, int value) {
      return switch (axis) {
         case X -> new BlockPos(value, position.getY(), position.getZ());
         case Y -> new BlockPos(position.getX(), value, position.getZ());
         case Z -> new BlockPos(position.getX(), position.getY(), value);
      };
   }

   private static Vec3 rotateVector(Vec3 value, AxisGizmo.Axis axis, double radians) {
      double sin = Math.sin(radians);
      double cos = Math.cos(radians);
      return switch (axis) {
         case X -> new Vec3(value.x, value.y * cos - value.z * sin, value.y * sin + value.z * cos);
         case Y -> new Vec3(value.x * cos + value.z * sin, value.y, -value.x * sin + value.z * cos);
         case Z -> new Vec3(value.x * cos - value.y * sin, value.x * sin + value.y * cos, value.z);
      };
   }

   private static ClientBlockSnapshot rotateSnapshot(
      ClientBlockSnapshot snapshot, AxisGizmo.Axis axis, double radians
   ) {
      int quarterTurns = (int)Math.rint(radians / QUARTER_TURN);
      if (axis != AxisGizmo.Axis.Y || Math.abs(radians - quarterTurns * QUARTER_TURN) > ORTHOGONAL_EPSILON) {
         return snapshot;
      }
      int normalized = Math.floorMod(quarterTurns, 4);
      Rotation rotation = switch (normalized) {
         case 1 -> Rotation.CLOCKWISE_90;
         case 2 -> Rotation.CLOCKWISE_180;
         case 3 -> Rotation.COUNTERCLOCKWISE_90;
         default -> Rotation.NONE;
      };
      BlockState rotated = snapshot.state().rotate(rotation);
      return new ClientBlockSnapshot(rotated, snapshot.blockEntity());
   }
}
