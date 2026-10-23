package io.github.fastformer.fastplace.fill;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

class SmartFillSearchTest {
   private static final Direction DOWN = Direction.DOWN;
   private static final Direction EAST = Direction.EAST;
   private static SmartFillSearch.Cell cell(boolean solid) {
      return solid ? SmartFillSearch.Cell.SOLID : SmartFillSearch.Cell.AIR;
   }

   @Test void fillsOnlyTheFrontLayerRegardlessOfWallThickness() {
      for (int thickness = 1; thickness <= 4; thickness++) {
         final int depth = thickness;
         var search = new SmartFillSearch(pos -> cell(pos.getX() >= 0 && pos.getX() < depth
            && (Math.abs(pos.getY()) > 1 || Math.abs(pos.getZ()) > 1)));
         var plan = search.find(BlockPos.ZERO, EAST);
         assertNotNull(plan, "wall thickness " + thickness);
         assertEquals(9, plan.targets().size());
         assertTrue(plan.targets().stream().allMatch(pos -> pos.getX() == 0));
      }
   }

   @Test void fillsOnlyTheSelectedLayerAbovePitBottoms() {
      for (int depth = 1; depth <= 8; depth++) {
         final int bottom = -depth;
         var search = new SmartFillSearch(pos -> cell(pos.getY() <= bottom
            || pos.getY() <= 0 && (Math.abs(pos.getX()) > 1 || Math.abs(pos.getZ()) > 1)));
         var plan = search.find(new BlockPos(0, bottom + 1, 0), DOWN);
         assertNotNull(plan);
         assertEquals(9, plan.targets().size());
         assertTrue(plan.targets().stream().allMatch(pos -> pos.getY() == bottom + 1));
      }
   }

   @Test void unevenBottomDoesNotChangeTheCap() {
      var search = new SmartFillSearch(pos -> cell(pos.getY() <= -1 - Math.floorMod(pos.getX(), 3)
         || pos.getY() <= 0 && (Math.abs(pos.getX()) > 1 || Math.abs(pos.getZ()) > 1)));
      var plan = search.find(BlockPos.ZERO, DOWN);
      assertNotNull(plan);
      assertEquals(9, plan.targets().size());
      assertTrue(plan.targets().stream().allMatch(pos -> pos.getY() == 0));
   }

   @Test void deeperPitStillProducesOnlyASurface() {
      var search = new SmartFillSearch(pos -> cell(pos.getY() <= -8
         || pos.getY() <= 0 && (Math.abs(pos.getX()) > 1 || Math.abs(pos.getZ()) > 1)));
      var plan = search.find(BlockPos.ZERO, DOWN);
      assertNotNull(plan);
      assertEquals(9, plan.targets().size());
      assertTrue(plan.targets().stream().allMatch(pos -> pos.getY() == 0));
   }

   @Test void roomWallCanBeFilledOnlyInTheSelectedPlane() {
      var search = new SmartFillSearch(pos -> cell(Math.abs(pos.getX()) > 3 || Math.abs(pos.getY()) > 3 || Math.abs(pos.getZ()) > 3));
      var plan = search.find(new BlockPos(-3, 0, 0), EAST);
      assertNotNull(plan);
      assertEquals(49, plan.targets().size());
      assertTrue(plan.targets().stream().allMatch(pos -> pos.getX() == -3));
   }

   @Test void openSkyDoesNotUseSearchLimitsAsWalls() {
      var search = new SmartFillSearch(pos -> SmartFillSearch.Cell.AIR);
      assertNull(search.find(BlockPos.ZERO, DOWN));
   }

   @Test void edgeGapLeakingToSkyIsRejected() {
      var search = new SmartFillSearch(pos -> cell(pos.getY() <= -4
         || pos.getY() <= 0 && (pos.getX() < -1 || Math.abs(pos.getZ()) > 1)));
      assertNull(search.find(BlockPos.ZERO, DOWN));
   }

   @Test void unknownCellCannotCloseTheBoundary() {
      var search = new SmartFillSearch(pos -> pos.equals(new BlockPos(2, 0, 0))
         ? SmartFillSearch.Cell.UNKNOWN : cell(pos.getY() <= -1 || pos.getY() <= 0
            && (Math.abs(pos.getX()) > 1 || Math.abs(pos.getZ()) > 1)));
      assertNull(search.find(BlockPos.ZERO, DOWN));
   }

   @Test void leakBehindTheMouthDoesNotAffectTheSurface() {
      var search = new SmartFillSearch(pos -> cell(pos.getY() <= -7 || pos.getY() <= 0
         && (Math.abs(pos.getX()) > 1 || Math.abs(pos.getZ()) > 1)
         && !(pos.getX() >= 2 && pos.getY() == -4 && pos.getZ() == 0)));
      var plan = search.find(BlockPos.ZERO, DOWN);
      assertNotNull(plan);
      assertEquals(9, plan.targets().size());
      assertTrue(plan.targets().stream().allMatch(pos -> pos.getY() == 0));
   }

   @Test void largeRoomProducesASinglePlaneInsteadOfItsVolume() {
      var search = new SmartFillSearch(pos -> cell(Math.abs(pos.getX()) > 12 || Math.abs(pos.getY()) > 12 || Math.abs(pos.getZ()) > 12));
      var plan = search.find(new BlockPos(-12, 0, 0), EAST);
      assertNotNull(plan);
      assertEquals(625, plan.targets().size());
      assertTrue(plan.targets().stream().allMatch(pos -> pos.getX() == -12));
   }

   @Test void occupiedSeedHasNoPlan() {
      assertNull(new SmartFillSearch(pos -> SmartFillSearch.Cell.SOLID).find(BlockPos.ZERO, EAST));
   }

   @Test void openHitFacePlaneStillAllowsAnotherBoundedPlaneAtTheSameSeed() {
      var search = new SmartFillSearch(pos -> cell(pos.getX() == 0
         && (Math.abs(pos.getY()) > 1 || Math.abs(pos.getZ()) > 1)));
      var plan = search.find(BlockPos.ZERO, Direction.UP);
      assertNotNull(plan);
      assertEquals(9, plan.targets().size());
      assertTrue(plan.targets().stream().allMatch(pos -> pos.getX() == 0));
   }

   @Test void multipleBoundedPlanesPreferTheHitFaceAxis() {
      for (Direction face : Direction.values()) {
         var search = new SmartFillSearch(pos -> cell(Math.abs(pos.getX()) > 1
            || Math.abs(pos.getY()) > 1 || Math.abs(pos.getZ()) > 1));
         var plan = search.find(BlockPos.ZERO, face);
         assertNotNull(plan);
         assertEquals(9, plan.targets().size());
         assertTrue(plan.targets().stream().allMatch(pos ->
            face.getAxis().choose(pos.getX(), pos.getY(), pos.getZ()) == 0));
      }
   }

   @Test void openSelectedPositionDoesNotFallBackToAnotherPosition() {
      var search = new SmartFillSearch(pos -> cell(pos.getX() == 0
         && (Math.abs(pos.getY()) > 1 || Math.abs(pos.getZ()) > 1)));
      assertNull(search.find(new BlockPos(1, 0, 0), EAST));
      assertNotNull(search.find(BlockPos.ZERO, EAST));
   }

   @Test void planeAtTheTargetLimitIsAccepted() {
      var search = new SmartFillSearch(pos -> cell(pos.getX() == 0
         && (pos.getY() < -15 || pos.getY() > 16 || pos.getZ() < -15 || pos.getZ() > 16)));
      var plan = search.find(BlockPos.ZERO, EAST);
      assertNotNull(plan);
      assertEquals(SmartFillSearch.MAX_TARGETS, plan.targets().size());
   }

   @Test void oversizedPlaneIsRejectedWithoutReturningAPartialFill() {
      var search = new SmartFillSearch(pos -> cell(pos.getX() == 0
         && (Math.abs(pos.getY()) > 16 || Math.abs(pos.getZ()) > 16)));
      assertNull(search.find(BlockPos.ZERO, EAST));
   }

   @Test void cubeEdgesAllowAdjacentFacesAfterTheFirstWall() {
      int end = 6;
      java.util.Set<BlockPos> blocks = cubeEdges(end);
      var faces = List.of(new BlockPos(0, 3, 3), new BlockPos(3, 3, 0),
         new BlockPos(6, 3, 3), new BlockPos(3, 3, 6), new BlockPos(3, 0, 3), new BlockPos(3, 6, 3));
      var directions = List.of(EAST, Direction.SOUTH, Direction.WEST, Direction.NORTH,
         Direction.UP, DOWN);
      for (int index = 0; index < faces.size(); index++) {
         var plan = new SmartFillSearch(pos -> cell(blocks.contains(pos))).find(faces.get(index), directions.get(index));
         assertNotNull(plan, "cube face " + index + " after earlier walls");
         assertEquals(25, plan.targets().size(), "only the face interior should be filled");
         var axis = directions.get(index).getAxis();
         var face = faces.get(index);
         assertTrue(plan.targets().stream().allMatch(pos -> {
            return axis.choose(pos.getX(), pos.getY(), pos.getZ())
               == axis.choose(face.getX(), face.getY(), face.getZ());
         }));
         blocks.addAll(plan.targets());
      }
      for (int x = 1; x < end; x++) for (int y = 1; y < end; y++) for (int z = 1; z < end; z++) {
         assertFalse(blocks.contains(new BlockPos(x, y, z)), "box interior must remain empty");
      }
   }

   @Test void adjacentFilledFaceDoesNotHideTheOpeningFromInside() {
      var blocks = cubeEdges(6);
      for (int y = 1; y < 6; y++) for (int z = 1; z < 6; z++) blocks.add(new BlockPos(0, y, z));
      var plan = new SmartFillSearch(pos -> cell(blocks.contains(pos)))
         .find(new BlockPos(3, 3, 0), Direction.NORTH);
      assertNotNull(plan);
      assertEquals(25, plan.targets().size());
   }

   @Test void thinWalledContainerIsCappedWithoutFillingItsInterior() {
      var blocks = cubeEdges(3);
      for (int x = 0; x <= 3; x++) for (int y = 0; y <= 3; y++) for (int z = 0; z <= 3; z++) {
         if (x == 0 || x == 3 || z == 0 || z == 3 || y == 0) blocks.add(new BlockPos(x, y, z));
      }
      var plan = new SmartFillSearch(pos -> cell(blocks.contains(pos)))
         .find(new BlockPos(1, 3, 1), DOWN);
      assertNotNull(plan);
      assertEquals(4, plan.targets().size());
      assertTrue(plan.targets().stream().allMatch(pos -> pos.getY() == 3));
   }

   private static java.util.Set<BlockPos> cubeEdges(int end) {
      java.util.Set<BlockPos> blocks = new java.util.HashSet<>();
      for (int x = 0; x <= end; x++) for (int y = 0; y <= end; y++) for (int z = 0; z <= end; z++) {
         int boundaries = (x == 0 || x == end ? 1 : 0) + (y == 0 || y == end ? 1 : 0) + (z == 0 || z == end ? 1 : 0);
         if (boundaries >= 2) blocks.add(new BlockPos(x, y, z));
      }
      return blocks;
   }
}
