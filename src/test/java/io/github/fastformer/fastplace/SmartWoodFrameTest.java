package io.github.fastformer.fastplace;

import io.github.fastformer.fastplace.world.*;

import io.github.fastformer.fastplace.session.*;
import io.github.fastformer.fastplace.workflow.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fastformer.fastplace.geometry.generation.LineGenerator;
import io.github.fastformer.fastplace.geometry.generation.LineTieBias;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

class SmartWoodFrameTest {
   @Test
   void lineAndFaceStagesOrientTheirGeneratedOutline() {
      FastPlaceGeometry.Modes modes = new FastPlaceGeometry.Modes(
         PointMode.RAYCAST, RaycastPlacement.EMBEDDED, LineMode.AXIS,
         FaceMode.COORDINATE_PLANE, VolumeMode.FREE, FillMode.OUTLINE, 0.0, false
      );
      for (List<BlockPos> points : List.of(
         List.of(BlockPos.ZERO, new BlockPos(6, 0, 0)),
         List.of(BlockPos.ZERO, new BlockPos(6, 0, 0), new BlockPos(0, 0, 4))
      )) {
         Set<BlockPos> targets = FastPlaceGeometry.blocks(
            points, modes, false, PolygonVolumeShape.EXTRUDE, 1000
         );
         SmartWoodFrame.Config config = SmartWoodFrame.config(
            Direction.Axis.Y, points, modes, false, PolygonVolumeShape.EXTRUDE
         );
         assertTrue(targets.contains(new BlockPos(3, 0, 0)));
         assertEquals(Direction.Axis.X, SmartWoodFrame.axisForTest(targets, new BlockPos(3, 0, 0), config));
         if (points.size() == 3) {
            assertTrue(targets.contains(new BlockPos(6, 0, 2)));
            assertEquals(Direction.Axis.Z, SmartWoodFrame.axisForTest(targets, new BlockPos(6, 0, 2), config));
         }
      }
   }

   @Test
   void slopedGuideUsesItsClosestWorldAxis() {
      Set<BlockPos> outline = Set.of(new BlockPos(0, 0, 0), new BlockPos(5, 1, 0), new BlockPos(10, 2, 0));
      SmartWoodFrame.Config config = new SmartWoodFrame.Config(
         Direction.Axis.Y, List.of(new BlockPos(0, 0, 0), new BlockPos(10, 2, 0))
      );

      assertEquals(Direction.Axis.X, SmartWoodFrame.axisForTest(outline, new BlockPos(5, 1, 0), config));
   }

   @Test
   void aCornerUsesStableLargestComponentTieBreak() {
      Set<BlockPos> corner = Set.of(
         new BlockPos(0, 0, 0), new BlockPos(1, 0, 0), new BlockPos(0, 1, 0)
      );
      SmartWoodFrame.Config config = new SmartWoodFrame.Config(Direction.Axis.Y, List.of());

      // X/Y are equal at an orthogonal corner; ties are stable and do not use
      // the clicked face as an arbitrary bias.
      assertEquals(Direction.Axis.X, SmartWoodFrame.axisForTest(corner, BlockPos.ZERO, config));
   }

   @Test
   void cuboidOppositeCornersDoNotBecomeOneDiagonalAxis() {
      Set<BlockPos> outline = new java.util.HashSet<>();
      for (int x = 0; x <= 2; x++) {
         for (int y = 0; y <= 2; y++) {
            for (int z = 0; z <= 2; z++) {
               int boundary = (x == 0 || x == 2 ? 1 : 0) + (y == 0 || y == 2 ? 1 : 0)
                  + (z == 0 || z == 2 ? 1 : 0);
               if (boundary >= 2) outline.add(new BlockPos(x, y, z));
            }
         }
      }
      SmartWoodFrame.Config config = new SmartWoodFrame.Config(
         Direction.Axis.Y, List.of(BlockPos.ZERO, new BlockPos(2, 2, 2))
      );

      assertEquals(Direction.Axis.X, SmartWoodFrame.axisForTest(outline, new BlockPos(1, 0, 0), config));
      assertEquals(Direction.Axis.Z, SmartWoodFrame.axisForTest(outline, new BlockPos(0, 0, 1), config));
   }

   @Test
   void guideDoesNotOverrideAnUnambiguousFrameEdge() {
      Set<BlockPos> edge = Set.of(
         new BlockPos(0, 0, 0), new BlockPos(1, 0, 0), new BlockPos(2, 0, 0)
      );
      SmartWoodFrame.Config config = new SmartWoodFrame.Config(
         Direction.Axis.Y, List.of(new BlockPos(0, 0, 0), new BlockPos(0, 2, 2), new BlockPos(2, 0, 0))
      );

      assertEquals(Direction.Axis.X, SmartWoodFrame.axisForTest(edge, new BlockPos(1, 0, 0), config));
   }

   @Test
   void blocksInOneEdgeInheritTheSameAxisBeforeCornerTieBreaks() {
      Set<BlockPos> frame = Set.of(
         new BlockPos(0, 0, 0), new BlockPos(1, 0, 0), new BlockPos(2, 0, 0),
         new BlockPos(2, 0, 1), new BlockPos(2, 0, 2)
      );
      SmartWoodFrame.Config config = new SmartWoodFrame.Config(Direction.Axis.Y, List.of());

      assertEquals(Direction.Axis.X, SmartWoodFrame.axisForTest(frame, new BlockPos(1, 0, 0), config));
      assertEquals(Direction.Axis.Z, SmartWoodFrame.axisForTest(frame, new BlockPos(2, 0, 1), config));
      // X/Z are tied, so the stable axis order selects X; base Y is not an
      // incident edge and must not override the geometric candidates.
      assertEquals(Direction.Axis.X, SmartWoodFrame.axisForTest(frame, new BlockPos(2, 0, 0), config));
   }

   @Test
   void cornerUsesStableTieBreakRegardlessOfBaseAxis() {
      Set<BlockPos> frame = Set.of(
         new BlockPos(0, 0, 0), new BlockPos(1, 0, 0), new BlockPos(2, 0, 0),
         new BlockPos(3, 0, 0), new BlockPos(4, 0, 0), new BlockPos(4, 1, 0)
      );
      SmartWoodFrame.Config config = new SmartWoodFrame.Config(Direction.Axis.Y, List.of());

      assertEquals(Direction.Axis.X, SmartWoodFrame.axisForTest(frame, new BlockPos(4, 0, 0), config));
   }

   @Test
   void digitalSlopeInheritsOneAxisForTheWholeGuideEdge() {
      BlockPos from = new BlockPos(0, 0, 0);
      BlockPos to = new BlockPos(9, 4, 0);
      Set<BlockPos> edge = Set.copyOf(
         LineGenerator.path(from, to)
      );
      SmartWoodFrame.Config config = new SmartWoodFrame.Config(Direction.Axis.Y, List.of(from, to));

      for (BlockPos position : edge) {
         assertEquals(Direction.Axis.X, SmartWoodFrame.axisForTest(edge, position, config));
      }
   }

   @Test
   void authoredPrismEdgesAssignAxesByWholeEdge() {
      List<BlockPos> points = List.of(
         new BlockPos(0, 0, 0), new BlockPos(3, 0, 0),
         new BlockPos(0, 0, 2), new BlockPos(0, 4, 2)
      );
      List<SmartWoodFrame.Edge> edges = SmartWoodFrame.edgeGuides(
         points, FaceMode.COORDINATE_PLANE, false, PolygonVolumeShape.EXTRUDE
      );
      Set<BlockPos> outline = new java.util.HashSet<>();
      for (SmartWoodFrame.Edge edge : edges) {
         outline.addAll(LineGenerator.path(edge.from(), edge.to()));
      }
      SmartWoodFrame.Config config = new SmartWoodFrame.Config(Direction.Axis.Y, points, edges);

      assertEquals(Direction.Axis.X, SmartWoodFrame.axisForTest(outline, new BlockPos(1, 0, 0), config));
      assertEquals(Direction.Axis.Z, SmartWoodFrame.axisForTest(outline, new BlockPos(0, 0, 1), config));
      assertEquals(Direction.Axis.Y, SmartWoodFrame.axisForTest(outline, new BlockPos(0, 2, 0), config));
   }

   @Test
   void tiltedPrismProducesTwelveEdgeGuidesWithPerEdgeAxes() {
      List<BlockPos> points = List.of(
         new BlockPos(0, 0, 0), new BlockPos(4, 0, 0), new BlockPos(0, 0, 4), new BlockPos(0, 5, 4)
      );
      List<SmartWoodFrame.Edge> edges = SmartWoodFrame.edgeGuides(
         points, FaceMode.COORDINATE_PLANE, false, PolygonVolumeShape.EXTRUDE
      );
      assertEquals(12, edges.size());
      Set<BlockPos> outline = new java.util.HashSet<>();
      for (SmartWoodFrame.Edge edge : edges) {
         outline.addAll(LineGenerator.path(edge.from(), edge.to()));
      }
      SmartWoodFrame.Config config = new SmartWoodFrame.Config(Direction.Axis.Y, points, edges);
      assertEquals(Direction.Axis.X, SmartWoodFrame.axisForTest(outline, new BlockPos(2, 0, 0), config));
      assertEquals(Direction.Axis.Z, SmartWoodFrame.axisForTest(outline, new BlockPos(0, 0, 2), config));
      assertEquals(Direction.Axis.Y, SmartWoodFrame.axisForTest(outline, new BlockPos(0, 2, 4), config));
   }

   @Test
   void generatedTiltedPrismKeepsAllTwelveAuthoredEdgeDirections() {
      List<BlockPos> points = List.of(
         new BlockPos(0, 0, 0),
         new BlockPos(8, 3, 1),
         new BlockPos(1, 1, 7),
         new BlockPos(3, 8, 9)
      );
      FastPlaceGeometry.Modes modes = new FastPlaceGeometry.Modes(
         PointMode.RAYCAST,
         RaycastPlacement.EMBEDDED,
         LineMode.AXIS,
         FaceMode.COORDINATE_PLANE,
         VolumeMode.FREE,
         FillMode.OUTLINE,
         0.0,
         false
      ).withFaceTieBias(LineTieBias.OPPOSITE);
      Set<BlockPos> outline = FastPlaceGeometry.blocks(
         points, modes, false, PolygonVolumeShape.EXTRUDE, 10_000
      );
      SmartWoodFrame.Config config = SmartWoodFrame.config(
         Direction.Axis.Y, points, modes, false, PolygonVolumeShape.EXTRUDE
      );

      assertEquals(12, config.edges().size());
      for (SmartWoodFrame.Edge edge : config.edges()) {
         List<BlockPos> path = LineGenerator.path(
            edge.from(), edge.to(), config.tieBias()
         );
         Direction.Axis expected = dominantAxis(edge);
         for (int index = 1; index < path.size() - 1; index++) {
            BlockPos member = path.get(index);
            assertTrue(outline.contains(member));
            assertEquals(expected, SmartWoodFrame.axisForTest(outline, member, config));
         }
      }
   }

   @Test
   void axisAlignedCuboidAssignsAllTwelveEdgesWithoutSplittingColumns() {
      List<BlockPos> points = List.of(
         new BlockPos(0, 0, 0),
         new BlockPos(2, 0, 0),
         new BlockPos(0, 0, 4),
         new BlockPos(0, 3, 4)
      );
      FastPlaceGeometry.Modes modes = new FastPlaceGeometry.Modes(
         PointMode.RAYCAST,
         RaycastPlacement.EMBEDDED,
         LineMode.AXIS,
         FaceMode.COORDINATE_PLANE,
         VolumeMode.PERPENDICULAR_TO_FACE,
         FillMode.OUTLINE,
         0.0,
         false
      );
      Set<BlockPos> targets = FastPlaceGeometry.blocks(
         points, modes, false, PolygonVolumeShape.EXTRUDE, 1_000
      );
      SmartWoodFrame.Config config = SmartWoodFrame.config(
         Direction.Axis.Y, points, modes, false, PolygonVolumeShape.EXTRUDE
      );

      for (int y : new int[]{0, 3}) {
         for (int z : new int[]{0, 4}) {
            assertEquals(Direction.Axis.X, SmartWoodFrame.axisForTest(targets, new BlockPos(1, y, z), config));
         }
      }
      for (int x : new int[]{0, 2}) {
         for (int z : new int[]{0, 4}) {
            assertEquals(Direction.Axis.Y, SmartWoodFrame.axisForTest(targets, new BlockPos(x, 1, z), config));
            assertEquals(Direction.Axis.Y, SmartWoodFrame.axisForTest(targets, new BlockPos(x, 2, z), config));
         }
      }
      for (int x : new int[]{0, 2}) {
         for (int y : new int[]{0, 3}) {
            for (int z = 1; z < 4; z++) {
               assertEquals(Direction.Axis.Z, SmartWoodFrame.axisForTest(targets, new BlockPos(x, y, z), config));
            }
         }
      }
   }

   @Test
   void rasterizedMembershipWinsOverACloserUnrelatedContinuousEdge() {
      SmartWoodFrame.Edge owningEdge = new SmartWoodFrame.Edge(
         new BlockPos(-3, -3, -3), new BlockPos(-3, -2, 1)
      );
      SmartWoodFrame.Edge nearbyEdge = new SmartWoodFrame.Edge(
         new BlockPos(-3, -3, -2), new BlockPos(-2, -1, 0)
      );
      BlockPos member = new BlockPos(-3, -2, -1);
      SmartWoodFrame.Config config = new SmartWoodFrame.Config(
         Direction.Axis.X, List.of(), List.of(owningEdge, nearbyEdge)
      );

      assertEquals(Direction.Axis.Z, SmartWoodFrame.axisForTest(Set.of(member), member, config));
   }

   @Test
   void cornerFollowsTheLongestIncidentEdge() {
      SmartWoodFrame.Edge shallow = new SmartWoodFrame.Edge(
         new BlockPos(0, 0, 0), new BlockPos(10, 2, 0)
      );
      SmartWoodFrame.Edge steep = new SmartWoodFrame.Edge(
         new BlockPos(0, 0, 0), new BlockPos(1, 10, 0)
      );
      Set<BlockPos> positions = Set.of(BlockPos.ZERO);
      SmartWoodFrame.Config config = new SmartWoodFrame.Config(
         Direction.Axis.Z, List.of(), List.of(shallow, steep)
      );

      assertEquals(Direction.Axis.X, SmartWoodFrame.axisForTest(positions, BlockPos.ZERO, config));
      SmartWoodFrame.Config reversed = new SmartWoodFrame.Config(
         Direction.Axis.Z, List.of(), List.of(steep, shallow)
      );
      assertEquals(Direction.Axis.X, SmartWoodFrame.axisForTest(positions, BlockPos.ZERO, reversed));
   }

   private static Direction.Axis dominantAxis(SmartWoodFrame.Edge edge) {
      int x = Math.abs(edge.to().getX() - edge.from().getX());
      int y = Math.abs(edge.to().getY() - edge.from().getY());
      int z = Math.abs(edge.to().getZ() - edge.from().getZ());
      int maximum = Math.max(x, Math.max(y, z));
      return x == maximum ? Direction.Axis.X : y == maximum ? Direction.Axis.Y : Direction.Axis.Z;
   }
}
