package io.github.fastformer.client.operation.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fastformer.client.operation.selection.OccupiedBlockBounds;
import io.github.fastformer.fastplace.geometry.AxisGizmo;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/**
 * Pure geometry of voxel rotation. A right-angle turn must keep the lattice: same cell count,
 * same face adjacency, and the same formula as {@link VoxelRotation#rotatePoint}.
 */
class VoxelRotationGeometryTest {
   private static final double QUARTER = Math.PI / 2.0;
   private static final double[] RIGHT_ANGLES = {
      QUARTER, -QUARTER, Math.PI, -Math.PI, 3.0 * QUARTER, -3.0 * QUARTER
   };

   @Test
   void twoFaceAdjacentCellsStayTwoCellsAfterAPositiveYQuarterTurn() {
      Map<BlockPos, String> source = labelled(BlockPos.ZERO, new BlockPos(0, 0, -1));

      VoxelRotation.RotationResult<String> rotated = VoxelRotation.rotateStage(
         source, new Vec3(0.0, QUARTER, 0.0)
      );

      assertEquals(Set.of(BlockPos.ZERO, new BlockPos(1, 0, 0)), rotated.values().keySet());
      assertEquals("c0", rotated.values().get(new BlockPos(1, 0, 0)));
      assertEquals("c1", rotated.values().get(BlockPos.ZERO));
   }

   @Test
   void rotatePointUsesExactRightAngleSineAndCosine() {
      Vec3 point = new Vec3(1.0, 2.0, 3.0);
      Vec3 origin = Vec3.ZERO;

      assertEquals(new Vec3(3.0, 2.0, -1.0), VoxelRotation.rotatePoint(point, origin, AxisGizmo.Axis.Y, QUARTER));
      assertEquals(new Vec3(-3.0, 2.0, 1.0), VoxelRotation.rotatePoint(point, origin, AxisGizmo.Axis.Y, -QUARTER));
      assertEquals(new Vec3(-1.0, 2.0, -3.0), VoxelRotation.rotatePoint(point, origin, AxisGizmo.Axis.Y, Math.PI));

      assertEquals(new Vec3(1.0, -3.0, 2.0), VoxelRotation.rotatePoint(point, origin, AxisGizmo.Axis.X, QUARTER));
      assertEquals(new Vec3(1.0, 3.0, -2.0), VoxelRotation.rotatePoint(point, origin, AxisGizmo.Axis.X, -QUARTER));
      assertEquals(new Vec3(1.0, -2.0, -3.0), VoxelRotation.rotatePoint(point, origin, AxisGizmo.Axis.X, Math.PI));

      assertEquals(new Vec3(-2.0, 1.0, 3.0), VoxelRotation.rotatePoint(point, origin, AxisGizmo.Axis.Z, QUARTER));
      assertEquals(new Vec3(2.0, -1.0, 3.0), VoxelRotation.rotatePoint(point, origin, AxisGizmo.Axis.Z, -QUARTER));
      assertEquals(new Vec3(-1.0, -2.0, 3.0), VoxelRotation.rotatePoint(point, origin, AxisGizmo.Axis.Z, Math.PI));
   }

   @Test
   void rotatedCellsMatchFloorOfRotatePoint() {
      Vec3 pivot = new Vec3(10.5, -4.5, 7.5);
      Map<BlockPos, String> source = labelled(
         new BlockPos(0, 0, 0),
         new BlockPos(1, 0, 0),
         new BlockPos(0, 0, -1),
         new BlockPos(-2, 3, 4)
      );
      for (AxisGizmo.Axis axis : AxisGizmo.Axis.values()) {
         for (double radians : RIGHT_ANGLES) {
            Map<BlockPos, String> rotated = VoxelRotation.rotateValues(source, pivot, axis, radians);
            Map<BlockPos, String> expected = new LinkedHashMap<>();
            source.forEach((pos, value) -> expected.putIfAbsent(cellOf(pos, pivot, axis, radians), value));
            assertEquals(expected, rotated, axis + " " + radians);
         }
      }
   }

   @Test
   void evenAndOddWidthLinesKeepCountAndFaceAdjacencyOnEveryAxis() {
      for (AxisGizmo.Axis lineAxis : AxisGizmo.Axis.values()) {
         Map<BlockPos, String> even = line(lineAxis, 2);
         Map<BlockPos, String> odd = line(lineAxis, 3);
         Vec3 occupiedPivot = OccupiedBlockBounds.from(even.keySet()).orElseThrow().center();
         Vec3 oddPivot = OccupiedBlockBounds.from(odd.keySet()).orElseThrow().center();
         Vec3 translatedPivot = occupiedPivot.add(8.0, -3.0, 5.0);
         for (AxisGizmo.Axis axis : AxisGizmo.Axis.values()) {
            for (double radians : RIGHT_ANGLES) {
               assertPreserved("even occupied " + lineAxis + " " + axis, even, occupiedPivot, axis, radians);
               assertPreserved("odd occupied " + lineAxis + " " + axis, odd, oddPivot, axis, radians);
               assertPreserved("even translated " + lineAxis + " " + axis, even, translatedPivot, axis, radians);
            }
         }
      }
   }

   @Test
   void multiQuadrantCornersStaySparseAfterARightAngleTurn() {
      Map<BlockPos, String> source = labelled(
         new BlockPos(3, 0, 2),
         new BlockPos(-4, 0, 2),
         new BlockPos(3, 0, -5),
         new BlockPos(-4, 0, -5)
      );
      Vec3 pivot = OccupiedBlockBounds.from(source.keySet()).orElseThrow().center();
      for (AxisGizmo.Axis axis : AxisGizmo.Axis.values()) {
         for (double radians : RIGHT_ANGLES) {
            Map<BlockPos, String> rotated = VoxelRotation.rotateValues(source, pivot, axis, radians);
            assertEquals(4, rotated.size(), axis + " " + radians);
            assertEquals(Set.copyOf(source.values()), Set.copyOf(rotated.values()), axis + " " + radians);
         }
      }
   }

   @Test
   void aNonRightAngleStillBridgesADiagonalStaircase() {
      Map<BlockPos, String> source = labelled(BlockPos.ZERO, new BlockPos(1, 0, 0));

      Map<BlockPos, String> rotated = VoxelRotation.rotateValues(
         source, new Vec3(0.5, 0.5, 0.5), AxisGizmo.Axis.Y, Math.toRadians(45.0)
      );

      assertEquals("c0", rotated.get(BlockPos.ZERO));
      assertEquals("c1", rotated.get(new BlockPos(1, 0, -1)));
      assertEquals("c0", rotated.get(new BlockPos(1, 0, 0)));
      assertEquals(3, rotated.size());
   }

   private static void assertPreserved(
      String label, Map<BlockPos, String> source, Vec3 pivot, AxisGizmo.Axis axis, double radians
   ) {
      Map<BlockPos, String> rotated = VoxelRotation.rotateValues(source, pivot, axis, radians);
      assertEquals(source.size(), rotated.size(), label + " count");
      List<BlockPos> sourceCells = new ArrayList<>(source.keySet());
      for (int i = 0; i < sourceCells.size(); i++) {
         for (int j = i + 1; j < sourceCells.size(); j++) {
            BlockPos a = sourceCells.get(i);
            BlockPos b = sourceCells.get(j);
            if (!faceAdjacent(a, b)) {
               continue;
            }
            assertTrue(
               faceAdjacent(cellOf(a, pivot, axis, radians), cellOf(b, pivot, axis, radians)),
               label + " lost adjacency " + a + " -> " + b
            );
         }
      }
   }

   private static BlockPos cellOf(BlockPos source, Vec3 pivot, AxisGizmo.Axis axis, double radians) {
      Vec3 rotated = VoxelRotation.rotatePoint(Vec3.atCenterOf(source), pivot, axis, radians);
      return BlockPos.containing(Math.floor(rotated.x), Math.floor(rotated.y), Math.floor(rotated.z));
   }

   private static boolean faceAdjacent(BlockPos a, BlockPos b) {
      return Math.abs(a.getX() - b.getX()) + Math.abs(a.getY() - b.getY()) + Math.abs(a.getZ() - b.getZ()) == 1;
   }

   private static Map<BlockPos, String> line(AxisGizmo.Axis axis, int length) {
      BlockPos[] positions = new BlockPos[length];
      for (int index = 0; index < length; index++) {
         positions[index] = switch (axis) {
            case X -> new BlockPos(index, 0, 0);
            case Y -> new BlockPos(0, index, 0);
            case Z -> new BlockPos(0, 0, -index);
         };
      }
      return labelled(positions);
   }

   private static Map<BlockPos, String> labelled(BlockPos... positions) {
      LinkedHashMap<BlockPos, String> source = new LinkedHashMap<>();
      for (int index = 0; index < positions.length; index++) {
         source.put(positions[index], "c" + index);
      }
      return source;
   }
}
