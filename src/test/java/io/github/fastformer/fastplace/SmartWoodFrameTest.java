package io.github.fastformer.fastplace;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

class SmartWoodFrameTest {
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
         io.github.fastformer.fastplace.geometry.generation.LineGenerator.path(from, to)
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
         outline.addAll(io.github.fastformer.fastplace.geometry.generation.LineGenerator.path(edge.from(), edge.to()));
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
         outline.addAll(io.github.fastformer.fastplace.geometry.generation.LineGenerator.path(edge.from(), edge.to()));
      }
      SmartWoodFrame.Config config = new SmartWoodFrame.Config(Direction.Axis.Y, points, edges);
      assertEquals(Direction.Axis.X, SmartWoodFrame.axisForTest(outline, new BlockPos(2, 0, 0), config));
      assertEquals(Direction.Axis.Z, SmartWoodFrame.axisForTest(outline, new BlockPos(0, 0, 2), config));
      assertEquals(Direction.Axis.Y, SmartWoodFrame.axisForTest(outline, new BlockPos(0, 2, 4), config));
   }

   @Test
   void cornerUsesTheLargestAbsoluteComponentAcrossIncidentDirections() {
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

      assertEquals(Direction.Axis.Y, SmartWoodFrame.axisForTest(positions, BlockPos.ZERO, config));
   }
}
