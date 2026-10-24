package io.github.fastformer.fastplace.geometry.movement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import org.junit.jupiter.api.Test;

class ForwardDashPathTest {
   private static final AABB PLAYER = new AABB(-0.3, 0, -0.3, 0.3, 1.8, 0.3);

   @Test
   void unobstructedTravelKeepsAllFifteenBlocks() {
      assertEquals(1, ForwardDashPath.clearFraction(PLAYER, new Vec3(15, 0, 0), List.of()));
   }

   @Test
   void thinWallStopsTheWholeBodyBeforeItEvenWhenTheDestinationIsEmpty() {
      Vec3 movement = new Vec3(15, 0, 0);
      var wall = Shapes.create(new AABB(5, -1, -1, 5.05, 3, 1));
      double travel = ForwardDashPath.clearFraction(PLAYER, movement, List.of(wall)) * 15;
      assertEquals(4.69, travel, 1.0E-6);
      assertTrue(PLAYER.move(movement.scale(travel / 15)).maxX < 5);
   }

   @Test
   void headAndFeetBothBlockTheDash() {
      Vec3 movement = new Vec3(15, 0, 0);
      var overhang = Shapes.create(new AABB(5, 1.6, -1, 6, 2, 1));
      var slab = Shapes.create(new AABB(5, 0, -1, 6, 0.5, 1));
      assertEquals(4.69, ForwardDashPath.clearFraction(PLAYER, movement, List.of(overhang)) * 15, 1.0E-6);
      assertEquals(4.69, ForwardDashPath.clearFraction(PLAYER, movement, List.of(slab)) * 15, 1.0E-6);
   }

   @Test
   void contactWithFloorOrWallAllowsParallelTravelAndMovingAway() {
      var floor = Shapes.create(new AABB(-20, -1, -20, 20, 0, 20));
      var touchingWall = Shapes.create(new AABB(0.3, -1, -20, 1.3, 3, 20));
      assertEquals(1, ForwardDashPath.clearFraction(PLAYER, new Vec3(0, 0, 15), List.of(floor, touchingWall)));
      assertEquals(1, ForwardDashPath.clearFraction(PLAYER, new Vec3(-15, 0, 0), List.of(touchingWall)));
      assertEquals(0, ForwardDashPath.clearFraction(PLAYER, new Vec3(15, 0, 0), List.of(touchingWall)));
   }

   @Test
   void allAxisDirectionsUseTheNearestObstacle() {
      for (var axis : net.minecraft.core.Direction.Axis.values()) {
         for (int sign : new int[] {-1, 1}) {
            Vec3 movement = switch (axis) {
               case X -> new Vec3(15 * sign, 0, 0);
               case Y -> new Vec3(0, 15 * sign, 0);
               case Z -> new Vec3(0, 0, 15 * sign);
            };
            AABB box = new AABB(-0.3, -0.3, -0.3, 0.3, 0.3, 0.3);
            var first = Shapes.create(box.move(movement.normalize().scale(5)));
            var last = Shapes.create(box.move(movement.normalize().scale(10)));
            assertEquals(4.39, ForwardDashPath.clearFraction(box, movement, List.of(last, first)) * 15, 1.0E-6);
         }
      }
   }

   @Test
   void diagonalTravelDoesNotSlideAroundACornerOrStopForDistantSideObstacles() {
      Vec3 movement = new Vec3(10, 0, 10);
      var corner = Shapes.create(new AABB(5, 0, 5, 6, 3, 6));
      var offPath = Shapes.create(new AABB(1, 0, 8, 2, 3, 9));
      double fraction = ForwardDashPath.clearFraction(PLAYER, movement, List.of(corner, offPath));
      assertTrue(fraction < 0.47 && fraction > 0.46);
      assertEquals(1, ForwardDashPath.clearFraction(PLAYER, movement, List.of(offPath)));
   }
}
