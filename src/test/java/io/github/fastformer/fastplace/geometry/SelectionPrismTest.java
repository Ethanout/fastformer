package io.github.fastformer.fastplace.geometry;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class SelectionPrismTest {
   @Test
   void extrudesAnArbitraryPolygonToTheHeightPoint() {
      SelectionPrism prism = SelectionPrism.fromPoints(
         List.of(
            new BlockPos(0, 0, 0), new BlockPos(5, 0, 0), new BlockPos(4, 0, 3),
            new BlockPos(1, 0, 4), new BlockPos(0, 6, 0)
         ),
         4,
         BlockPos.ZERO,
         BlockPos.ZERO
      );

      assertNotNull(prism);
      assertTrue(prism.contains(new Vec3(2.5, 3.5, 2.0)));
      assertFalse(prism.contains(new Vec3(5.5, 3.5, 4.5)));
      assertTrue(prism.edges().size() == 12);
   }

   @Test
   void heightPointFollowsTheNormalAxisInsteadOfTheWorldHitSurface() {
      List<BlockPos> base = List.of(
         new BlockPos(0, 0, 0), new BlockPos(5, 0, 0), new BlockPos(4, 0, 3)
      );

      BlockPos height = SelectionPrism.resolveHeightPoint(
         base,
         new Vec3(0.5, 5.5, -10.0),
         new Vec3(0.0, 0.0, 1.0),
         1024.0
      );

      assertNotNull(height);
      assertEquals(5, height.getY());
      assertNotNull(SelectionPrism.fromPoints(List.of(base.get(0), base.get(1), base.get(2), height), 3, BlockPos.ZERO, BlockPos.ZERO));
   }

   @Test
   void fourthBasePointUsesTheViewIntersectionWithTheFirstThreePointPlane() {
      List<BlockPos> base = List.of(
         new BlockPos(0, 2, 0), new BlockPos(5, 2, 0), new BlockPos(1, 2, 4)
      );

      BlockPos point = SelectionPrism.resolveBasePlanePoint(
         base,
         new Vec3(8.5, 12.5, 7.5),
         new Vec3(0.0, -1.0, 0.0),
         64.0
      );

      assertEquals(new BlockPos(8, 2, 7), point);
   }

   @Test
   void fourthBasePointStaysOnAnObliqueFirstThreePointPlane() {
      List<BlockPos> base = List.of(
         new BlockPos(0, 0, 0), new BlockPos(4, 0, 0), new BlockPos(0, 4, 4)
      );

      BlockPos point = SelectionPrism.resolveBasePlanePoint(
         base,
         new Vec3(3.5, 10.5, 2.5),
         new Vec3(0.0, -1.0, 0.0),
         64.0
      );

      assertEquals(new BlockPos(3, 2, 2), point);
   }

   @Test
   void basePlanePointRejectsParallelOrOccludedIntersections() {
      List<BlockPos> base = List.of(
         new BlockPos(0, 0, 0), new BlockPos(5, 0, 0), new BlockPos(0, 0, 5)
      );

      assertEquals(null, SelectionPrism.resolveBasePlanePoint(
         base, new Vec3(0.5, 8.5, 0.5), new Vec3(1.0, 0.0, 0.0), 64.0
      ));
      assertEquals(null, SelectionPrism.resolveBasePlanePoint(
         base, new Vec3(0.5, 8.5, 0.5), new Vec3(0.0, -1.0, 0.0), 4.0
      ));
   }

   @Test
   void edgeInsertionReturnsAGridPointAndListInsertionIndex() {
      SelectionPrism.EdgeInsertion insertion = SelectionPrism.resolveEdgeInsertion(
         List.of(new BlockPos(0, 0, 0), new BlockPos(10, 0, 0), new BlockPos(10, 0, 10)),
         0,
         new Vec3(5.5, 8.5, 0.5),
         new Vec3(0.0, -1.0, 0.0),
         64.0
      );

      assertNotNull(insertion);
      assertEquals(1, insertion.insertionIndex());
      assertEquals(new BlockPos(5, 0, 0), insertion.point());
   }

   @Test
   void closedBaseWrapEdgeInsertsBeforeTheHeightPoint() {
      SelectionPrism.EdgeInsertion insertion = SelectionPrism.resolveEdgeInsertion(
         List.of(
            new BlockPos(0, 0, 0), new BlockPos(10, 0, 0), new BlockPos(10, 0, 10),
            new BlockPos(0, 0, 10), new BlockPos(0, 5, 0)
         ),
         4,
         new Vec3(0.5, 8.5, 5.5),
         new Vec3(0.0, -1.0, 0.0),
         64.0
      );

      assertNotNull(insertion);
      assertEquals(4, insertion.insertionIndex());
      assertEquals(new BlockPos(0, 0, 5), insertion.point());
   }

   @Test
   void planeStillResolvesWhenAnInsertedEdgePointMakesTheFirstThreeCollinear() {
      SelectionPrism.GridPlane plane = SelectionPrism.gridPlane(List.of(
         new BlockPos(0, 0, 0),
         new BlockPos(5, 0, 0),
         new BlockPos(10, 0, 0),
         new BlockPos(10, 0, 10)
      ));

      assertNotNull(plane);
      assertEquals(new BlockPos(4, 0, 7), plane.snap(new BlockPos(4, 6, 7)));
   }

   @Test
   void heightGridLineSnapsAllCoordinatesToTheDiscreteBaseNormal() {
      SelectionPrism.GridLine line = SelectionPrism.heightGridLine(List.of(
         new BlockPos(0, 0, 0),
         new BlockPos(4, 0, 0),
         new BlockPos(0, 0, 4)
      ));

      assertNotNull(line);
      assertEquals(new BlockPos(0, 7, 0), line.snap(new BlockPos(3, 7, 1)));
      assertEquals(new BlockPos(0, -5, 0), line.pointAtOffset(5.0));
   }

   @Test
   void broadSelectionIncludesBlocksTouchedAwayFromTheirCenters() {
      SelectionPrism prism = SelectionPrism.fromPoints(
         List.of(
            new BlockPos(0, 0, 0), new BlockPos(5, 2, 0), new BlockPos(4, 2, 4),
            new BlockPos(0, 0, 4), new BlockPos(0, 5, 0)
         ),
         4,
         BlockPos.ZERO,
         BlockPos.ZERO
      );
      assertNotNull(prism);
      boolean foundBroadOnlyBlock = false;
      for (int x = -2; x <= 7 && !foundBroadOnlyBlock; x++) {
         for (int y = -2; y <= 8 && !foundBroadOnlyBlock; y++) {
            for (int z = -2; z <= 6; z++) {
               BlockPos pos = new BlockPos(x, y, z);
               if (!prism.contains(Vec3.atCenterOf(pos)) && prism.intersects(new net.minecraft.world.phys.AABB(pos))) {
                  foundBroadOnlyBlock = true;
                  break;
               }
            }
         }
      }
      assertTrue(foundBroadOnlyBlock);
   }
}
