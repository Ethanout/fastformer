package io.github.fastformer.client.operation.transform;

import io.github.fastformer.client.operation.model.ClientBlockSnapshot;
import io.github.fastformer.client.operation.preview.Composition;
import io.github.fastformer.client.operation.preview.CompositionCounter;
import io.github.fastformer.client.operation.selection.OccupiedBlockBounds;
import io.github.fastformer.fastplace.geometry.AxisGizmo;
import java.util.ArrayList;
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
   /** A rotation below this angle is not applied to positions. */
   public static final double POSITION_EPSILON = 1.0E-12;

   private VoxelRotation() {
   }

   /**
    * One applied rotation axis, with the pivot the voxel pipeline used for it.
    *
    * <p>Render geometry applies these exact steps to a selection envelope. Deriving its
    * own pivot would move the outline and Gizmo away from the blocks they describe.
    */
   public record RotationStep(AxisGizmo.Axis axis, double radians, Vec3 pivot) {
   }

   /** The mapped cells and the ordered steps that produced them. */
   public record RotationResult<T>(Map<BlockPos, T> values, List<RotationStep> steps) {
   }

   /**
    * The value transform one rotation axis applies to every stored value.
    *
    * <p>A cell and the block state it holds must turn together. The position stage and the
    * value stage therefore take the same axis and the same angle.
    */
   public interface ValueRotation<T> {
      Function<T, T> forAxis(AxisGizmo.Axis axis, double radians);
   }

   /** Keeps every stored value unchanged. Position-only callers use this. */
   public static <T> ValueRotation<T> positionOnly() {
      return (axis, radians) -> value -> value;
   }

   /** Turns a stored block state with its cell. Only the Y axis has a block-state rotation. */
   public static ValueRotation<ClientBlockSnapshot> snapshotValues() {
      return (axis, radians) -> snapshot -> rotateSnapshot(snapshot, axis, radians);
   }

   /**
    * Applies the whole X, Y, Z position rotation stage and reports the pivots it used.
    *
    * <p>Each axis re-centres on the occupied bounds of the current result. The order and
    * the pivots are part of the contract: a caller that must place outline geometry on top
    * of these blocks replays {@link RotationResult#steps()} instead of guessing a centre.
    *
    * <p>This entry keeps the value of every cell unchanged. Use
    * {@link #rotateStageSnapshots} when the values are block states.
    */
   public static <T> RotationResult<T> rotateStage(Map<BlockPos, T> source, Vec3 rotation) {
      return rotateStage(source, rotation, positionOnly());
   }

   /**
    * Applies the position stage and turns each stored block state with its cell.
    *
    * <p>Preview and submission both use this entry, so a rotated operation shows the same
    * states that it writes to the world.
    */
   public static RotationResult<ClientBlockSnapshot> rotateStageSnapshots(
      Map<BlockPos, ClientBlockSnapshot> source, Vec3 rotation
   ) {
      return rotateStage(source, rotation, snapshotValues());
   }

   public static <T> RotationResult<T> rotateStage(
      Map<BlockPos, T> source, Vec3 rotation, ValueRotation<T> valueRotation
   ) {
      BoundedRotation<T> bounded = rotateStage(source, rotation, valueRotation, null);
      if (bounded.overBudget() != null) {
         throw new IllegalStateException("unbounded rotation hit a composition budget");
      }
      return bounded.result();
   }

   /**
    * Bounded rotation stage. Unique cells count toward output. Cell visits and insert
    * attempts count toward work. Sort comparisons do not count as work.
    */
   public static <T> BoundedRotation<T> rotateStage(
      Map<BlockPos, T> source,
      Vec3 rotation,
      ValueRotation<T> valueRotation,
      CompositionCounter counter
   ) {
      Map<BlockPos, T> current = source;
      List<RotationStep> steps = new ArrayList<>(3);
      for (AxisGizmo.Axis axis : AxisGizmo.Axis.values()) {
         double radians = axisComponent(rotation, axis);
         OccupiedBlockBounds bounds = OccupiedBlockBounds.from(current.keySet()).orElse(null);
         if (bounds == null || Math.abs(radians) <= POSITION_EPSILON) {
            continue;
         }
         Vec3 pivot = bounds.center();
         steps.add(new RotationStep(axis, radians, pivot));
         AxisRotation<T> axisResult = rotate(
            current, pivot, axis, radians, valueRotation.forAxis(axis, radians), counter
         );
         if (axisResult.overBudget() != null) {
            return new BoundedRotation<>(null, axisResult.overBudget());
         }
         current = axisResult.values();
      }
      return new BoundedRotation<>(new RotationResult<>(current, List.copyOf(steps)), null);
   }

   public record BoundedRotation<T>(RotationResult<T> result, Composition.OverBudget<T> overBudget) {
   }

   private record AxisRotation<T>(Map<BlockPos, T> values, Composition.OverBudget<T> overBudget) {
   }

   private static double axisComponent(Vec3 value, AxisGizmo.Axis axis) {
      return switch (axis) {
         case X -> value.x;
         case Y -> value.y;
         case Z -> value.z;
      };
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
      AxisRotation<T> bounded = rotate(source, pivot, axis, radians, valueTransform, null);
      if (bounded.overBudget() != null) {
         throw new IllegalStateException("unbounded rotation hit a composition budget");
      }
      return bounded.values();
   }

   private static <T> AxisRotation<T> rotate(
      Map<BlockPos, T> source,
      Vec3 pivot,
      AxisGizmo.Axis axis,
      double radians,
      Function<T, T> valueTransform,
      CompositionCounter counter
   ) {
      LinkedHashMap<BlockPos, T> result = new LinkedHashMap<>();
      var entries = source.entrySet().stream()
         .sorted(Map.Entry.comparingByKey(Comparator
            .comparingInt((BlockPos pos) -> pos.getX())
            .thenComparingInt(pos -> pos.getY())
            .thenComparingInt(pos -> pos.getZ())))
         .toList();
      for (Map.Entry<BlockPos, T> entry : entries) {
         Composition.OverBudget<T> work = charge(counter, 1L);
         if (work != null) {
            return new AxisRotation<>(null, work);
         }
         result.putIfAbsent(rotatedCell(entry.getKey(), pivot, axis, radians), valueTransform.apply(entry.getValue()));
         Composition.OverBudget<T> output = checkOutput(counter, result.size());
         if (output != null) {
            return new AxisRotation<>(null, output);
         }
      }
      // Center sampling makes a diagonal staircase from two originally touching cells.
      // Fill only that staircase, never arbitrary gaps, so sparse shapes remain sparse.
      for (Map.Entry<BlockPos, T> entry : entries) {
         BlockPos start = entry.getKey();
         BlockPos rotatedStart = rotatedCell(start, pivot, axis, radians);
         for (BlockPos offset : List.of(new BlockPos(1, 0, 0), new BlockPos(0, 1, 0), new BlockPos(0, 0, 1))) {
            Composition.OverBudget<T> probe = charge(counter, 1L);
            if (probe != null) {
               return new AxisRotation<>(null, probe);
            }
            if (!source.containsKey(start.offset(offset))) {
               continue;
            }
            Composition.OverBudget<T> bridged = bridgeFaceNeighbours(
               result,
               rotatedStart,
               rotatedCell(start.offset(offset), pivot, axis, radians),
               valueTransform.apply(entry.getValue()),
               counter
            );
            if (bridged != null) {
               return new AxisRotation<>(null, bridged);
            }
         }
      }
      return new AxisRotation<>(io.github.fastformer.fastplace.geometry.BlockPositionMaps.copyOf(result), null);
   }

   private static <T> Composition.OverBudget<T> charge(CompositionCounter counter, long steps) {
      return counter == null ? null : counter.addWork(steps);
   }

   private static <T> Composition.OverBudget<T> checkOutput(CompositionCounter counter, long uniqueCells) {
      return counter == null ? null : counter.checkOutput(uniqueCells);
   }

   private static BlockPos rotatedCell(BlockPos source, Vec3 pivot, AxisGizmo.Axis axis, double radians) {
      Vec3 rotated = rotatePoint(Vec3.atCenterOf(source), pivot, axis, radians);
      return BlockPos.containing(Math.floor(rotated.x), Math.floor(rotated.y), Math.floor(rotated.z));
   }

   private static <T> Composition.OverBudget<T> bridgeFaceNeighbours(
      Map<BlockPos, T> result, BlockPos from, BlockPos to, T value, CompositionCounter counter
   ) {
      BlockPos cursor = from;
      for (AxisGizmo.Axis bridgeAxis : AxisGizmo.Axis.values()) {
         int difference = coordinate(to, bridgeAxis) - coordinate(cursor, bridgeAxis);
         if (difference != 0) {
            cursor = withCoordinate(cursor, bridgeAxis, coordinate(to, bridgeAxis));
            if (!cursor.equals(to)) {
               Composition.OverBudget<T> work = charge(counter, 1L);
               if (work != null) {
                  return work;
               }
               result.putIfAbsent(cursor, value);
               Composition.OverBudget<T> output = checkOutput(counter, result.size());
               if (output != null) {
                  return output;
               }
            }
         }
      }
      return null;
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

   /**
    * Rotates one point about a pivot. The envelope code uses this same method, so a
    * selection box and its blocks can never drift apart through a second formula.
    */
   public static Vec3 rotatePoint(Vec3 point, Vec3 pivot, AxisGizmo.Axis axis, double radians) {
      return rotateVector(point.subtract(pivot), axis, radians).add(pivot);
   }

   private static Vec3 rotateVector(Vec3 value, AxisGizmo.Axis axis, double radians) {
      double sin = sin(radians);
      double cos = cos(radians);
      return switch (axis) {
         case X -> new Vec3(value.x, value.y * cos - value.z * sin, value.y * sin + value.z * cos);
         case Y -> new Vec3(value.x * cos + value.z * sin, value.y, -value.x * sin + value.z * cos);
         case Z -> new Vec3(value.x * cos - value.y * sin, value.x * sin + value.y * cos, value.z);
      };
   }

   /**
    * Exact 0 / ±1 for a right-angle turn. {@code Math.cos(π/2)} is a tiny residual, and
    * {@code floor} of that residual can drop a cell onto a diagonal neighbour. The bridge
    * then fills a third cell that a quarter turn must never produce.
    *
    * <p>A non-right angle keeps the sampled sine and cosine, so 45° bridging stays as it is.
    */
   private static double sin(double radians) {
      int quarterTurns = (int)Math.rint(radians / QUARTER_TURN);
      if (Math.abs(radians - quarterTurns * QUARTER_TURN) > ORTHOGONAL_EPSILON) {
         return Math.sin(radians);
      }
      return switch (Math.floorMod(quarterTurns, 4)) {
         case 1 -> 1.0;
         case 3 -> -1.0;
         default -> 0.0;
      };
   }

   private static double cos(double radians) {
      int quarterTurns = (int)Math.rint(radians / QUARTER_TURN);
      if (Math.abs(radians - quarterTurns * QUARTER_TURN) > ORTHOGONAL_EPSILON) {
         return Math.cos(radians);
      }
      return switch (Math.floorMod(quarterTurns, 4)) {
         case 0 -> 1.0;
         case 2 -> -1.0;
         default -> 0.0;
      };
   }

   /**
    * Turns one block state by the same angle that turned its cell.
    *
    * <p>The sign matters. {@link Rotation#CLOCKWISE_90} is
    * {@code OctahedralGroup.ROT_90_Y_NEG}, and it turns a north facing to east
    * ({@code Direction.getClockWise}, NORTH to EAST). Rotating a point by a positive angle
    * about +Y turns north to west, because {@code rotateVector} uses
    * {@code x' = x cos + z sin} and {@code z' = -x sin + z cos}. The state rotation is
    * therefore the opposite quarter turn of the position rotation.
    *
    * <p>Only the Y axis has a block-state rotation. An X or Z turn, and a Y turn that is
    * not a quarter turn, keep the state and move only the cell.
    */
   private static ClientBlockSnapshot rotateSnapshot(
      ClientBlockSnapshot snapshot, AxisGizmo.Axis axis, double radians
   ) {
      int quarterTurns = (int)Math.rint(radians / QUARTER_TURN);
      if (axis != AxisGizmo.Axis.Y || Math.abs(radians - quarterTurns * QUARTER_TURN) > ORTHOGONAL_EPSILON) {
         return snapshot;
      }
      int normalized = Math.floorMod(quarterTurns, 4);
      Rotation rotation = switch (normalized) {
         case 1 -> Rotation.COUNTERCLOCKWISE_90;
         case 2 -> Rotation.CLOCKWISE_180;
         case 3 -> Rotation.CLOCKWISE_90;
         default -> Rotation.NONE;
      };
      BlockState rotated = snapshot.state().rotate(rotation);
      return new ClientBlockSnapshot(rotated, snapshot.blockEntity());
   }
}
